#!/usr/bin/env bash
# Own disposable PostgreSQL only. Never uses the demo/stand database or its passwords.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
container=''
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
container=$(docker run --rm --detach --publish 127.0.0.1::5432 \
    --env POSTGRES_HOST_AUTH_METHOD=trust --env POSTGRES_DB=catalog_test postgres:16.15-bookworm)
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d catalog_test >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs "$container"
    echo 'Disposable catalog database did not become ready.' >&2
    exit 1
fi
address=$(docker port "$container" 5432/tcp)
port=${address##*:}
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Cannot determine test database port.' >&2; exit 1; }
export INDEXBUDGET_CATALOG_TEST_URL="jdbc:postgresql://127.0.0.1:$port/catalog_test"
export INDEXBUDGET_CATALOG_TEST_USER=postgres
export INDEXBUDGET_CATALOG_TEST_PASSWORD=''
./gradlew --no-daemon test --rerun-tasks --tests 'ru.itmo.indexbudget.postgres.JdbcCatalogReader*'
