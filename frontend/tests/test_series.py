import copy
import json
import io
import threading
from types import SimpleNamespace
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import make_handler, http_json, series_result, report_artifact, Jobs, LocalRunner, ROOT, read_json, write_json, validate_request
TEMPLATES = {t['id']: t for t in (read_json(p) for p in (ROOT / 'templates').glob('*.json'))}
def request():
    t = TEMPLATES['01-records']
    return dict(template_id=t['id'], template_version=t['version'], workload=copy.deepcopy(t['workload']),
                series=dict(schema_version=1, budget='storage_budget_bytes', values=[0, 16777216, 67108864], timeout_seconds=60))

class SeriesTests(unittest.TestCase):
    def test_series_fixture_keeps_write_profile_regardless_of_template_order(self):
        reordered = {key: TEMPLATES[key] for key in sorted(TEMPLATES, reverse=True)}
        self.assertNotEqual(next(iter(reordered)), '01-records')
        with patch.dict(TEMPLATES, reordered, clear=True):
            data = request()
            self.assertEqual(data['template_id'], '01-records')
            self.assertEqual(data['workload']['table_write_rates'], TEMPLATES['01-records']['workload']['table_write_rates'])
            data['series']['budget'] = 'write_budget_units_per_hour'
            validate_request(data, TEMPLATES)

    def test_validation_rejects_counts_duplicates_negative_and_missing_rates(self):
        for values in ([1, 2], [1, 1, 2], [1, 2, -1], [True, 2, 3], [0, 1, float('inf')]):
            data = request(); data['series']['values'] = values
            with self.assertRaises(ValueError): validate_request(data, TEMPLATES)
        data = request(); data['series']['budget'] = 'write_budget_units_per_hour'
        data['workload'].pop('table_write_rates', None)
        with self.assertRaises(ValueError): validate_request(data, TEMPLATES)

    def test_single_contract_is_preserved(self):
        data = request(); data.pop('series')
        self.assertNotIn('series', validate_request(data, TEMPLATES))

    def test_local_runner_uses_existing_series_cli_and_preserves_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            workload = folder / 'workload.json'; series = folder / 'series.json'
            write_json(workload, request()['workload']); write_json(series, request()['series'])
            runner = LocalRunner(); runner.cli = workload
            with patch.object(runner, 'command', return_value=(0, '', '')) as command:
                runner.analyze(workload, folder / 'out', series)
            args = command.call_args.args[0]
            self.assertEqual(args[1], 'analyze-series')
            self.assertEqual(args[args.index('--series') + 1], str(series))
            self.assertEqual(args[args.index('--analysis-mode') + 1], request()['workload'].get('analysis_mode', 'normal'))
            self.assertEqual(command.call_args.kwargs['timeout'], 120)

    def test_storage_and_write_series_prepare_once_and_keep_failed_points(self):
        for budget, timeout in (('storage_budget_bytes', False), ('write_budget_units_per_hour', False), ('storage_budget_bytes', True)):
            with self.subTest(budget=budget), tempfile.TemporaryDirectory() as tmp:
                class Runner:
                    def __init__(self): self.preparations = 0; self.calls = 0
                    def prepare(self, data): self.preparations += 1; return {'tables': []}
                    def analyze(self, workload, output, series):
                        self.calls += 1
                        output.mkdir()
                        point = output / 'point-1'; point.mkdir()
                        write_json(point / 'report.json', {'status': 'completed', 'selected': {'candidate_ids': []}})
                        (point / 'report.html').write_text('point')
                        (point / 'indexes.sql').write_text('-- saved')
                        write_json(output / 'series.json', {'status': 'running' if timeout else 'partial', 'points': [
                            {'ordinal': 1, 'status': 'completed', 'artifacts': {'report.html': 'point-1/report.html'}},
                            {'ordinal': 2, 'status': 'failed'}]})
                        (output / 'report.html').write_text('<a href="point-1/report.html">point</a>')
                        if timeout: raise RuntimeError('Превышено время выполнения локальной команды.')
                        return 3, '', ''
                runner = Runner(); jobs = Jobs(Path(tmp), runner, TEMPLATES)
                data = request(); data['series']['budget'] = budget
                item = jobs.submit(data); jobs.queue.join()
                result = jobs.get(item['run_id'])
                self.assertEqual(result['status'], 'partial', result.get('error'))
                self.assertEqual(runner.preparations, 1); self.assertEqual(runner.calls, 1)
                saved = Path(tmp) / 'reports' / item['run_id']
                self.assertTrue((saved / 'point-1/indexes.sql').is_file())
                self.assertEqual(read_json(saved / 'workload.json'), data['workload'])
                self.assertEqual(read_json(saved / 'series-request.json'), data['series'])

    def test_nested_artifacts_are_local_to_the_saved_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); run_id = '00000000-0000-4000-8000-000000000001'
            base = root / 'reports' / run_id
            (base / 'point-1').mkdir(parents=True)
            target = base / 'point-1/report.html'; target.write_text('point')
            self.assertEqual(report_artifact(root, ['reports', run_id, 'point-1', 'report.html']), target.resolve())
            self.assertIsNone(report_artifact(root, ['reports', run_id, '..', 'report.html']))
            self.assertIsNone(report_artifact(root, ['reports', run_id, 'point-1', 'cli.log']))
            (base / 'outside').symlink_to(root, target_is_directory=True)
            self.assertIsNone(report_artifact(root, ['reports', run_id, 'outside', 'report.html']))

    def test_underflow_at_http_boundary_is_rejected_before_float_conversion(self):
        for literal in ('1e-400', '-1e-400'):
            with self.assertRaises(ValueError): http_json('{"values":[' + literal + ',1,2]}')
        self.assertEqual(http_json('{"values":[0,1e-3,2]}')['values'], [0, .001, 2])

    def test_post_handler_rejects_underflow_before_submitting_a_job(self):
        def submit(*args):
            self.fail("Underflow must be rejected before queue submission")
        jobs = SimpleNamespace(lock=threading.RLock(), accepting=True, submit=submit)
        handler_class = make_handler(jobs, TEMPLATES)
        for literal in ('1e-400', '-1e-400'):
            raw = ('{"series":{"values":[' + literal + ',1,2]}}').encode()
            handler = object.__new__(handler_class)
            handler.path = '/api/runs'
            handler.headers = {'Content-Length': str(len(raw))}
            handler.rfile = io.BytesIO(raw)
            sent = []
            handler.send = lambda code, body: sent.append((code, body))
            handler.do_POST()
            self.assertEqual(sent[0][0], 400)
            self.assertIn('ненулевое', sent[0][1]['error'])

    def test_terminal_classification_and_missing_summary_html(self):
        for state, selected, expected in [('completed', [], 'partial'), ('failed', None, 'failed'), ('skipped', None, 'failed')]:
            with self.subTest(state=state), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); run_id = '00000000-0000-4000-8000-000000000001'
                saved = root / 'jobs' / run_id / 'export'
                points = []
                if state != 'skipped':
                    write_json(saved / 'point-1/report.json', {'status': state, 'selected': None if selected is None else {'candidate_ids': selected}})
                    (saved / 'point-1/report.html').write_text('diagnostic or useful report')
                    points.append({'status': state, 'selected_index_ids': selected, 'artifacts': {'report.html': 'point-1/report.html'}})
                else: points.append({'status': 'skipped', 'selected_index_ids': None})
                write_json(saved / 'series.json', {'status': 'running', 'series_id': 'core-id', 'points': points})
                result = series_result(root, run_id, request(), {'catalog': {'tables': []}}, 1, 'EXPORT_FAILED / timeout')
                self.assertEqual(result['status'], expected)
                self.assertEqual(result['error'], 'EXPORT_FAILED / timeout')
                published = root / 'reports' / run_id
                page = (published / 'demo-series.html').read_text()
                self.assertIn(expected, page)
                self.assertIn('EXPORT_FAILED / timeout', page)
                if state != 'skipped': self.assertIn('point-1/report.html', page)
                self.assertFalse((published / 'report.html').exists())
                self.assertEqual(read_json(published / 'series.json')['status'], 'running')
                self.assertEqual(read_json(published / 'demo-config.json')['series_id'], 'core-id')

    def test_restart_exposes_existing_partial_series_without_rerunning(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); run_id = '00000000-0000-4000-8000-000000000001'
            folder = root / 'jobs' / run_id
            write_json(folder / 'status.json', {'run_id': run_id, 'status': 'analyzing', 'catalog': {'tables': [{'name': 'public.records'}]}})
            write_json(folder / 'export/series.json', {'status': 'partial', 'points': [{'ordinal': 1, 'status': 'completed'}]})
            (folder / 'export/report.html').write_text('saved report')
            write_json(folder / 'request.json', request())
            write_json(folder / 'export/point-1/report.json', {'status': 'completed', 'selected': {'candidate_ids': []}})
            (folder / 'export/point-1/report.html').write_text('point')
            jobs = Jobs(root, object(), TEMPLATES)
            result = jobs.get(run_id)
            self.assertEqual(result['status'], 'partial')
            self.assertTrue((root / 'reports' / run_id / 'report.html').is_file())
            self.assertEqual(len(result['points']), 1)
            saved = root / 'reports' / run_id
            self.assertEqual(read_json(saved / 'workload.json'), request()['workload'])
            self.assertEqual(read_json(saved / 'series-request.json'), request()['series'])
            self.assertEqual(read_json(saved / 'schema.json'), {'tables': [{'name': 'public.records'}]})
            self.assertEqual(read_json(saved / 'demo-config.json')['run_id'], run_id)
            self.assertIn('Процесс остановлен', (saved / 'demo-series.html').read_text())

if __name__ == '__main__': unittest.main()
