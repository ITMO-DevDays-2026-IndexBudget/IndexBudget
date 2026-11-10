-- IndexBudget recommendations; not executed automatically.
-- run_id: 0c3f73ee-9b40-481e-adb0-9a422085f25d
-- input_hash: 5a5f00b4412d8349687495075dde8ba86657b8da29b3bd301288c7eb534ccd06
-- selected_objective: robust

CREATE INDEX "ib_d2e9c41347f8a774adc74bf3ab4476011ac9fdab397101eb77db9c50f8d3" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST);
