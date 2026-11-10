import json
import threading
import unittest
from pathlib import Path
from urllib.request import urlopen

from server import Handler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]


class ProgressiveWebAppTests(unittest.TestCase):
    def test_manifest_has_chromium_install_requirements(self):
        manifest = json.loads((ROOT / "manifest.webmanifest").read_text(encoding="utf-8"))
        self.assertEqual(manifest["display"], "standalone")
        self.assertEqual(manifest["start_url"], "/")
        self.assertFalse(manifest["prefer_related_applications"])
        icons = {icon["sizes"] for icon in manifest["icons"]}
        self.assertIn("192x192", icons)
        self.assertIn("512x512", icons)
        for icon in manifest["icons"]:
            self.assertTrue((ROOT / icon["src"].lstrip("/")).is_file())

    def test_service_worker_does_not_cache_live_api(self):
        worker = (ROOT / "sw.js").read_text(encoding="utf-8")
        self.assertIn("url.pathname.startsWith('/api/')", worker)
        self.assertIn("cache.addAll(SHELL)", worker)
        self.assertIn("marstek-shell-v8", worker)
        self.assertIn("ignoreSearch: true", worker)

    def test_index_links_manifest_and_install_button(self):
        html = (ROOT / "index.html").read_text(encoding="utf-8")
        self.assertIn('rel="manifest" href="/manifest.webmanifest"', html)
        self.assertIn('id="install-app"', html)
        self.assertIn('apple-touch-icon', html)
        self.assertIn('href="/style.css?v=8"', html)

    def test_pwa_assets_are_served_with_expected_content_types(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            for path, expected in (
                ("/manifest.webmanifest", "application/manifest+json"),
                ("/sw.js", "application/javascript"),
                ("/icons/marstek-192.png", "image/png"),
                ("/icons/marstek-512.png", "image/png"),
                ("/favicon.svg", "image/svg+xml"),
            ):
                with urlopen(base + path, timeout=3) as response:
                    self.assertEqual(response.status, 200)
                    self.assertIn(expected, response.headers["Content-Type"])
                    self.assertGreater(len(response.read()), 100)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
