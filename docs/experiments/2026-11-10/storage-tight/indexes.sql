-- IndexBudget recommendations; not executed automatically.
-- run_id: 231ec5bd-5d9e-447e-8718-a5bad6aa71ff
-- input_hash: e30ce9b5a7d4fe7113d077e6ec087bf787ec952581becbda096fad52b58de599
-- selected_objective: robust

CREATE INDEX "ib_5d770c036759e8cb1b45d974e372dea64ca5606fe78c1eacaeb76fc54080" ON "public"."groups" USING btree ("category" "pg_catalog"."int4_ops" ASC NULLS LAST);
