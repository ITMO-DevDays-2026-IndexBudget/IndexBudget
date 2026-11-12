#!/usr/bin/env bash
# Full #67/#68 acceptance route on an owned disposable PostgreSQL/HypoPG, never the shared stand.
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
./gradlew --no-daemon test --rerun-tasks --tests 'ru.itmo.indexbudget.statistics.*' \
    --tests '*ReportImportIntegrationTest' --tests '*ReportSummaryTest'
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
# Required scenario names, not brittle counts: adding a test must not break this gate.
for suite, required in (
    ('statistics.ObservedImportIntegrationTest', (
        'actual snapshots import through CLI with closed resources and replayable provenance',
        'no eligible examples publishes a failed manifest without a successful workload',
        'an observed table write window yields one write profile and refuses silent fallbacks',
        'actual workload snapshots import analyze and HTML JSON SQL carry the same provenance',
    )),
    ('report.ReportImportIntegrationTest', (
        'real imported windows and recommendation survive shared model and both renderers without new evaluations',
    )),
):
    root = ET.parse(Path(f'build/test-results/test/TEST-ru.itmo.indexbudget.{suite}.xml')).getroot()
    if any(int(root.attrib[name]) != 0 for name in ('skipped', 'failures', 'errors')):
        raise SystemExit(f'{suite}: all actual importer/report scenarios must execute without skips or failures.')
    # Kotlin backtick test methods appear with a trailing () in the JUnit XML.
    executed = {case.attrib['name'].removesuffix('()') for case in root.iter('testcase')}
    missing = [name for name in required if name not in executed]
    if missing:
        raise SystemExit(f'{suite}: mandatory scenarios did not execute: {missing}')
print('Snapshots -> import -> analyze -> summary/HTML/JSON/SQL verified against the real import manifest.')
print('Evidence: build/report-import-verification/')
PY
