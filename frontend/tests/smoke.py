"""Сквозной тест frontend и его отдельной demo-БД (сервер уже должен работать)."""
import argparse
import copy
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8090')
    parser.add_argument('--verify-local-database', action='store_true',
                        help='Проверить неизменность строк и DML-роль в той же отдельной Compose-БД.')
    parser.add_argument('--repeatability-only', action='store_true',
                        help='Три одинаковых запуска обычного режима на подготовленных клонах.')
    args = parser.parse_args()
    base = args.url.rstrip('/')
    database = None
    if args.verify_local_database:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from server import LocalRunner
        database = LocalRunner()

    def fingerprint():
        return database.db("""SELECT json_build_object(
          'records', (SELECT md5(string_agg(md5(r::text), '' ORDER BY id)) FROM public.records r),
          'groups', (SELECT md5(string_agg(md5(g::text), '' ORDER BY id)) FROM public.groups g),
          'records_count', (SELECT count(*) FROM public.records),
          'groups_count', (SELECT count(*) FROM public.groups),
          'indexes', (SELECT json_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE schemaname = 'public')
        );""")

    def get(path):
        with urlopen(base + path, timeout=15) as response:
            return response.read()

    def post(path, data):
        req = Request(base + path, json.dumps(data).encode(), {'Content-Type': 'application/json'})
        with urlopen(req, timeout=15) as response:
            assert response.status == 202
            return json.load(response)

    def wait(job):
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            state = json.loads(get(job['status_url']))
            if state['status'] in ('completed', 'partial', 'failed'):
                return state
            time.sleep(.5)
        raise AssertionError('Smoke timeout')

    def payload(t):
        return dict(template_id=t['id'], template_version=t['version'], workload=copy.deepcopy(t['workload']))

    def reject(path, data):
        try:
            post(path, data)
            raise AssertionError('Invalid input was accepted')
        except HTTPError as error:
            assert error.code == 400
            assert json.load(error)['error']

    def verify_repeatability():
        reference = None
        common = next(t for t in templates if t['id'] == '04-scenarios')
        for repeat in range(3):
            repeated = payload(common)
            repeated['workload']['analysis_mode'] = 'normal'
            result = wait(post('/api/runs', repeated))
            assert result['status'] in ('completed', 'partial'), result
            prefix = result['report_url'].rsplit('/', 1)[0]
            report = json.loads(get(prefix + '/report.json'))
            catalog = json.loads(get(prefix + '/schema.json'))
            reasons = report['diagnostics']['stop_reasons']
            assert not set(reasons) & {'DEADLINE_EXCEEDED', 'EXPLAIN_LIMIT', 'CANCELLED',
                                       'CONNECTION_LOST', 'EVALUATION_FAILED'}, reasons
            retained = {name: {'ids': report[name]['candidate_ids'], 'costs': report[name]['cost_by_query_id'],
                               'metrics': report[name]['metrics']}
                        for name in ('baseline', 'selected', 'best_robust', 'best_mean')}
            current = {'snapshot': catalog['dataset_snapshot'], 'evaluations': retained,
                       'candidates': sorted(c['id'] for c in report['candidates']), 'stop_reasons': reasons}
            if reference is None:
                reference = current
            else:
                assert current == reference, f'Default repeated run {repeat + 1} changed: {current}'
            if database:
                visible = json.loads(database.db("SELECT to_json(bool_and(relallvisible = relpages AND relpages > 0)) "
                    "FROM pg_class WHERE oid IN ('public.records'::regclass, 'public.groups'::regclass);"))
                assert visible is True
        print('PASS three default runs: same statistics/visibility, baseline, pool, incumbents and selected costs', flush=True)

    assert b'IndexBudget' in get('/')
    for asset in ('/app.js', '/style.css'):
        assert len(get(asset)) > 100
    health = json.loads(get('/api/health'))
    assert health['database'] and health['cli'], health
    templates = json.loads(get('/api/templates'))
    if args.repeatability_only:
        verify_repeatability()
        return
    assert {t['id'] for t in templates} == {'01-records', '02-shop', '03-wide', '04-scenarios', '04-events-brin', '05-lookup-hash'}
    for template in templates:
        assert template['workload']['index_methods'] == ['btree', 'brin', 'hash']
    request = payload(templates[0])
    for endpoint in ('/api/runs', '/api/schema-previews'):
        for key, sql in (('schema_sql', 'ALTER TABLE public.records ADD COLUMN demo_note text;'),
                         ('seed_sql', 'DELETE FROM public.records;')):
            edited = payload(templates[0])
            edited[key] = sql
            reject(endpoint, edited)
    preview = wait(post('/api/schema-previews', request))
    assert preview['status'] == 'completed', preview
    assert 'demo_note' not in [c['name'] for c in preview['catalog']['tables'][0]['columns']]
    assert preview['catalog']['tables'][0]['rows'] == templates[0]['catalog']['tables'][0]['rows']
    assert not preview['report_url']
    print('PASS client DDL/seed rejected; server dataset → PostgreSQL catalog', flush=True)
    qid = templates[0]['workload']['queries'][0]['id']
    for value in (-1, .5, 1_000_001):
        invalid = payload(templates[0])
        invalid['workload']['profiles'][0]['calls_per_hour'][qid] = value
        reject('/api/runs', invalid)
    empty = payload(templates[0])
    empty['workload']['profiles'][0]['calls_per_hour'] = {
        key: 0 for key in empty['workload']['profiles'][0]['calls_per_hour']}
    reject('/api/runs', empty)
    submitted = []
    for t in templates:
        request = payload(t)
        request['workload']['analysis_mode'] = 'fast'
        if t['id'] == templates[0]['id']:
            request['workload']['profiles'][0]['calls_per_hour'][qid] = 0
            request['workload']['profiles'][1]['calls_per_hour'][qid] = 1_000_000
        submitted.append((request, post('/api/runs', request)))
    assert len({j['run_id'] for _, j in submitted}) == len(templates)
    for request, job in submitted:
        result = wait(job)
        assert result['status'] in ('completed', 'partial'), result
        prefix = result['report_url'].rsplit('/', 1)[0]
        report = json.loads(get(prefix + '/report.json'))
        actual = json.loads(get(prefix + '/workload.json'))
        assert actual == request['workload']
        assert report['index_methods'] == ['btree', 'brin', 'hash'] and report['schema_version'] == 4
        assert report['status'] == result['status'] and report['analysis_mode'] == 'fast'
        assert report['queries'][0]['sql'] == actual['queries'][0]['sql']
        assert b'<html' in get(result['report_url'])
        get(prefix + '/indexes.sql')
        config = json.loads(get(prefix + '/demo-config.json'))
        assert config['analysis_run_id'] == report['run_id']
        template = next(t for t in templates if t['id'] == request['template_id'])
        assert config['template_id'] == template['id'] and config['template_version'] == template['version']
        assert config['schema_sql'] == template['schema_sql'] and config['seed_sql'] == template['seed_sql']
        if template['id'] == '04-events-brin':
            assert {c['definition']['method'] for c in report['selected_indexes']} == {'brin'}, report['selected_indexes']
            assert report['selected']['cost_by_query_id']['events_window'] < report['baseline']['cost_by_query_id']['events_window']
            assert report['selected']['cost_by_query_id']['events_all'] == report['baseline']['cost_by_query_id']['events_all']
            assert report['selected']['metrics']['budget_usage']['budgeted_bytes'] <= 65536
            btree = [c for c in report['candidates'] if c['definition']['method'] == 'btree']
            assert btree and all(c['budgeted_bytes'] > 65536 for c in btree)
        if template['id'] == '05-lookup-hash':
            assert {(c['definition']['method'], c['definition']['keys'][0]['column']['name'])
                    for c in report['selected_indexes']} == {('hash', 'external_key'), ('btree', 'created_at')}
            assert len(report['selected_indexes']) == 2
            assert {c['definition']['method'] for c in report['candidates']} == {'btree', 'hash'}
            assert report['selected']['cost_by_query_id']['lookup_exact'] < report['baseline']['cost_by_query_id']['lookup_exact']
            assert report['selected']['cost_by_query_id']['lookup_recent'] < report['baseline']['cost_by_query_id']['lookup_recent']
            assert report['selected']['cost_by_query_id']['lookup_all'] == report['baseline']['cost_by_query_id']['lookup_all']
            budget = actual['constraints']['storage_budget_bytes']
            assert report['selected']['metrics']['budget_usage']['budgeted_bytes'] <= budget
            btree = [c for c in report['candidates'] if c['definition']['method'] == 'btree'
                     and c['definition']['keys'][0]['column']['name'] == 'external_key']
            assert btree and all(c['budgeted_bytes'] > budget for c in btree)
            assert b'USING hash' in get(prefix + '/indexes.sql')
            assert b'USING btree' in get(prefix + '/indexes.sql')
        print(f"PASS {request['template_id']} → {result['status']} → {result['report_url']}", flush=True)
    scenario = next(template for template in templates if template['id'] == '04-scenarios')
    source = json.loads((Path(__file__).resolve().parents[2] / 'demo/scenarios/workload.json').read_text())
    assert [{'id': example['id'], 'sql': example['sql']} for example in scenario['examples']] == source['queries']
    assert len(scenario['examples']) == 9
    before = fingerprint() if database else None
    if database:
        assert json.loads(before)['records_count'] == 200_000 and json.loads(before)['groups_count'] == 1_000
        privileges = json.loads(database.db("""SELECT json_build_object(
          'payload_update', has_column_privilege('frontend_scenarios', 'public.records', 'payload', 'UPDATE'),
          'value_update', has_column_privilege('frontend_scenarios', 'public.records', 'value', 'UPDATE'),
          'delete', has_table_privilege('frontend_scenarios', 'public.records', 'DELETE'),
          'reader_update', has_column_privilege('frontend_reader', 'public.records', 'payload', 'UPDATE'),
          'reader_delete', has_table_privilege('frontend_reader', 'public.records', 'DELETE')
        );"""))
        assert privileges == dict(payload_update=True, value_update=False, delete=True,
                                  reader_update=False, reader_delete=False), privileges
        command = database.compose + ['exec', '-T', 'postgres', 'psql', '-X', '-qAt',
                                     '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate',
                                     '-U', 'frontend_scenarios', '-d', 'frontend_demo']
        code, output, error = database.command(command, input='SHOW default_transaction_read_only;')
        assert code == 0 and output.strip() == 'on', error
        code, _, error = database.command(command,
            input="BEGIN; UPDATE public.records SET payload = 'must-not-write' WHERE id = 1; ROLLBACK;")
        assert code != 0 and '25006' in error, error
        assert fingerprint() == before
    for without_join in (False, True):
        selected = [example for example in scenario['examples'] if not without_join or example['id'] != 'category_join']
        full = payload(scenario)
        full['workload']['queries'] = [{'id': example['id'], 'sql': example['sql']} for example in selected]
        full['workload']['analysis_mode'] = 'fast'
        used_tables = {table for example in selected for table in example['tables']}
        full['workload']['table_write_rates'] = {table: rates for table, rates in full['workload']['table_write_rates'].items()
                                                if table in used_tables}
        for profile in full['workload']['profiles']:
            profile['calls_per_hour'] = {example['id']: example['calls_per_hour'][profile['id']] for example in selected}
        result = wait(post('/api/runs', full))
        assert result['status'] in ('completed', 'partial'), result
        prefix = result['report_url'].rsplit('/', 1)[0]
        report = json.loads(get(prefix + '/report.json'))
        assert [{'id': query['id'], 'sql': query['sql']} for query in report['queries']] == full['workload']['queries']
        assert {query['statement_kind'] for query in report['queries']} == {'select', 'update', 'delete'}
        assert json.loads(get(prefix + '/workload.json')) == full['workload']
        if database:
            assert fingerprint() == before, 'Scenario analysis changed seed rows or real indexes'
        print(f"PASS canonical scenario {'without JOIN' if without_join else 'all 9 examples'} → {result['status']} → original SELECT/UPDATE/DELETE", flush=True)
    series_request = payload(scenario)
    series_request['workload'] = copy.deepcopy(source)
    series_request['workload']['analysis_mode'] = 'fast'
    series_request['workload']['constraints']['max_indexes'] = 0
    series_request['series'] = {'schema_version': 1, 'budget': 'storage_budget_bytes',
                                'values': [0, 16 * 1024 * 1024, 64 * 1024 * 1024], 'timeout_seconds': 120}
    series_job = wait(post('/api/runs', series_request))
    assert series_job['status'] in ('completed', 'partial'), series_job
    prefix = series_job['report_url'].rsplit('/', 1)[0]
    summary = json.loads(get(prefix + '/series.json'))
    assert summary['status'] in ('completed', 'partial') and len(summary['points']) == 3, summary
    assert json.loads(get(prefix + '/workload.json')) == series_request['workload']
    assert json.loads(get(prefix + '/series-request.json')) == series_request['series']
    assert json.loads(get(prefix + '/demo-config.json'))['analysis_role'] == 'frontend_scenarios'
    assert b'<html' in get(series_job['report_url'])
    for point in summary['points']:
        assert point['status'] in ('completed', 'partial'), point
        point_report = json.loads(get(prefix + '/' + point['artifacts']['report.json']))
        assert {query['statement_kind'] for query in point_report['queries']} == {'select', 'update', 'delete'}
        assert [{'id': query['id'], 'sql': query['sql']} for query in point_report['queries']] == source['queries']
        assert point_report['selected']['candidate_ids'] == []
        assert b'<html' in get(prefix + '/' + point['artifacts']['report.html'])
        get(prefix + '/' + point['artifacts']['indexes.sql'])
    if database:
        assert fingerprint() == before, 'DML budget series changed seed rows or real indexes'
    print(f"PASS canonical SELECT/UPDATE/DELETE budget series → {series_job['status']} → 3 preserved point reports", flush=True)
    if database:
        print('PASS separate DML privileges, server read-only and unchanged seed/physical indexes', flush=True)
    # При тех же данных и SQL больший бюджет позволяет выбрать более дорогой B-tree.
    generous = payload(next(t for t in templates if t['id'] == '04-events-brin'))
    generous['workload']['constraints']['storage_budget_bytes'] = 67108864
    result = wait(post('/api/runs', generous))
    assert result['status'] == 'completed', result
    report = json.loads(get(result['report_url'].rsplit('/', 1)[0] + '/report.json'))
    assert {c['definition']['method'] for c in report['selected_indexes']} == {'btree'}
    print('PASS events: 64 KiB selects BRIN, 64 MiB selects B-tree', flush=True)
    tight = payload(next(t for t in templates if t['id'] == '05-lookup-hash'))
    tight['workload']['constraints']['storage_budget_bytes'] = 8388608
    result = wait(post('/api/runs', tight))
    assert result['status'] == 'completed', result
    report = json.loads(get(result['report_url'].rsplit('/', 1)[0] + '/report.json'))
    assert [c['definition']['method'] for c in report['selected_indexes']] == ['hash']
    assert report['selected']['cost_by_query_id']['lookup_recent'] == report['baseline']['cost_by_query_id']['lookup_recent']
    print('PASS lookup: 8 MiB selects hash, 16 MiB selects hash + B-tree', flush=True)
    verify_repeatability()
    broken = payload(templates[0])
    broken['workload']['queries'][0]['sql'] = 'SELECT missing_demo_column FROM public.records'
    failure = wait(post('/api/runs', broken))
    assert failure['status'] == 'failed' and failure['error'] and not failure['report_url']
    try:
        get(f"/reports/{failure['run_id']}/report.html")
        raise AssertionError('Failed run was published')
    except HTTPError as error:
        assert error.code == 404
    bad = payload(templates[0])
    bad['workload']['profiles'][0]['weight'] = 0
    reject('/api/runs', bad)
    # Ошибка SQL не блокирует следующую подготовку доверенного датасета.
    recovery = wait(post('/api/schema-previews', payload(templates[0])))
    assert recovery['status'] == 'completed', recovery
    assert submitted[0][1]['run_id'].encode() in get('/reports/')
    print('PASS input rejection, failed export, recovery and report listing', flush=True)


if __name__ == '__main__':
    main()
