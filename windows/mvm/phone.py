"""«Доступ для телефона»: LAN URLs + token + QR (SVG) for the phone browser / Android server mode."""
from __future__ import annotations

import ipaddress
import io

_CGNAT = ipaddress.IPv4Network("100.64.0.0/10")


def lan_ips(ips) -> list:
    out = []
    for text in ips or []:
        try:
            ip = ipaddress.IPv4Address(str(text))
        except ValueError:
            continue
        if ip.is_private and not ip.is_loopback and not ip.is_link_local and ip not in _CGNAT:
            out.append(str(ip))
    return list(dict.fromkeys(out))


def phone_access_info(port: int, token: str, ips) -> dict:
    urls = [f"http://{ip}:{port}/" for ip in lan_ips(ips)]
    pair = f"{urls[0]}#token={token}" if urls and token else None
    return {"urls": urls, "token": token, "pair_url": pair}


def qr_svg(text: str):
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        return None
    image = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage, box_size=8, border=2)
    buf = io.BytesIO()
    image.save(buf)
    return buf.getvalue().decode("utf-8")
