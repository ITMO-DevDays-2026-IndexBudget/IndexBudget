import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepared_dataset import prepare_clone, STATISTICS, DISABLE_AUTOVACUUM, SEED_DATABASE, WORK_DATABASE


class Database:
    def __init__(self):
        self.marker = ''
        self.calls = []
        self.conditions = {'tables': [{'rows': 200000, 'all_visible': 100}], 'statistics': []}
        self.fail = None
        self.drift = False

    def __call__(self, sql, database='frontend_demo'):
        self.calls.append((database, sql))
        if self.fail and self.fail in sql:
            raise RuntimeError('Preparation failed')
        if sql.startswith('DROP DATABASE IF EXISTS ' + SEED_DATABASE):
            self.marker = ''
        if 'server_version_num' in sql:
            return '160015:1.4.2'
        if 'shobj_description' in sql:
            return self.marker
        if 'COMMENT ON DATABASE' in sql:
            self.marker = sql.split(" IS '", 1)[1].rsplit("';", 1)[0]
        if sql == STATISTICS:
            return json.dumps({} if self.drift and database == WORK_DATABASE else self.conditions)
        return ''


def request():
    return dict(template_id='records', template_version=1, analysis_role='frontend_reader',
                schema_sql='CREATE TABLE public.records (id int);', seed_sql='INSERT INTO public.records VALUES(1);')


class PreparedDatasetTests(unittest.TestCase):
    def test_first_prepare_freezes_source_then_clones_with_matching_statistics(self):
        db = Database(); roles = Mock()
        result = prepare_clone(db, request(), roles)
        commands = [sql for _, sql in db.calls]
        self.assertLess(commands.index(DISABLE_AUTOVACUUM), commands.index(request()['seed_sql']))
        freeze = commands.index('VACUUM (FREEZE, ANALYZE);')
        marker = next(i for i, sql in enumerate(commands) if 'COMMENT ON DATABASE' in sql)
        clone = commands.index(f'CREATE DATABASE {WORK_DATABASE} TEMPLATE {SEED_DATABASE};')
        self.assertLess(freeze, marker); self.assertLess(marker, clone)
        self.assertIn(f'ALTER DATABASE {SEED_DATABASE} ALLOW_CONNECTIONS false;', commands[marker])
        self.assertEqual(roles.call_args.args, (SEED_DATABASE,))
        self.assertEqual(len(result['statistics_hash']), 64)

    def test_identical_requests_and_changed_workloads_reuse_source_but_always_clone(self):
        db = Database(); roles = Mock()
        first = prepare_clone(db, request(), roles)
        db.calls.clear()
        second = prepare_clone(db, request() | {'workload': {'objective': 'mean'}}, roles)
        self.assertEqual(first, second)
        self.assertEqual(roles.call_count, 1)
        self.assertFalse(any('VACUUM' in sql or 'CREATE EXTENSION' in sql for _, sql in db.calls))
        self.assertTrue(any(sql.startswith('CREATE DATABASE frontend_demo TEMPLATE') for _, sql in db.calls))

    def test_changed_dataset_role_or_version_rebuilds_only_the_single_source(self):
        for field, value in [('template_id', 'other'), ('template_version', 2), ('schema_sql', 'new schema'),
                             ('seed_sql', 'new seed'), ('analysis_role', 'frontend_scenarios')]:
            with self.subTest(field=field):
                db = Database(); roles = Mock()
                old = prepare_clone(db, request(), roles)
                new = prepare_clone(db, request() | {field: value}, roles)
                self.assertNotEqual(old['preparation_key'], new['preparation_key'])
                self.assertEqual(roles.call_count, 2)

    def test_interrupted_preparation_never_publishes_reusable_marker(self):
        db = Database(); db.fail = 'VACUUM'
        with self.assertRaises(RuntimeError): prepare_clone(db, request(), Mock())
        self.assertEqual(db.marker, '')
        db.fail = None
        prepare_clone(db, request(), Mock())
        self.assertEqual(sum(sql.startswith('CREATE DATABASE indexbudget_frontend_seed') or
                             '\nCREATE DATABASE indexbudget_frontend_seed' in sql for _, sql in db.calls), 2)

    def test_clone_drift_fails_before_analysis_and_does_not_touch_foreign_databases(self):
        db = Database(); db.drift = True
        with self.assertRaisesRegex(RuntimeError, 'клона отличается'): prepare_clone(db, request(), Mock())
        for database, sql in db.calls:
            self.assertIn(database, {'postgres', SEED_DATABASE, WORK_DATABASE})
            if sql.startswith('DROP DATABASE'):
                self.assertTrue(sql.startswith('DROP DATABASE IF EXISTS ' + SEED_DATABASE) or
                                sql.startswith('DROP DATABASE IF EXISTS ' + WORK_DATABASE))
