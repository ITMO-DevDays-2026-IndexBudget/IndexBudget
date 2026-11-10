#!/usr/bin/env bash
# Only a disposable Linux CI runner: resources use the stand's project/volume names.
set -Eeuo pipefail
[[ ${CI:-} == true ]] || { echo 'Disposable CI only.' >&2; exit 1; }
source_dir=$(cd "$(dirname "$0")/../../.." && pwd)
work=$(mktemp -d)
export INDEXBUDGET_DB_ROOT="$work/state"
export INDEXBUDGET_REPOSITORY="$work/repository"
mkdir -p "$work/repository/demo" "$work/repository/deploy" "$work/state"
cp -R "$source_dir/demo/postgres" "$work/repository/demo/"
cp -R "$source_dir/deploy/db" "$work/repository/deploy/"
python3 - "$work/state/db.env" <<'PY'
import os, secrets, sys
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as out:
    out.write('INDEXBUDGET_ADMIN_PASSWORD=' + secrets.token_hex(24) + '\n')
    out.write('INDEXBUDGET_READER_PASSWORD=' + secrets.token_hex(24) + '\n')
PY
git -C "$work/repository" init -b main --quiet
commit() {
    git -C "$work/repository" add .
    git -C "$work/repository" -c user.name=CI -c user.email=ci@example.invalid commit -qm "$1"
}
commit initial
trap 'cat "$work/state/update.log"' ERR
bash "$source_dir/deploy/db/update.sh"
compose() {
    DB_RELEASE=$(cat "$work/state/current/RELEASE") docker compose --project-name indexbudget-stand \
        --env-file "$work/state/db.env" -f "$work/state/current/compose.yaml" "$@"
}
compose exec -T postgres psql -X -U demo_admin -d indexbudget -v ON_ERROR_STOP=1 \
    -c "CREATE TABLE demo_meta.persistence_probe (value text); INSERT INTO demo_meta.persistence_probe VALUES ('keep-me');"
before=$(compose ps -q postgres)
printf 'application-only change\n' > "$work/repository/README.md"
commit application
bash "$source_dir/deploy/db/update.sh"
[[ $(compose ps -q postgres) == "$before" ]]
printf 'new db configuration revision\n' > "$work/repository/deploy/db/probe.txt"
commit database
bash "$source_dir/deploy/db/update.sh"
[[ $(compose ps -q postgres) != "$before" ]]
[[ $(compose exec -T postgres psql -X -U demo_admin -d indexbudget -Atqc 'SELECT value FROM demo_meta.persistence_probe') == keep-me ]]
[[ $(compose exec -T postgres psql -X -U demo_admin -d indexbudget -Atqc 'SELECT count(*) FROM demo_meta.schema_migrations') == 1 ]]
dump=$(find "$work/state/backups" -name '*.dump' -print -quit)
test -s "$dump"
compose exec -T postgres pg_restore --list < "$dump" > "$work/backup-contents.txt"
grep -q persistence_probe "$work/backup-contents.txt"
echo 'Persistence, application-only no-op, migrations and backup checks passed.'
