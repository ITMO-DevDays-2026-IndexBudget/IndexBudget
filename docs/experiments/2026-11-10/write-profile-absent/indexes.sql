-- IndexBudget recommendations; not executed automatically.
-- run_id: a56aa2f3-7946-47fd-a09f-fbacaf8e8905
-- input_hash: 581a2914c4a4d1c24939b2755732b689f2ade2c132386ec1fd4035c1d6dbf25d
-- selected_objective: robust

CREATE INDEX "ib_d2e9c41347f8a774adc74bf3ab4476011ac9fdab397101eb77db9c50f8d3" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST);
