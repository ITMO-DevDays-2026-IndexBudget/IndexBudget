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
    --env POSTGRES_HOST_AUTH_METHOD=trust --env POSTGRES_DB=table_stats_test postgres:16.15-bookworm)
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d table_stats_test >/dev/null 2>&1; then
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
export INDEXBUDGET_TABLE_STATS_TEST_URL="jdbc:postgresql://127.0.0.1:$port/table_stats_test"
./gradlew --no-daemon test --rerun-tasks --tests 'ru.itmo.indexbudget.statistics.Table*' --tests 'ru.itmo.indexbudget.statistics.StatisticsSnapshotTest'
python3 - <<'CHECK'
import xml.etree.ElementTree as ET
r = ET.parse('build/test-results/test/TEST-ru.itmo.indexbudget.statistics.TableStatisticsIntegrationTest.xml').getroot()
if int(r.attrib['tests']) == 0 or any(int(r.attrib[k]) for k in ('skipped', 'failures', 'errors')):
    raise SystemExit('Table statistics integration suite must execute without skips or failures.')
CHECK
