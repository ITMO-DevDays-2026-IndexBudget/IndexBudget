#!/usr/bin/env bash
# Own disposable PostgreSQL + HypoPG only; never reads the shared stand's .env or credentials.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
container=''
image="indexbudget-hypopg-test-$$"
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
    docker image rm "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker build --tag "$image" demo/postgres
container=$(docker run --rm --detach --publish 127.0.0.1::5432 \
    --env POSTGRES_HOST_AUTH_METHOD=trust --env POSTGRES_DB=hypopg_test "$image")
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d hypopg_test >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs "$container"
    echo 'Disposable HypoPG database did not become ready.' >&2
    exit 1
fi
address=$(docker port "$container" 5432/tcp)
port=${address##*:}
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Cannot determine test database port.' >&2; exit 1; }
export INDEXBUDGET_HYPOPG_TEST_URL="jdbc:postgresql://127.0.0.1:$port/hypopg_test"
export INDEXBUDGET_HYPOPG_TEST_USER=postgres
export INDEXBUDGET_HYPOPG_TEST_PASSWORD=''
./gradlew --no-daemon test --rerun-tasks \
    --tests 'ru.itmo.indexbudget.postgres.JdbcAnalysisSessionIntegrationTest' \
    --tests 'ru.itmo.indexbudget.postgres.BrinHypopgIntegrationTest' \
    --tests 'ru.itmo.indexbudget.application.CandidateRefillHypopgIntegrationTest' \
    --tests 'ru.itmo.indexbudget.application.BudgetSeriesHypopgIntegrationTest' \
    --tests 'ru.itmo.indexbudget.application.CandidateRankingHypopgIntegrationTest' \
    --tests 'ru.itmo.indexbudget.application.SearchComparisonHypopgIntegrationTest' \
    --tests 'ru.itmo.indexbudget.comparison.ManualComparisonIntegrationTest'
# Also save the real-run comparison artifact of #104 into a throwaway directory; the committed
# copy under docs/experiments is regenerated the same way on a disposable database.
./gradlew --no-daemon searchComparisonRealExperiments --args="--output-dir build/search-comparison-real"
