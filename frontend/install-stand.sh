#!/usr/bin/env bash
# Выполнять на Linux-стенде из переданной копии frontend. Без sudo.
set -euo pipefail
frontend_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
frontend_cli=${FRONTEND_CLI:-"$HOME/.local/bin/indexbudget"}
frontend_port=${FRONTEND_PORT:-3000}
frontend_db_port=${FRONTEND_DB_PORT:-55433}
frontend_db_image=${FRONTEND_DB_IMAGE:-indexbudget-frontend-db:local}
[[ "$frontend_port" =~ ^[0-9]+$ && "$frontend_db_port" =~ ^[0-9]+$ ]]
test -x "$frontend_cli"
python3 -m py_compile "$frontend_dir/server.py"
systemctl --user show-environment >/dev/null
export FRONTEND_DB_PORT="$frontend_db_port" FRONTEND_DB_IMAGE="$frontend_db_image"
if docker image inspect "$frontend_db_image" --format '{{.Id}}' >/dev/null 2>&1; then
  docker compose -f "$frontend_dir/compose.yaml" up -d --no-build --wait
else
  docker compose -f "$frontend_dir/compose.yaml" up -d --build --wait
fi
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/indexbudget-frontend.service" <<EOF
[Unit]
Description=IndexBudget demonstration frontend
After=network.target

[Service]
Type=simple
WorkingDirectory=$frontend_dir
Environment="FRONTEND_CLI=$frontend_cli"
Environment="FRONTEND_DB_PORT=$frontend_db_port"
Environment="FRONTEND_DB_IMAGE=$frontend_db_image"
ExecStart=/usr/bin/python3 -u $frontend_dir/server.py --host 0.0.0.0 --port $frontend_port
Restart=on-failure
RestartSec=3
TimeoutStopSec=420
KillMode=mixed

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable indexbudget-frontend.service
systemctl --user restart indexbudget-frontend.service
for attempt in {1..20}; do
  if curl --fail --silent "http://127.0.0.1:$frontend_port/api/health"; then
    printf '\nFrontend listening on port %s\n' "$frontend_port"
    exit 0
  fi
  sleep 1
done
systemctl --user status indexbudget-frontend.service --no-pager
exit 1
