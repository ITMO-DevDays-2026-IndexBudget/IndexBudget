-- IndexBudget recommendations; not executed automatically.
-- run_id: 27413f47-a927-4b49-8829-be679b002d6b
-- input_hash: c3395500998731bb95d32f9f07efc74de6deb1042d43e5329dc647049774bdea
-- index_methods: btree,brin
-- selected_objective: robust

CREATE INDEX "ib_39c4ca8de3f95c1cfb0083badb5f3a47bee36be585612fdd419169dda6ae" ON "brin_demo"."events" USING brin ("sequence" "pg_catalog"."int8_minmax_ops");
