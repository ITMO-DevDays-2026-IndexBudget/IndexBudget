#!/usr/bin/env bash
set -Eeuo pipefail
root=$1
release=$2
migrations=$3
compose=(docker compose --project-name indexbudget-stand --env-file "$root/db.env" -f "$release/compose.yaml")
psql=("${compose[@]}" exec -T postgres psql -X -U demo_admin -d indexbudget -v ON_ERROR_STOP=1)
"${psql[@]}" -c 'CREATE TABLE IF NOT EXISTS demo_meta.schema_migrations (name text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now());'
for migration in "$migrations"/*.sql; do
    [[ -f "$migration" ]] || continue
    name=$(basename "$migration")
    [[ "$name" =~ ^[0-9]{3}_[a-z0-9_]+\.sql$ ]] || { echo 'Invalid migration filename.' >&2; exit 1; }
    checksum=$(sha256sum "$migration" | cut -d' ' -f1)
    stored=$("${psql[@]}" -At -v migration_name="$name" <<'SQL'
SELECT checksum FROM demo_meta.schema_migrations WHERE name = :'migration_name';
SQL
    )
    if [[ -n "$stored" ]]; then
        [[ "$stored" == "$checksum" ]] || { echo "Applied migration changed: $name" >&2; exit 1; }
        continue
    fi
    {
        printf "BEGIN;\nSET LOCAL lock_timeout = '5s';\nSET LOCAL statement_timeout = '30s';\n"
        cat "$migration"
        printf "\nINSERT INTO demo_meta.schema_migrations(name, checksum) VALUES (:'migration_name', :'checksum');\nCOMMIT;\n"
    } | "${psql[@]}" -v migration_name="$name" -v checksum="$checksum"
done
