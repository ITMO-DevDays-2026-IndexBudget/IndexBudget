#!/usr/bin/env bash
# Установить frontend с обновлением вслед за проверенным CLI-релизом. Без sudo.
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
options=()
if [[ -n "${FRONTEND_PORT:-}" ]]; then options+=(--port "$FRONTEND_PORT"); fi
if [[ -n "${FRONTEND_DB_PORT:-}" ]]; then options+=(--db-port "$FRONTEND_DB_PORT"); fi
exec python3 "$repo_dir/deploy/frontend/install.py" "${options[@]}" "$@"
