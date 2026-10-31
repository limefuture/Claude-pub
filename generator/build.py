#!/usr/bin/env python3
"""Builds generator/index.html: inlines the logos and the sample photo from moodboard/
into the template, so the generator is a single file that also works offline."""
import base64
import pathlib
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "generator" / "src" / "flyer.template.html"
OUT = ROOT / "generator" / "index.html"
BODY_ONLY = ROOT / "generator" / "dist" / "flyer.body.html"

SKELETON = (
    '<!doctype html><html><head><meta charset=utf8>'
    '<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover">'
    '<style>body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>'
    '</head><body>\n'
)


def svg_uri(path):
    return "data:image/svg+xml;charset=utf-8," + urllib.parse.quote(path.read_text(encoding="utf-8"), safe="")


def bin_uri(path, mime):
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main():
    html = SRC.read_text(encoding="utf-8")
    logo = ROOT / "moodboard" / "logo"
    for key, value in {
        "__WORDMARK_RED__": svg_uri(logo / "VANDALISM_logo_piros.svg"),
        "__WORDMARK__": svg_uri(logo / "VANDALISM_logo_feher.svg"),
        "__MONOGRAM__": svg_uri(logo / "V-monogram_feher.svg"),
        "__SAMPLE__": bin_uri(ROOT / "generator" / "src" / "sample_tunnel.webp", "image/webp"),
    }.items():
        html = html.replace(key, value)
    BODY_ONLY.parent.mkdir(exist_ok=True)
    BODY_ONLY.write_text(html, encoding="utf-8")
    OUT.write_text(SKELETON + html + "\n</body></html>\n", encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
