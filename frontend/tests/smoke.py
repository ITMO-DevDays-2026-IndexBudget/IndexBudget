"""Сквозной тест frontend и его отдельной demo-БД (сервер уже должен работать)."""
import argparse
import copy
import json
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8090')
    base = parser.parse_args().url.rstrip('/')

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

    assert b'IndexBudget' in get('/')
    for asset in ('/app.js', '/style.css'):
        assert len(get(asset)) > 100
    health = json.loads(get('/api/health'))
    assert health['database'] and health['cli'], health
    templates = json.loads(get('/api/templates'))
    assert len(templates) == 3
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
    assert len({j['run_id'] for _, j in submitted}) == 3
    for request, job in submitted:
        result = wait(job)
        assert result['status'] in ('completed', 'partial'), result
        prefix = result['report_url'].rsplit('/', 1)[0]
        report = json.loads(get(prefix + '/report.json'))
        actual = json.loads(get(prefix + '/workload.json'))
        assert actual == request['workload']
        assert report['status'] == result['status'] and report['analysis_mode'] == 'fast'
        assert report['queries'][0]['sql'] == actual['queries'][0]['sql']
        assert b'<html' in get(result['report_url'])
        get(prefix + '/indexes.sql')
        config = json.loads(get(prefix + '/demo-config.json'))
        assert config['analysis_run_id'] == report['run_id']
        template = next(t for t in templates if t['id'] == request['template_id'])
        assert config['template_id'] == template['id'] and config['template_version'] == template['version']
        assert config['schema_sql'] == template['schema_sql'] and config['seed_sql'] == template['seed_sql']
        print(f"PASS {request['template_id']} → {result['status']} → {result['report_url']}", flush=True)
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
