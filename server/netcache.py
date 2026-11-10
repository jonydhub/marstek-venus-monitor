"""Small polite HTTP client (JSON or text): TTL cache, ETag / If-Modified-Since, explicit User-Agent."""
from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request

USER_AGENT = "marstek-venus-monitor/1.0 (+https://github.com/jonydhub/marstek-venus-monitor; read-only home monitor)"


def urllib_opener(url, headers, timeout):
    """Return (status, headers, body). 304 is a normal answer, not an exception."""
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        if error.code == 304:
            return 304, dict(error.headers or {}), b""
        raise


class CachedJsonClient:
    def __init__(self, opener=urllib_opener, ttl=600, clock=time.monotonic, timeout=20,
                 accept="application/json", as_text=False):
        self.opener, self.ttl, self.clock, self.timeout, self.accept = opener, ttl, clock, timeout, accept
        self.as_text = as_text  # return the decoded body instead of parsed JSON (HTML pages)
        self.lock = threading.Lock()
        self._cache = {}  # url -> {"data", "at", "etag", "last_modified"}

    def get(self, url):
        with self.lock:
            entry = self._cache.get(url)
            now = self.clock()
            if entry and now - entry["at"] < self.ttl:
                return entry["data"]
            headers = {"User-Agent": USER_AGENT, "Accept": self.accept}
            if entry and entry.get("etag"):
                headers["If-None-Match"] = entry["etag"]
            if entry and entry.get("last_modified"):
                headers["If-Modified-Since"] = entry["last_modified"]
            try:
                status, response_headers, body = self.opener(url, headers, self.timeout)
            except Exception:
                if entry:  # keep serving last good copy; retry after the next TTL
                    entry["at"] = now
                    entry["stale"] = True
                raise
            lower = {str(k).lower(): v for k, v in (response_headers or {}).items()}
            if status == 304 and entry:
                entry["at"] = now
                return entry["data"]
            if status != 200:
                raise OSError(f"HTTP {status} from {url}")
            text = body.decode("utf-8", errors="replace")
            data = text if self.as_text else json.loads(text)
            self._cache[url] = {"data": data, "at": now, "etag": lower.get("etag"),
                                "last_modified": lower.get("last-modified")}
            return data
