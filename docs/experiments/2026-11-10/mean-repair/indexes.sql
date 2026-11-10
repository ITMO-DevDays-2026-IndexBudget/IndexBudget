-- IndexBudget recommendations; not executed automatically.
-- run_id: cdbe66b0-714f-407f-9935-5f2877eeabf5
-- input_hash: a08483b20a2cc943df4b85fb8f210886f0f7bf54bedabd7cf84d6bc5b8ba3577
-- selected_objective: mean

CREATE INDEX "ib_d2e9c41347f8a774adc74bf3ab4476011ac9fdab397101eb77db9c50f8d3" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST);
