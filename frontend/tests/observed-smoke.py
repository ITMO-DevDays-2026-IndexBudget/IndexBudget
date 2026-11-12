#!/usr/bin/env python3
"""Real #102 -> #107 -> demo -> single/write-series route, on an owned disposable DB.
Requires installDist, Docker and JDK 25. --serve keeps the local test API for browser QA.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from urllib.request import Request, urlopen
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import FrontendHTTPServer, Jobs, LocalRunner, ROOT, make_handler, read_json, write_json, load_templates


def command(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kwargs).stdout.strip()


class DisposableRunner(LocalRunner):
    def __init__(self, container):
        super().__init__()
        self.container = container

    def db(self, sql, database='frontend_demo'):
        return command(['docker', 'exec', '-i', self.container, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1',
                        '-U', 'postgres', '-d', database], input=sql)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serve', action='store_true')
    args = parser.parse_args()
    output = ROOT.parent / 'build/write-profile-verification' / str(uuid4())
    output.mkdir(parents=True)
    container = command(['docker', 'run', '--rm', '-d', '-p', '127.0.0.1::5432', '-e',
        'POSTGRES_HOST_AUTH_METHOD=trust', os.environ.get('FRONTEND_TEST_DB_IMAGE', 'indexbudget-import-test:local'),
        '-c', 'shared_preload_libraries=pg_stat_statements', '-c', 'compute_query_id=on'])
    server = jobs = None
    try:
        for _ in range(60):
            if subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'postgres'], capture_output=True).returncode == 0: break
            time.sleep(1)
        port = command(['docker', 'port', container, '5432/tcp']).rsplit(':', 1)[1]
        os.environ['FRONTEND_DB_PORT'] = port
        runner = DisposableRunner(container)
        templates = load_templates(ROOT)
        template = templates['04-scenarios']
        request = {'template_id': template['id'], 'template_version': template['version'], 'workload': copy.deepcopy(template['workload'])}
        runner.prepare({**request, 'schema_sql': template['schema_sql'], 'seed_sql': template['seed_sql']})
        runner.db('CREATE EXTENSION pg_stat_statements;')
        source = str(uuid4())
        env = {**os.environ, 'INDEXBUDGET_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{port}/frontend_demo?user=postgres'}
        cli = str(runner.cli)
        def invoke(*arguments, accepted=(0,)):
            result = subprocess.run([cli, *map(str, arguments)], env=env, text=True, capture_output=True)
            if result.returncode not in accepted: raise RuntimeError(result.stderr)
            return result
        sql = 'SELECT id FROM public.records WHERE group_id = 42'
        runner.db(sql + '; SELECT pg_stat_force_next_flush();')
        for command_name, directory in [('table-stats', 'tables-before'), ('stats', 'read-before')]:
            invoke(command_name, 'snapshot', '--source-id', source, '--output-dir', output / directory)
        # These writes belong only to the fixture, never to analyze.
        runner.db("UPDATE public.records SET group_id = group_id WHERE id <= 7; DELETE FROM public.records WHERE id = 10; "
                  + sql + '; ' + sql + '; SELECT pg_stat_force_next_flush();')
        for command_name, directory in [('table-stats', 'tables-after'), ('stats', 'read-after')]:
            invoke(command_name, 'snapshot', '--source-id', source, '--output-dir', output / directory)
        before = read_json(output / 'read-before/snapshot-1.json')
        row = next(r for r in before['entries'] if r.get('query', '').startswith('SELECT id FROM public.records WHERE group_id'))
        key = {k: row[k] for k in ['dbid', 'userid', 'queryid', 'toplevel']}
        key['source_id'] = source
        write_json(output / 'examples.json', {'schema_version': 1, 'kind': 'pg_stat_statements_examples',
            'examples': [{'key': key, 'sql': sql, 'search_path': ['pg_catalog', 'public']}]})
        write_json(output / 'import-config.json', {'schema_version': 1, 'kind': 'pg_stat_statements_import_config',
            'windows': [{'id': 'observed', 'weight': 1, 'before': 'read-before/snapshot-1.json', 'after': 'read-after/snapshot-1.json'}],
            'examples': 'examples.json', 'constraints': {'storage_budget_bytes': 67108864, 'write_budget_units_per_hour': None},
            'table_write_window': {'before': 'tables-before/snapshot-1.json', 'after': 'tables-after/snapshot-1.json'}})
        invoke('import-workload', '--config', output / 'import-config.json', '--output-dir', output / 'imported')
        imported = read_json(output / 'imported/workload.json')
        assert imported['table_write_rates_origin']['complete'] is True
        assert imported['table_write_rates']['public.records']['updates_per_hour'] > 0
        # Наблюдаемое окно содержит только records, поэтому JOIN на groups здесь не нужен.
        request['workload']['queries'] = [q for q in request['workload']['queries'] if q['id'] != 'category_join']
        for profile in request['workload']['profiles']:
            profile['calls_per_hour'].pop('category_join')
        request['write_source'] = 'observed'
        for key in ['table_write_rates', 'table_write_rates_origin']: request['workload'][key] = imported[key]
        request['workload']['analysis_mode'] = 'normal'
        request['workload']['constraints']['write_budget_units_per_hour'] = 1000000
        jobs = Jobs(output / 'demo', runner, templates)
        server = FrontendHTTPServer(('0.0.0.0' if args.serve else '127.0.0.1', 0), make_handler(jobs, templates))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f'http://127.0.0.1:{server.server_port}'
        def api(path, data=None):
            req = Request(base + path, data=None if data is None else json.dumps(data).encode(), headers={'Content-Type': 'application/json'})
            with urlopen(req, timeout=10) as response: return json.load(response)
        for mode in ['single', 'write']:
            submission = copy.deepcopy(request)
            if mode == 'write': submission['series'] = {'schema_version': 1, 'budget': 'write_budget_units_per_hour', 'values': [0, 10000, 1000000], 'timeout_seconds': 600}
            job = api('/api/runs', submission)
            deadline = time.monotonic() + 660
            while job['status'] not in {'completed', 'partial', 'failed'} and time.monotonic() < deadline:
                time.sleep(.2)
                job = api('/api/runs/' + job['run_id'])
            assert job['status'] in {'completed', 'partial'}, job
            directory = output / 'demo/reports' / job['run_id']
            reports = [read_json(directory / 'report.json')] if mode == 'single' else [read_json(p) for p in sorted(directory.glob('*/report.json'))]
            assert reports
            for report in reports:
                for key in ['table_write_rates', 'table_write_rates_origin']: assert report[key] == imported[key], (mode, key)
            write_json(output / f'{mode}-request.json', submission)
            write_json(output / f'{mode}-job.json', job)
            # Re-run the same fixed input through CLI against the same restored dataset.
            workload_path = output / f'{mode}-workload.json'
            write_json(workload_path, submission['workload'])
            env['INDEXBUDGET_DATABASE_URL'] = f'jdbc:postgresql://127.0.0.1:{port}/frontend_demo?user=frontend_reader'
            if mode == 'single': invoke('analyze', '--workload', workload_path, '--output-dir', output / 'cli-single', accepted=(0, 3))
            else:
                write_json(output / 'series-request.json', submission['series'])
                invoke('analyze-series', '--workload', workload_path, '--series', output / 'series-request.json', '--output-dir', output / 'cli-write', accepted=(0, 3))
            direct = [read_json(output / 'cli-single/report.json')] if mode == 'single' else [read_json(p) for p in sorted((output / 'cli-write').glob('*/report.json'))]
            assert len(direct) == len(reports)
            for actual, expected in zip(reports, direct):
                for key in ['table_write_rates', 'table_write_rates_origin', 'selected']:
                    assert actual[key] == expected[key], (mode, key)
            print(f'PASS {mode}: demo and CLI rates/window/selected costs and budgets match', flush=True)
        print(json.dumps({'output': str(output), 'url': base}), flush=True)
        if args.serve:
            while True: time.sleep(1)
    finally:
        if server: server.shutdown(); server.server_close()
        if jobs: jobs.begin_shutdown(); jobs.queue.join()
        subprocess.run(['docker', 'rm', '-f', container], capture_output=True)


if __name__ == '__main__':
    try: main()
    except KeyboardInterrupt: pass
