#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
container=''
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f -v "$container" >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker build -t indexbudget-metrics-test:local demo/postgres
export INDEXBUDGET_METRICS_TEST_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
container=$(docker run --detach --publish 127.0.0.1::5432 \
    --env POSTGRES_DB=indexbudget --env POSTGRES_USER=demo_admin \
    --env POSTGRES_PASSWORD="$INDEXBUDGET_METRICS_TEST_PASSWORD" \
    --env INDEXBUDGET_READER_PASSWORD="$INDEXBUDGET_METRICS_TEST_PASSWORD" \
    --mount "type=bind,src=$(pwd)/demo/postgres/init,dst=/docker-entrypoint-initdb.d,readonly" \
    indexbudget-metrics-test:local)
ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if docker exec "$container" sh -c 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -h 127.0.0.1 -U demo_admin -d indexbudget -Atqc "SELECT ready FROM demo_meta.seed_status WHERE ready"' 2>/dev/null | grep -qx t; then
        ready=true
        break
    fi
    sleep 2
done
[[ "$ready" == true ]] || { echo 'Disposable metrics database did not become ready.' >&2; exit 1; }
address=$(docker port "$container" 5432/tcp)
port=${address##*:}
[[ "$port" =~ ^[0-9]+$ ]]
export INDEXBUDGET_METRICS_TEST_URL="jdbc:postgresql://127.0.0.1:$port/indexbudget?connectTimeout=5&socketTimeout=5"
./gradlew --no-daemon test --rerun-tasks --tests '*RealCostsIntegrationTest'
test -s build/metrics-verification/real-costs.json
