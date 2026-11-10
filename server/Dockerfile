# Marstek Venus monitor — read-only server. Python stdlib only, no pip install.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MARSTEK_BIND=0.0.0.0 \
    MARSTEK_DASHBOARD_PORT=8767 \
    MARSTEK_HISTORY_DB=/data/history.db \
    MARSTEK_CONFIG=/config/.env

RUN groupadd --system --gid 10001 marstek \
 && useradd --system --uid 10001 --gid marstek --home-dir /app --shell /usr/sbin/nologin marstek \
 && mkdir -p /data /config && chown marstek:marstek /data

WORKDIR /app
COPY --chown=root:root *.py index.html app.js style.css sw.js favicon.svg manifest.webmanifest config.example.env ./
COPY --chown=root:root icons ./icons

USER marstek
VOLUME ["/data"]
EXPOSE 8767

HEALTHCHECK --interval=60s --timeout=5s --start-period=40s --retries=3 \
  CMD python3 -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('MARSTEK_DASHBOARD_PORT','8767'), timeout=4)" || exit 1

CMD ["python3", "-B", "server.py"]
