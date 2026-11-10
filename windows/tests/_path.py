"""Make `mvm` (windows/) and the embedded server (server/) importable when running from the repo."""
import sys
from pathlib import Path

WINDOWS = Path(__file__).resolve().parents[1]
SERVER = WINDOWS.parent / "server"
for p in (str(WINDOWS), str(SERVER)):
    if p not in sys.path:
        sys.path.insert(0, p)
