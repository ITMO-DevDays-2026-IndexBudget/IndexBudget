#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
root=$1
snapshot=$2
source_revision=$3
minimum_free_gib=10
[[ -s "$root/db.env" ]] || { echo 'Missing db.env; run install.sh.' >&2; exit 1; }
app_revision=$source_revision
db_tree=$(git --git-dir="$root/app.git" rev-parse "$app_revision:demo/postgres")
config_tree=$(git --git-dir="$root/app.git" rev-parse "$app_revision:deploy/db")
# Application-only changes do not rebuild or restart PostgreSQL.
release_id=$(printf '%s\n%s\n' "$config_tree" "$db_tree" | sha256sum | cut -d' ' -f1)
release="$root/releases/$release_id"
if [[ -f "$root/pending-release" ]] && [[ $(cat "$root/pending-release") != "$release_id" ]]; then
    echo 'Unfinished database deployment targets another release; manual recovery required.' >&2
    exit 1
fi
if [[ ! -f "$root/pending-release" && -f "$root/current/RELEASE" ]] && [[ $(cat "$root/current/RELEASE") == "$release_id" ]]; then
    container=$(docker ps -aq --filter label=com.docker.compose.project=indexbudget-stand --filter label=com.docker.compose.service=postgres)
    [[ -n "$container" ]] || { echo 'Current release has no database container; manual recovery required.' >&2; exit 1; }
    actual=$(docker inspect -f '{{.Config.Image}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container")
    volume=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$container")
    if [[ "$actual" != "indexbudget-stand-postgres:$release_id|running|healthy" || "$volume" != indexbudget-stand-pg16-data ]]; then
        echo 'Container does not match the healthy current release; manual recovery required.' >&2
        exit 1
    fi
    exit 0
fi
free_kib=$(df -Pk "$root" | awk 'END {print $4}')
(( free_kib >= minimum_free_gib * 1024 * 1024 )) || { echo 'Not enough free disk space; deployment skipped.' >&2; exit 1; }
mkdir -p "$release/source"
git --git-dir="$root/app.git" archive "$app_revision" demo/postgres | tar -x -C "$release/source"
# tar respects umask 077, but the bind-mounted init directory is read by the
# container's postgres UID. Only public seed sources get cross-UID read access.
find "$release/source/demo/postgres/init" -type d -exec chmod 755 {} +
find "$release/source/demo/postgres/init" -type f -exec chmod 644 {} +
cp "$snapshot/deploy/db/compose.yaml" "$release/compose.yaml"
printf '%s\n' "$app_revision" > "$release/APP_REVISION"
printf '%s\n' "$source_revision" > "$release/CONFIG_REVISION"
export DB_RELEASE="$release_id"
compose=(docker compose --project-name indexbudget-stand --env-file "$root/db.env" -f "$release/compose.yaml")
"${compose[@]}" config --quiet

container=$("${compose[@]}" ps --all --quiet postgres)
if [[ -z "$container" ]]; then
    if docker volume inspect indexbudget-stand-pg16-data >/dev/null 2>&1; then
        echo 'Existing volume without its container: manual recovery required.' >&2
        exit 1
    fi
    # Fail before touching an existing listener belonging to another service.
    python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(('127.0.0.1', 55432))
PY
else
    mounted_volume=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$container")
    [[ "$mounted_volume" == indexbudget-stand-pg16-data ]] || { echo 'Unexpected database volume; refusing deployment.' >&2; exit 1; }
fi

# Do not stop PostgreSQL on a failed image build. Build is single-threaded in the source Dockerfile.
"${compose[@]}" build postgres
if [[ -n "$container" ]]; then
    backup="$root/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
    "${compose[@]}" exec -T postgres pg_dump -U demo_admin -d indexbudget -Fc > "$backup.pending"
    test -s "$backup.pending"
    mv "$backup.pending" "$backup"
fi
docker volume create indexbudget-stand-pg16-data >/dev/null
# Persist intent before replacing the container; a failed post-up check must not
# become an apparent no-op if main is subsequently reverted to the old release.
printf '%s\n' "$release_id" > "$root/pending-release.next"
mv -f "$root/pending-release.next" "$root/pending-release"
"${compose[@]}" up --detach --wait --wait-timeout 180 postgres

bash "$snapshot/deploy/db/migrate.sh" "$root" "$release" "$snapshot/deploy/db/migrations"
"${compose[@]}" exec -T postgres sh -c 'export PGPASSWORD="$INDEXBUDGET_READER_PASSWORD"; exec psql -X -h 127.0.0.1 -U indexbudget_reader -d indexbudget -v ON_ERROR_STOP=1' < "$snapshot/deploy/db/stand.sql"
printf '%s\n' "$release_id" > "$release/RELEASE"
rm -f "$root/current.next"
ln -s "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
rm -f "$root/pending-release"
printf '%s Database release %s is healthy.\n' "$(date -Is)" "$release_id"
