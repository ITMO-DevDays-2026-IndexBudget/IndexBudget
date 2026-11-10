-- IndexBudget recommendations; not executed automatically.
-- run_id: 2bc5d69e-fc44-48b6-93b7-bea0dbfcbc5a
-- input_hash: 288492fa932bafdce039d82a361c096710121a42b82c148ffdf216c8d9caefdd
-- selected_objective: robust

CREATE INDEX "ib_d2e9c41347f8a774adc74bf3ab4476011ac9fdab397101eb77db9c50f8d3" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST);
