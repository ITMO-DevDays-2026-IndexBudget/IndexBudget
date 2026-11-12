"""One immutable source database and one disposable clone in the frontend's own cluster."""
import hashlib
import json

SEED_DATABASE = 'indexbudget_frontend_seed'
WORK_DATABASE = 'frontend_demo'
PREPARATION_VERSION = 1

DISABLE_AUTOVACUUM = """DO $$ DECLARE t record; BEGIN
 FOR t IN SELECT n.nspname, c.relname FROM pg_catalog.pg_class c
   JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
 LOOP
   EXECUTE format('ALTER TABLE %I.%I SET (autovacuum_enabled = false)', t.nspname, t.relname);
 END LOOP;
END $$;"""

STATISTICS = """SELECT jsonb_build_object(
 'tables', (SELECT jsonb_agg(jsonb_build_object('schema', n.nspname, 'table', c.relname,
     'rows', c.reltuples, 'pages', c.relpages, 'all_visible', c.relallvisible,
     'options', c.reloptions) ORDER BY n.nspname, c.relname)
   FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'statistics', (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.schemaname, s.tablename, s.attname, s.inherited)
   FROM pg_catalog.pg_stats s WHERE s.schemaname NOT LIKE 'pg_%' AND s.schemaname <> 'information_schema')
);"""


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def prepare_clone(db, request, configure_roles):
    version = db("SELECT current_setting('server_version_num') || ':' || default_version "
                 "FROM pg_catalog.pg_available_extensions WHERE name = 'hypopg';", 'postgres')
    if not version:
        raise RuntimeError('HypoPG недоступен в локальной demo-БД.')
    key = digest({name: request.get(name) for name in
                  ('template_id', 'template_version', 'schema_sql', 'seed_sql', 'analysis_role')}
                 | {'preparation_version': PREPARATION_VERSION, 'database_version': version})
    saved = db(f"SELECT pg_catalog.shobj_description(oid, 'pg_database') FROM pg_catalog.pg_database "
               f"WHERE datname = '{SEED_DATABASE}';", 'postgres')
    try:
        marker = json.loads(saved)
    except (TypeError, ValueError):
        marker = None
    reusable = (isinstance(marker, dict) and marker.get('preparation_key') == key
                and isinstance(marker.get('statistics_hash'), str) and len(marker['statistics_hash']) == 64)

    db(f'DROP DATABASE IF EXISTS {WORK_DATABASE} WITH (FORCE);', 'postgres')
    if not reusable:
        db(f'DROP DATABASE IF EXISTS {SEED_DATABASE} WITH (FORCE);\n'
           f'CREATE DATABASE {SEED_DATABASE} TEMPLATE template0;', 'postgres')
        db('CREATE EXTENSION hypopg;\n' + request['schema_sql'], SEED_DATABASE)
        db(DISABLE_AUTOVACUUM, SEED_DATABASE)
        db(request['seed_sql'], SEED_DATABASE)
        configure_roles(SEED_DATABASE)
        # A separate psql invocation: VACUUM cannot run inside a transaction block.
        db('VACUUM (FREEZE, ANALYZE);', SEED_DATABASE)
        conditions = json.loads(db(STATISTICS, SEED_DATABASE))
        marker = {'preparation_key': key, 'statistics_hash': digest(conditions),
                  'preparation_version': PREPARATION_VERSION}
        encoded = json.dumps(marker).replace("'", "''")
        # Publish only after the source is complete; failed preparation is rebuilt on retry.
        db(f'ALTER DATABASE {SEED_DATABASE} ALLOW_CONNECTIONS false;\n'
           f"COMMENT ON DATABASE {SEED_DATABASE} IS '{encoded}';", 'postgres')
    db(f'CREATE DATABASE {WORK_DATABASE} TEMPLATE {SEED_DATABASE};', 'postgres')
    # Database-level ACLs are not inherited from the template database.
    db(f'REVOKE TEMPORARY ON DATABASE {WORK_DATABASE} FROM PUBLIC;', WORK_DATABASE)
    actual = digest(json.loads(db(STATISTICS, WORK_DATABASE)))
    if actual != marker['statistics_hash']:
        raise RuntimeError('Статистика или visibility map клона отличается от эталона. Анализ не запущен.')
    return marker
