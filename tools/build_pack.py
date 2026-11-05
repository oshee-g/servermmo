#!/usr/bin/env python3
"""Собирает servermmo-paper-26.3-server-pack.zip для раздачи.

Кладёт в архив папку server/ (скрипты, плагины, ресурспак), README.md,
PLUGINS.md и превью HUD. .bat/.ps1 переводятся в CRLF.

Запуск: python3 tools/build_pack.py [выходной_файл]
"""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT.parent / "servermmo-paper-26.3-server-pack.zip"
CRLF_SUFFIXES = {".bat", ".ps1", ".cmd"}


def add(zf: zipfile.ZipFile, src: Path, arc: str) -> None:
    data = src.read_bytes()
    if src.suffix.lower() in CRLF_SUFFIXES:
        data = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    info = zipfile.ZipInfo(arc, date_time=(2026, 11, 5, 12, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data)


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()

    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted((ROOT / "server").rglob("*")):
            if not path.is_file():
                continue
            if "backups" in path.parts or path.name == ".gitignore":
                continue
            add(zf, path, path.relative_to(ROOT).as_posix())
            count += 1
        for extra, arc in [
            (ROOT / "README.md", "README.md"),
            (ROOT / "plugins" / "README.md", "PLUGINS.md"),
            (ROOT / "resourcepack" / "hud-preview.png", "hud-preview.png"),
        ]:
            if extra.is_file():
                add(zf, extra, arc)
                count += 1

    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"файлов: {count}")
    print(f"архив:  {out} ({out.stat().st_size} байт)")
    print(f"sha256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
