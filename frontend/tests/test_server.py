import copy
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import Jobs, ROOT, read_json, validate_request, write_json

TEMPLATES = {t['id']: t for t in (read_json(p) for p in (ROOT / 'templates').glob('*.json'))}


def request(template_id='01-records'):
    t = TEMPLATES[template_id]
    return dict(template_id=t['id'], template_version=t['version'],
                schema_sql=t['schema_sql'], seed_sql=t['seed_sql'], workload=copy.deepcopy(t['workload']))


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


if __name__ == '__main__':
    unittest.main()
