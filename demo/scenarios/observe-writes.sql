-- #109: fixed observation burst, ONLY in check-scenarios.sh's disposable seed-v1 DB.
-- SELECT/UPDATE/DELETE from workload.json are executed once by the experiment runner.
-- These additional writes make INSERT and the second table's rates observable too.
INSERT INTO public.records
SELECT i, (i % 1000)::integer, ((i * 37) % 997)::integer,
       timestamp '2026-01-01 00:00:00' + i * interval '1 second', repeat(md5(i::text), 4)
FROM generate_series(200001, 200010) AS g(i);
INSERT INTO public.groups VALUES (1000, 0, 'group-1000');
UPDATE public.groups SET name = 'observed-group' WHERE id IN (10, 20, 30);
DELETE FROM public.groups WHERE id = 1000;
