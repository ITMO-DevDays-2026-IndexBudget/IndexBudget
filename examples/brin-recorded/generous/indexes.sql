-- IndexBudget recommendations; not executed automatically.
-- run_id: 3b53d765-4e0a-4265-8a0a-4bbe82c0138a
-- input_hash: ecabc65c03de2587a043a6dad4f032789748a82a27fd726abc4504770f70c668
-- index_methods: btree,brin
-- selected_objective: robust

CREATE INDEX "ib_ebb9838d0774a31eb6a097b81e483368d8f0c4e5411150b11de76e703966" ON "brin_demo"."events" USING btree ("sequence" "pg_catalog"."int8_ops" ASC NULLS LAST);
