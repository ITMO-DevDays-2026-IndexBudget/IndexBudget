-- IndexBudget recommendations; not executed automatically.
-- run_id: f0f8e453-36ab-403b-b1d9-76208c4f69ae
-- input_hash: b3164ff8931eee129feffd731dc0336141211dc32b93ec7c272306714be74eb2
-- index_methods: btree
-- selected_objective: robust

CREATE INDEX "ib_03dfa4d6f086896e7252ee7c3baf17c8b3c4e1770b4e7183c1eac7462f33" ON "public"."records" USING btree ("created_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST);
CREATE INDEX "ib_13506513486f8f4f9dba27e45cf56d3f5baaca8ae99a2588288fcfb4a9f8" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST, "value" "pg_catalog"."int4_ops" ASC NULLS LAST);
CREATE INDEX "ib_1f49313917f0cb8f6e95d7235bbe9373111322427785df823b321729583b" ON "public"."records" USING btree ("group_id" "pg_catalog"."int4_ops" ASC NULLS LAST, "created_at" "pg_catalog"."timestamp_ops" ASC NULLS LAST);
CREATE INDEX "ib_6b42a62519609891a2964994b65f43644d345af6b165e1220092a348dbb0" ON "public"."records" USING btree ("value" "pg_catalog"."int4_ops" ASC NULLS LAST);
