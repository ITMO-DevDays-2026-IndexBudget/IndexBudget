#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose exec -T postgres sh -c 'export PGPASSWORD="$INDEXBUDGET_READER_PASSWORD"; exec psql -X -h 127.0.0.1 -U indexbudget_reader -d indexbudget -v ON_ERROR_STOP=1' < demo/checks/smoke.sql
