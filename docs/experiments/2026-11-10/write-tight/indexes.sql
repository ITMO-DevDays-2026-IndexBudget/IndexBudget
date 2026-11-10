-- IndexBudget recommendations; not executed automatically.
-- run_id: e563b39e-5f37-4116-a6b4-f1dcfd67dff4
-- input_hash: 46396d324a1f8177d989c0744b2e124afff7a411e409461eea930c4eff7a9e07
-- selected_objective: robust

CREATE INDEX "ib_5d770c036759e8cb1b45d974e372dea64ca5606fe78c1eacaeb76fc54080" ON "public"."groups" USING btree ("category" "pg_catalog"."int4_ops" ASC NULLS LAST);
