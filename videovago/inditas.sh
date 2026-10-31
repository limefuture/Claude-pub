#!/bin/sh
# Videóvágó indítása (macOS / Linux). Használat: ./inditas.sh [videó]
cd "$(dirname "$0")" || exit 1
exec python3 videovago.py "$@"
