\set ON_ERROR_STOP on
\getenv analysis_password INDEXBUDGET_SCENARIOS_PASSWORD
-- Explicit administrator-only preparation in an OWN disposable seed-v1 database.
-- Never invoked by analyze or by the shared-stand migrations.
BEGIN;
CREATE ROLE indexbudget_scenarios LOGIN PASSWORD :'analysis_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT indexbudget_reader TO indexbudget_scenarios;
GRANT UPDATE (payload), DELETE ON public.records TO indexbudget_scenarios;
ALTER ROLE indexbudget_scenarios SET default_transaction_read_only = on;
COMMIT;
