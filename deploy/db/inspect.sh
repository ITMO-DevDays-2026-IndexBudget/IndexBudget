#!/usr/bin/env bash
# Read-only preflight, no credentials printed.
set -eu
uname -a
id
docker version
docker compose version
docker ps --format 'table {{.Names}}\t{{.Ports}}'
df -h "$HOME"
free -m
ss -ltn
pgrep -x cron || pgrep -x crond
printf 'IndexBudget cron entries: '
crontab -l 2>/dev/null | grep -c '# indexbudget-' || true
