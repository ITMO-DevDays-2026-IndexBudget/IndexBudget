-- IndexBudget recommendations; not executed automatically.
-- run_id: eb8d9327-d296-407f-b38b-ed34e70f7d08
-- input_hash: 288492fa932bafdce039d82a361c096710121a42b82c148ffdf216c8d9caefdd
-- selected_objective: robust

CREATE INDEX "ib_d2e9c41347f8a774adc74bf3ab4476011ac9fdab397101eb77db9c50f8d3" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST);
