import copy
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import (FrontendHTTPServer, Jobs, LocalRunner, ROOT, ShuttingDownError,
                    data_directory, make_handler, read_json, validate_request, write_json)

TEMPLATES = {t['id']: t for t in (read_json(p) for p in (ROOT / 'templates').glob('*.json'))}


def request(template_id='01-records'):
    t = TEMPLATES[template_id]
    return dict(template_id=t['id'], template_version=t['version'],
                workload=copy.deepcopy(t['workload']))


class FakeRunner:
    def __init__(self, status='completed', missing=None, gate=None):
        self.status, self.missing, self.gate = status, missing, gate
        self.started = threading.Event()
        self.prepared = []

    def prepare(self, data):
        self.prepared.append(data)
        self.started.set()
        if self.gate and not self.gate.wait(5):
            raise RuntimeError('Test gate timed out')
        return copy.deepcopy(TEMPLATES[data['template_id']]['catalog'])

    def analyze(self, workload, output):
        output.mkdir()
        write_json(output / 'report.json', dict(status=self.status, run_id='engine-id'))
        for name in ('report.html', 'indexes.sql'):
            if self.missing != name:
                (output / name).write_text('fixture')
        return {'completed': 0, 'partial': 3, 'failed': 1}[self.status], '', 'test diagnostic'


class ValidationTests(unittest.TestCase):
    def test_templates_and_zero_budgets(self):
        for id in TEMPLATES:
            validate_request(request(id), TEMPLATES)
        for value in (None, 0):
            data = request()
            data['workload']['constraints'].update(storage_budget_bytes=0, max_indexes=0,
                                                   write_budget_units_per_hour=value)
            self.assertEqual(validate_request(data, TEMPLATES)['workload']['constraints']['write_budget_units_per_hour'], value)

    def test_snapshot_does_not_accept_paths_or_credentials(self):
        data = request()
        data.update(output_dir='/tmp/unrelated', database_url='must-not-be-copied')
        result = validate_request(data, TEMPLATES)
        data['workload']['constraints']['max_indexes'] = 0
        self.assertEqual(result['workload']['constraints']['max_indexes'], 5)
        self.assertNotIn('output_dir', result)
        self.assertNotIn('database_url', result)

    def test_frequency_matrix_must_match_queries(self):
        for mutation in ('missing', 'negative', 'unused', 'zero_profile'):
            data = request()
            profiles = data['workload']['profiles']
            if mutation == 'missing':
                del profiles[0]['calls_per_hour']['records_recent']
            elif mutation == 'negative':
                profiles[0]['calls_per_hour']['records_recent'] = -1
            elif mutation == 'unused':
                for p in profiles:
                    p['calls_per_hour']['records_recent'] = 0
            else:
                profiles[0]['calls_per_hour'] = {k: 0 for k in profiles[0]['calls_per_hour']}
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_request(data, TEMPLATES)

    def test_frequency_boundaries_and_types(self):
        for value in (0, 1, 1_000_000, 1_000_000.0):
            data = request()
            data['workload']['profiles'][0]['calls_per_hour']['records_recent'] = value
            with self.subTest(value=value):
                result = validate_request(data, TEMPLATES)
                self.assertEqual(result['workload']['profiles'][0]['calls_per_hour']['records_recent'], value)
        for value in (-1, .5, 1_000_001, 10**400, True, False, '1', None, float('nan'), float('inf')):
            data = request()
            data['workload']['profiles'][0]['calls_per_hour']['records_recent'] = value
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'records_recent.*целым числом'):
                validate_request(data, TEMPLATES)

    def test_frequency_rule_does_not_restrict_write_rates_or_weights(self):
        data = request()
        data['workload']['table_write_rates']['public.records'].update(
            inserts_per_hour=.25, updates_per_hour=1_000_000.5)
        data['workload']['profiles'][0]['weight'] = .25
        self.assertEqual(validate_request(data, TEMPLATES), data)

    def test_query_and_profile_count_boundaries(self):
        for count in (0, 1, 20, 21):
            data = request()
            data['workload']['queries'] = [dict(id=f'q{i}', sql='SELECT id FROM public.records') for i in range(count)]
            for profile in data['workload']['profiles']:
                profile['calls_per_hour'] = {f'q{i}': 1 for i in range(count)}
            with self.subTest(queries=count):
                if 1 <= count <= 20:
                    validate_request(data, TEMPLATES)
                else:
                    with self.assertRaisesRegex(ValueError, 'от 1 до 20'):
                        validate_request(data, TEMPLATES)
        for count in (0, 1, 3, 4):
            data = request()
            profile = data['workload']['profiles'][0]
            data['workload']['profiles'] = [{**copy.deepcopy(profile), 'id': f'p{i}'} for i in range(count)]
            with self.subTest(profiles=count):
                if 1 <= count <= 3:
                    validate_request(data, TEMPLATES)
                else:
                    with self.assertRaisesRegex(ValueError, 'от 1 до 3'):
                        validate_request(data, TEMPLATES)

    def test_client_dataset_and_unknown_template_are_rejected(self):
        for key in ('schema_sql', 'seed_sql'):
            for value in ('DROP TABLE public.records;', '', None, TEMPLATES['01-records'][key]):
                data = request()
                data[key] = value
                with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, 'серверным шаблоном'):
                    validate_request(data, TEMPLATES)
        for key, value in (('template_id', 'missing'), ('template_id', []),
                           ('template_version', 2), ('template_version', True)):
            data = request()
            data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate_request(data, TEMPLATES)

    def test_sql_is_preserved_exactly(self):
        data = request()
        data['workload']['queries'][0]['sql'] = '\n-- пример\nSELECT id FROM public.records WHERE value = 7;  '
        self.assertEqual(validate_request(data, TEMPLATES), data)

    def test_invalid_weights(self):
        for value in (float('nan'), float('inf'), 0, -1):
            data = request()
            data['workload']['profiles'][0]['weight'] = value
            with self.assertRaises(ValueError):
                validate_request(data, TEMPLATES)


class JobTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def jobs(self, runner):
        return Jobs(self.path, runner, TEMPLATES)

    def finish(self, jobs, id):
        jobs.queue.join()
        return jobs.get(id)

    def test_completed_and_partial_publish_immutable_input(self):
        for status in ('completed', 'partial'):
            jobs = self.jobs(FakeRunner(status))
            data = request()
            id = jobs.submit(data)['run_id']
            data['workload']['queries'][0]['sql'] = 'changed after submit'
            result = self.finish(jobs, id)
            self.assertEqual(result['status'], status)
            published = self.path / 'reports' / id
            self.assertTrue((published / 'indexes.sql').is_file())
            self.assertNotEqual(read_json(published / 'workload.json')['queries'][0]['sql'], 'changed after submit')
            self.assertEqual(read_json(published / 'demo-config.json')['analysis_run_id'], 'engine-id')
            self.assertEqual(read_json(published / 'report.json')['run_id'], 'engine-id')

    def test_preparation_uses_snapshot_of_server_dataset(self):
        gate = threading.Event()
        runner = FakeRunner(gate=gate)
        templates = copy.deepcopy(TEMPLATES)
        jobs = Jobs(self.path, runner, templates)
        first = jobs.submit(request(), 'schema')['run_id']
        self.assertTrue(runner.started.wait(2))
        data = request('02-shop')
        second = jobs.submit(data)['run_id']
        original = copy.deepcopy(TEMPLATES['02-shop'])
        templates['02-shop']['schema_sql'] = 'not the accepted template'
        templates['02-shop']['seed_sql'] = 'not the accepted seed'
        data['schema_sql'] = 'client mutation after submit'
        data['seed_sql'] = 'client mutation after submit'
        gate.set()
        self.assertEqual(self.finish(jobs, first)['status'], 'completed')
        self.assertEqual(jobs.get(second)['status'], 'completed')
        prepared = runner.prepared[1]
        config = read_json(self.path / 'reports' / second / 'demo-config.json')
        snapshot = read_json(self.path / 'jobs' / second / 'request.json')
        for key in ('schema_sql', 'seed_sql'):
            self.assertEqual(prepared[key], original[key])
            self.assertEqual(config[key], original[key])
            self.assertEqual(snapshot[key], original[key])
        self.assertEqual(config['template_version'], original['version'])

    def test_all_templates_prepare_and_publish(self):
        runner = FakeRunner()
        jobs = self.jobs(runner)
        for template_id, template in TEMPLATES.items():
            with self.subTest(template_id=template_id):
                id = jobs.submit(request(template_id))['run_id']
                self.assertEqual(self.finish(jobs, id)['status'], 'completed')
                self.assertEqual(runner.prepared[-1]['schema_sql'], template['schema_sql'])
                self.assertEqual(runner.prepared[-1]['seed_sql'], template['seed_sql'])

    def test_failed_and_incomplete_exports_are_not_published(self):
        for runner in (FakeRunner(missing='indexes.sql'), FakeRunner('failed')):
            jobs = self.jobs(runner)
            id = jobs.submit(request())['run_id']
            result = self.finish(jobs, id)
            self.assertEqual(result['status'], 'failed')
            self.assertIsNone(result['report_url'])
            self.assertFalse((self.path / 'reports' / id).exists())

    def test_schema_and_analysis_share_a_serial_queue(self):
        gate = threading.Event()
        runner = FakeRunner(gate=gate)
        jobs = self.jobs(runner)
        first = jobs.submit(request(), 'schema')['run_id']
        self.assertTrue(runner.started.wait(2))
        second = jobs.submit(request())['run_id']
        self.assertNotEqual(first, second)
        self.assertEqual(jobs.get(second)['status'], 'queued')
        self.assertEqual(len(runner.prepared), 1)
        self.assertEqual(list((self.path / 'reports').iterdir()), [])
        gate.set()
        self.assertEqual(self.finish(jobs, first)['status'], 'completed')
        self.assertFalse((self.path / 'reports' / first).exists())
        self.assertIsNotNone(jobs.get(second)['report_url'])

    def test_restart_marks_interrupted_job_without_replaying_ddl(self):
        write_json(self.path / 'jobs' / 'old' / 'status.json', dict(run_id='old', status='preparing'))
        runner = FakeRunner()
        jobs = self.jobs(runner)
        self.assertEqual(jobs.get('old')['status'], 'failed')
        self.assertEqual(runner.prepared, [])


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.runner = FakeRunner()
        self.jobs = Jobs(self.path, self.runner, TEMPLATES)
        handler = make_handler(self.jobs, TEMPLATES)
        handler.log_message = lambda *args: None
        self.server = FrontendHTTPServer(('127.0.0.1', 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.jobs.queue.join()
        self.temp.cleanup()

    def post(self, endpoint, data):
        req = Request(self.base + endpoint, json.dumps(data).encode(), {'Content-Type': 'application/json'})
        return urlopen(req, timeout=5)

    def test_client_ddl_and_seed_rejected_before_any_job_or_database_call(self):
        for endpoint in ('/api/runs', '/api/schema-previews'):
            for field in ('schema_sql', 'seed_sql'):
                data = request()
                data[field] = 'ALTER TABLE public.records ADD COLUMN injected integer;'
                with self.subTest(endpoint=endpoint, field=field):
                    with self.assertRaises(HTTPError) as caught:
                        self.post(endpoint, data)
                    with caught.exception as response:
                        self.assertEqual(response.code, 400)
                        self.assertIn('серверным шаблоном', json.load(response)['error'])
        self.assertEqual(self.runner.prepared, [])
        self.assertEqual(self.jobs.items, {})
        self.assertEqual(list((self.path / 'jobs').iterdir()), [])

    def test_frequency_validation_is_shared_by_both_endpoints(self):
        for endpoint in ('/api/runs', '/api/schema-previews'):
            for value in (-1, .5, 1_000_001):
                data = request()
                data['workload']['profiles'][0]['calls_per_hour']['records_recent'] = value
                with self.subTest(endpoint=endpoint, value=value):
                    with self.assertRaises(HTTPError) as caught:
                        self.post(endpoint, data)
                    with caught.exception as response:
                        self.assertEqual(response.code, 400)
                        self.assertIn('records_recent', json.load(response)['error'])
        self.assertEqual(self.runner.prepared, [])

    def test_valid_api_request_publishes_original_sql_and_trusted_dataset(self):
        data = request()
        sql = '\nSELECT id FROM public.records WHERE group_id = 42;  '
        data['workload']['queries'][0]['sql'] = sql
        data['workload']['profiles'][0]['calls_per_hour']['records_recent'] = 1_000_000
        with self.post('/api/runs', data) as response:
            self.assertEqual(response.status, 202)
            item = json.load(response)
        self.jobs.queue.join()
        with urlopen(self.base + item['status_url'], timeout=5) as response:
            result = json.load(response)
        self.assertEqual(result['status'], 'completed')
        prefix = result['report_url'].rsplit('/', 1)[0]
        with urlopen(self.base + prefix + '/workload.json', timeout=5) as response:
            self.assertEqual(json.load(response)['queries'][0]['sql'], sql)
        with urlopen(self.base + prefix + '/demo-config.json', timeout=5) as response:
            self.assertEqual(json.load(response)['schema_sql'], TEMPLATES['01-records']['schema_sql'])


    def test_draining_api_rejects_both_post_endpoints_with_503(self):
        self.jobs.begin_shutdown()
        for endpoint in ('/api/runs', '/api/schema-previews'):
            with self.subTest(endpoint=endpoint), self.assertRaises(HTTPError) as caught:
                self.post(endpoint, request())
            with caught.exception as response:
                self.assertEqual(response.code, 503)
                self.assertIn('Сервер обновляется', json.load(response)['error'])
        self.assertEqual(self.jobs.items, {})

    def test_in_flight_http_request_is_rejected_after_shutdown_begins(self):
        entered, resume = threading.Event(), threading.Event()
        statuses = []

        def delayed_validation(*args):
            entered.set()
            if not resume.wait(2):
                raise RuntimeError('validation gate timeout')
            return validate_request(*args)

        def post():
            try:
                with self.post('/api/runs', request()) as response:
                    statuses.append(response.status)
            except HTTPError as response:
                statuses.append(response.code)
                response.close()

        with patch('server.validate_request', side_effect=delayed_validation):
            thread = threading.Thread(target=post)
            thread.start()
            try:
                self.assertTrue(entered.wait(2))
                self.jobs.begin_shutdown()
            finally:
                resume.set()
                thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(statuses, [503])
        self.assertEqual(self.jobs.items, {})


class PreparationTests(unittest.TestCase):
    def test_preparation_does_not_execute_workload_sql_as_database_owner(self):
        calls = []
        runner = LocalRunner()
        runner.db = lambda sql, database='frontend_demo': calls.append((database, sql)) or '{"tables": []}'
        template = TEMPLATES['01-records']
        snapshot = {**request(), 'schema_sql': template['schema_sql'], 'seed_sql': template['seed_sql']}
        unvalidated_sql = 'DROP ROLE frontend_reader;'
        snapshot['workload']['queries'][0]['sql'] = unvalidated_sql
        runner.prepare(snapshot)
        self.assertTrue(any(template['schema_sql'] in sql for _, sql in calls))
        self.assertTrue(any(template['seed_sql'] in sql for _, sql in calls))
        self.assertFalse(any(unvalidated_sql in sql for _, sql in calls))


class DeploymentLifecycleTests(unittest.TestCase):
    def test_data_directory_can_live_outside_release_and_creates_parents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = root / 'persistent' / 'frontend'
            with patch.dict(os.environ, {'FRONTEND_DATA_DIR': str(shared)}):
                self.assertEqual(data_directory(), shared.resolve())
            (shared / 'report-marker').write_text('preserve')
            with patch.dict(os.environ, {'FRONTEND_DATA_DIR': str(shared)}):
                self.assertEqual((data_directory() / 'report-marker').read_text(), 'preserve')
            with patch('server.ROOT', root / 'release'), patch.dict(os.environ, {'FRONTEND_DATA_DIR': ''}):
                self.assertEqual(data_directory(), (root / 'release' / '.local').resolve())

    def test_health_exposes_only_configured_deployment_revision(self):
        for revision in ('a' * 40, ''):
            with self.subTest(revision=revision), patch.dict(os.environ, {'FRONTEND_REVISION': revision}):
                runner = LocalRunner()
            runner.command = lambda *args, **kwargs: (0, '', '')
            health = runner.health()
            self.assertTrue(health['database'])
            if revision:
                self.assertEqual(health['deployment_revision'], revision)
            else:
                self.assertNotIn('deployment_revision', health)

    def test_shutdown_drains_accepted_jobs_and_rejects_new_ones(self):
        with tempfile.TemporaryDirectory() as temporary:
            gate = threading.Event()
            runner = FakeRunner(gate=gate)
            directory = Path(temporary)
            jobs = Jobs(directory, runner, TEMPLATES)
            first = jobs.submit(request())['run_id']
            try:
                self.assertTrue(runner.started.wait(2))
                second = jobs.submit(request('02-shop'))['run_id']
                jobs.begin_shutdown()
                with self.assertRaisesRegex(ShuttingDownError, 'Сервер обновляется'):
                    jobs.submit(request('03-wide'))
                self.assertEqual(len(jobs.items), 2)
            finally:
                gate.set()
                jobs.queue.join()
            for id in (first, second):
                self.assertEqual(jobs.get(id)['status'], 'completed')
                self.assertTrue((directory / 'reports' / id / 'report.html').is_file())

    def test_shutdown_wins_against_request_still_being_validated(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            jobs = Jobs(directory, FakeRunner(), TEMPLATES)
            entered, resume = threading.Event(), threading.Event()
            errors = []

            def delayed_validation(*args):
                entered.set()
                if not resume.wait(2):
                    raise RuntimeError('validation gate timeout')
                return validate_request(*args)

            def submit():
                try:
                    jobs.submit(request())
                except Exception as error:
                    errors.append(error)

            with patch('server.validate_request', side_effect=delayed_validation):
                thread = threading.Thread(target=submit)
                thread.start()
                try:
                    self.assertTrue(entered.wait(2))
                    jobs.begin_shutdown()
                finally:
                    resume.set()
                    thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(len(errors), 1)
            self.assertIsInstance(errors[0], ShuttingDownError)
            self.assertEqual(jobs.items, {})
            self.assertEqual(jobs.queue.unfinished_tasks, 0)
            self.assertEqual(list((directory / 'jobs').iterdir()), [])

    def test_sigterm_retains_shared_lock_until_reports_are_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            shared = Path(temporary) / 'nested' / 'shared'
            script = '''
import os, sys, time
from pathlib import Path
sys.path.insert(0, sys.argv.pop(1))
sys.path.insert(0, sys.argv.pop(1))
import server
from test_server import FakeRunner
class DelayedRunner(FakeRunner):
    def __init__(self, host):
        super().__init__()
    def prepare(self, data):
        shared = Path(os.environ['FRONTEND_DATA_DIR'])
        (shared / 'started').write_text('started')
        deadline = time.monotonic() + 10
        while not (shared / 'resume').exists():
            if time.monotonic() > deadline:
                raise RuntimeError('process test gate timeout')
            time.sleep(.01)
        return super().prepare(data)
server.LocalRunner = DelayedRunner
server.main()
'''
            env = dict(os.environ, FRONTEND_DATA_DIR=str(shared))
            process = subprocess.Popen([sys.executable, '-u', '-c', script,
                                        str(ROOT), str(ROOT / 'tests'), '--port', '0'],
                                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertTrue(select.select([process.stdout], [], [], 5)[0], 'server did not start')
                base = process.stdout.readline().strip().split(' → ')[-1]
                ids = []
                for template_id in ('01-records', '02-shop'):
                    req = Request(base + '/api/runs', json.dumps(request(template_id)).encode(),
                                  {'Content-Type': 'application/json'})
                    with urlopen(req, timeout=5) as response:
                        ids.append(json.load(response)['run_id'])
                with (shared / 'server.lock').open('r') as lock:
                    process.send_signal(signal.SIGTERM)
                    self.assertTrue(select.select([process.stdout], [], [], 5)[0], 'shutdown did not start')
                    while process.stdout.readline().strip() != 'Завершение очереди…':
                        self.assertIsNone(process.poll())
                    # Повторный stop не должен прервать дренирование уже принятой очереди.
                    process.send_signal(signal.SIGTERM)
                    self.assertIsNone(process.poll())
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    (shared / 'resume').write_text('resume')
                    stdout, stderr = process.communicate(timeout=10)
                    self.assertEqual(process.returncode, 0, stdout + stderr)
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                for id in ids:
                    self.assertEqual(read_json(shared / 'jobs' / id / 'status.json')['status'], 'completed')
                    self.assertTrue((shared / 'reports' / id / 'report.html').is_file())
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
