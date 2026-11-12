#!/usr/bin/env bash
# Exact seed v1 in an OWN disposable container. Never uses .env or the shared stand.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
test_filter='ru.itmo.indexbudget.examples.DemoScenario*'
case "${1:-}" in
    '') [[ $# == 0 ]] || exit 2 ;;
    --observed-budgets)
        [[ $# == 1 ]] || exit 2
        test_filter='ru.itmo.indexbudget.experiments.ObservedBudgetIntegrationTest'
        ;;
    *) echo 'Usage: bash demo/check-scenarios.sh [--observed-budgets]' >&2; exit 2 ;;
esac
container=''
image="indexbudget-scenarios-test-$$"
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
    docker image rm "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
reader_password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
export INDEXBUDGET_SCENARIOS_PASSWORD
INDEXBUDGET_SCENARIOS_PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
docker build --tag "$image" demo/postgres
container=$(docker run --rm --detach --publish 127.0.0.1::5432 \
    --env POSTGRES_HOST_AUTH_METHOD=trust --env POSTGRES_DB=indexbudget --env POSTGRES_USER=demo_admin \
    --env INDEXBUDGET_READER_PASSWORD="$reader_password" \
    --env INDEXBUDGET_SCENARIOS_PASSWORD "$image")
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U demo_admin -d indexbudget >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
[[ "$ready" == true ]] || { echo 'Disposable scenario DB did not become ready.' >&2; exit 1; }
docker exec -i "$container" psql -X -U demo_admin -d indexbudget < demo/postgres/init/001-seed.sql
docker exec -i "$container" psql -X -U demo_admin -d indexbudget < demo/scenarios/prepare-role.sql
address=$(docker port "$container" 5432/tcp)
port=${address##*:}
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Cannot determine test database port.' >&2; exit 1; }
export INDEXBUDGET_SCENARIOS_TEST_URL="jdbc:postgresql://127.0.0.1:$port/indexbudget"
./gradlew --no-daemon test --rerun-tasks --tests "$test_filter"
