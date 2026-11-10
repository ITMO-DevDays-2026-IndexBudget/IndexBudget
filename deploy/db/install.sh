#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $(uname -s) == Linux ]] || { echo 'Run on the Linux/WSL stand.' >&2; exit 1; }
for tool in git python3 docker flock timeout crontab sha256sum; do command -v "$tool" >/dev/null; done
docker info >/dev/null
docker compose version
pgrep -x cron >/dev/null || pgrep -x crond >/dev/null || { echo 'cron must already be running.' >&2; exit 1; }
root="$HOME/indexbudget-db"
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$root"
chmod 700 "$root"
exec 8>"$root/install.lock"
flock -n 8 || { echo 'Another installation is running.' >&2; exit 1; }
python3 - "$root/db.env" <<'PY'
import os, pathlib, secrets, sys
path = pathlib.Path(sys.argv[1])
if not path.exists():
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write('INDEXBUDGET_ADMIN_PASSWORD=' + secrets.token_hex(24) + '\n')
        out.write('INDEXBUDGET_READER_PASSWORD=' + secrets.token_hex(24) + '\n')
PY
install -m 700 "$source_dir/update.sh" "$root/update.sh.next"
mv -f "$root/update.sh.next" "$root/update.sh"
timeout --kill-after=30s 20m bash "$root/update.sh"
test -f "$root/current/RELEASE"
cron_file=$(mktemp "$root/.crontab.XXXXXX")
trap 'rm -f "$cron_file" "$cron_file.error"' EXIT
if ! LC_ALL=C crontab -l >"$cron_file" 2>"$cron_file.error"; then
    grep -q 'no crontab for' "$cron_file.error" || { cat "$cron_file.error" >&2; exit 1; }
fi
python3 - "$cron_file" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
marker = '# indexbudget-database-update'
lines = [line for line in path.read_text().splitlines() if not line.endswith(marker)]
lines.append('*/5 * * * * /usr/bin/timeout --kill-after=30s 20m /bin/bash '
             '"$HOME/indexbudget-db/update.sh" >/dev/null 2>&1 ' + marker)
path.write_text('\n'.join(lines) + '\n')
PY
crontab "$cron_file"
echo 'Database installed; polling main every five minutes. Credentials stay in db.env.'
