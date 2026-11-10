# -*- mode: python ; coding: utf-8 -*-
# PyInstaller onedir build:  pyinstaller --noconfirm windows/packaging/MarstekVenusMonitor.spec
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files

PACK = Path(SPECPATH).resolve()
WIN = PACK.parent
REPO = WIN.parent
SERVER = REPO / "server"

# The server ships as source next to the exe (_internal/server) and is imported from there,
# so the embedded dashboard runs exactly the same code as the standalone server.
datas = [(str(p), "server") for p in SERVER.glob("*.py")]
datas += [(str(SERVER / name), "server") for name in
          ("index.html", "app.js", "style.css", "sw.js", "favicon.svg", "manifest.webmanifest")]
datas += [(str(p), "server/icons") for p in (SERVER / "icons").glob("*.png")]
datas += [(str(p), "mvm/web") for p in (WIN / "mvm" / "web").iterdir() if p.is_file()]
datas += [(str(WIN / "assets" / "app.ico"), "assets")]
datas += [(str(REPO / "LICENSE"), "."), (str(REPO / "NOTICE"), ".")]
datas += collect_data_files("tzdata")

a = Analysis(
    [str(PACK / "launcher.py")],
    pathex=[str(WIN)],
    datas=datas,
    hiddenimports=["mvm._server_deps", "pystray._win32", "tzdata", "qrcode.image.svg"],
    excludes=["tkinter", "unittest", "pydoc", "test"],
    noarchive=False,
)
# The server modules must come from _internal/server, never from the PYZ archive.
SERVER_MODULES = {p.stem for p in SERVER.glob("*.py")}
clash = [name for name, *_ in a.pure if name.split(".")[0] in SERVER_MODULES]
assert not clash, f"PYZ would shadow server modules: {clash}"

pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="MarstekVenusMonitor",
    icon=str(WIN / "assets" / "app.ico"),
    version=str(PACK / "version_info.txt"),
    console=False,
    disable_windowed_traceback=False,
    upx=False,
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="MarstekVenusMonitor")
