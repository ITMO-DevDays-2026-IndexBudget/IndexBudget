#!/usr/bin/env bash
# Full #67 acceptance route on an owned disposable PostgreSQL/HypoPG, never the shared stand.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
container=''
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker build --tag indexbudget-import-test:local --file demo/postgres/Dockerfile .
container=$(docker run --rm --detach --publish 127.0.0.1::5432 \
    --env POSTGRES_HOST_AUTH_METHOD=trust --env POSTGRES_DB=stats_test \
    indexbudget-import-test:local -c shared_preload_libraries=pg_stat_statements -c compute_query_id=on)
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d stats_test >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
[[ "$ready" == true ]] || { echo 'Disposable importer database did not become ready.' >&2; exit 1; }
address=$(docker port "$container" 5432/tcp)
port=${address##*:}
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Cannot determine test database port.' >&2; exit 1; }
export INDEXBUDGET_STATS_TEST_URL="jdbc:postgresql://127.0.0.1:$port/stats_test"
export INDEXBUDGET_IMPORT_HYPOPG_TEST=true
./gradlew --no-daemon test --tests 'ru.itmo.indexbudget.statistics.*'
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = ET.parse(Path('build/test-results/test/TEST-ru.itmo.indexbudget.statistics.ObservedImportIntegrationTest.xml')).getroot()
if int(root.attrib['tests']) != 2 or any(int(root.attrib[name]) != 0 for name in ('skipped', 'failures', 'errors')):
    raise SystemExit('Both actual importer scenarios must execute without skips or failures.')
print('Snapshots -> import -> analyze -> HTML/JSON/SQL executed on disposable PostgreSQL/HypoPG.')
PY
