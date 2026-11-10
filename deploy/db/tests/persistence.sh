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
# Add a real migration after the initial deployment, independent of how many
# production migrations exist. Re-execution would increment applications twice.
migration=$(python3 - "$work/repository/deploy/db/migrations" <<'PYTHON'
from pathlib import Path
import sys
folder = Path(sys.argv[1])
number = max((int(p.name[:3]) for p in folder.glob('*.sql')), default=0) + 1
assert number <= 999, 'No migration number available for CI fixture'
print(folder / f'{number:03d}_ci_persistence_probe.sql')
PYTHON
)
cat > "$migration" <<'SQL'
ALTER TABLE demo_meta.persistence_probe ADD COLUMN IF NOT EXISTS applications integer NOT NULL DEFAULT 0;
UPDATE demo_meta.persistence_probe SET applications = applications + 1;
SQL
commit database
bash "$source_dir/deploy/db/update.sh"
[[ $(compose ps -q postgres) != "$before" ]]
[[ $(compose exec -T postgres psql -X -U demo_admin -d indexbudget -Atqc 'SELECT value FROM demo_meta.persistence_probe') == keep-me ]]
assert_migrations() {
    python3 - "$work/repository/deploy/db/migrations" > "$work/expected-migrations" <<'PYTHON'
import hashlib
from pathlib import Path
import sys
for path in sorted(Path(sys.argv[1]).glob('*.sql')):
    print(path.name + '|' + hashlib.sha256(path.read_bytes()).hexdigest())
PYTHON
    compose exec -T postgres psql -X -U demo_admin -d indexbudget -Atqc \
        'SELECT name, checksum FROM demo_meta.schema_migrations ORDER BY name' > "$work/actual-migrations"
    diff -u "$work/expected-migrations" "$work/actual-migrations"
    [[ $(compose exec -T postgres psql -X -U demo_admin -d indexbudget -Atqc \
        'SELECT value, applications FROM demo_meta.persistence_probe') == 'keep-me|1' ]]
}
assert_migrations
bash "$source_dir/deploy/db/update.sh"
assert_migrations
# Exercise the migration runner too: an updater no-op alone cannot prove that
# already recorded SQL is skipped when a later release runs migrations again.
DB_RELEASE=$(cat "$work/state/current/RELEASE") bash "$source_dir/deploy/db/migrate.sh" \
    "$work/state" "$work/state/current" "$work/repository/deploy/db/migrations"
assert_migrations
dump=$(find "$work/state/backups" -name '*.dump' -print -quit)
test -s "$dump"
compose exec -T postgres pg_restore --list < "$dump" > "$work/backup-contents.txt"
grep -q persistence_probe "$work/backup-contents.txt"
# A -> B (container replaced, post-up validation fails) -> configuration A.
revision_a=$(git -C "$work/repository" rev-parse HEAD)
release_a=$(cat "$work/state/current/RELEASE")
printf '\nSELECT 1 / 0;\n' >> "$work/repository/deploy/db/stand.sql"
commit broken-post-up-check
if bash "$source_dir/deploy/db/update.sh"; then
    echo 'Broken post-up check unexpectedly succeeded.' >&2; exit 1
fi
[[ $(cat "$work/state/current/RELEASE") == "$release_a" ]]
release_b=$(cat "$work/state/pending-release")
[[ "$release_b" != "$release_a" ]]
container_b=$(compose ps -q postgres)
[[ $(docker inspect -f '{{.Config.Image}}' "$container_b") == "indexbudget-stand-postgres:$release_b" ]]
git -C "$work/repository" checkout "$revision_a" -- deploy/db/stand.sql
commit revert-to-a
if bash "$source_dir/deploy/db/update.sh"; then
    echo 'Revert to A silently accepted container B.' >&2; exit 1
fi
grep -q 'Unfinished database deployment targets another release; manual recovery required.' "$work/state/update.log"
[[ $(compose ps -q postgres) == "$container_b" ]]
[[ $(cat "$work/state/current/RELEASE") == "$release_a" ]]
[[ $(cat "$work/state/pending-release") == "$release_b" ]]
assert_migrations
echo 'Persistence, migrations, backup and failed-release recovery checks passed.'
