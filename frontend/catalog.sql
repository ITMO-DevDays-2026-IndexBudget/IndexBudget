SELECT json_build_object(
  'tables', COALESCE((SELECT json_agg(t ORDER BY t.name) FROM (
    SELECT n.nspname || '.' || c.relname AS name,
      c.reltuples::bigint AS rows,
      (SELECT json_agg(json_build_object(
        'name', a.attname, 'type', format_type(a.atttypid, a.atttypmod),
        'primary', EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid
          AND k.contype = 'p' AND a.attnum = ANY(k.conkey)),
        'nullable', NOT a.attnotnull) ORDER BY a.attnum)
       FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS columns,
      (SELECT COALESCE(json_agg(indexdef ORDER BY indexname), '[]'::json)
       FROM pg_indexes WHERE schemaname = n.nspname AND tablename = c.relname) AS indexes
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND n.nspname NOT LIKE 'pg_toast%'
  ) t), '[]'::json),
  'relations', COALESCE((SELECT json_agg(json_build_object(
    'from', sn.nspname || '.' || s.relname, 'to', dn.nspname || '.' || d.relname,
    'label', pg_get_constraintdef(k.oid)) ORDER BY k.conname)
    FROM pg_constraint k
    JOIN pg_class s ON s.oid = k.conrelid JOIN pg_namespace sn ON sn.oid = s.relnamespace
    JOIN pg_class d ON d.oid = k.confrelid JOIN pg_namespace dn ON dn.oid = d.relnamespace
    WHERE k.contype = 'f'), '[]'::json)
);
