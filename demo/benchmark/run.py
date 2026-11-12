#!/usr/bin/env python3
"""D3/#110: own disposable PostgreSQL only; committed single-client mixed benchmark."""
import argparse
import csv
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / 'build/install/indexbudget/bin/indexbudget'


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n')


def summarize(samples, per_query=False):
    groups = {}
    for s in samples:
        groups.setdefault((s['mode'], s['variant'], s['kind'], s['query_id'] if per_query else None), []).append(s)
    result = []
    for (mode, variant, kind, query_id), rows in sorted(groups.items()):
        calls = len(rows); affected = sum(r['rows'] for r in rows)
        seconds = (sum({r['repeat']: r['exposure_seconds'] for r in rows}.values())
                   if all('exposure_seconds' in r for r in rows) else sum(r['wall_seconds'] for r in rows))
        latency = [r['psql_ms'] for r in rows]
        wal = None if any(r['wal_bytes'] is None for r in rows) else sum(r['wal_bytes'] for r in rows)
        result.append(dict(mode=mode, variant=variant, kind=kind, query_id=query_id, calls=calls, rows=affected,
            tps=calls / seconds, rows_per_second=affected / seconds, errors=0,
            median_psql_ms=statistics.median(latency), min_psql_ms=min(latency), max_psql_ms=max(latency),
            stdev_psql_ms=statistics.stdev(latency) if calls > 1 else None,
            wal_bytes=wal, wal_bytes_per_operation=None if wal is None else wal / calls,
            wal_bytes_per_row=None if wal is None or not affected else wal / affected))
    return result


def parse_psql(text, kind):
    times = re.findall(r'^Time: ([0-9.]+) ms', text, re.M)
    if len(times) != 1: raise ValueError('Expected exactly one timed operation')
    if kind != 'SELECT':
        tags = re.findall(r'^(?:INSERT 0|UPDATE|DELETE) ([0-9]+)$', text, re.M)
        if len(tags) != 1 or int(tags[0]) <= 0: raise ValueError('Write must affect real rows')
        rows = int(tags[0])
    else:
        # Fixed #106 literals contain no newlines. CSV parsing still handles commas/quotes.
        content = '\n'.join(line for line in text.splitlines() if not line.startswith(('Timing is ', 'Time: ')))
        rows = max(0, len(list(csv.reader(io.StringIO(content)))) - 1)
    return float(times[0]), rows


class Experiment:
    def __init__(self, output):
        self.output = output
        self.token = uuid4().hex
        self.container = None
        self.image = None
        self.commands = []
        self.samples = []
        self.trials = []

    def command(self, args, *, data=None, timeout=180):
        # URLs passed to CLI contain only an ephemeral localhost port and trust-auth user, no passwords.
        self.commands.append(args)
        process = subprocess.run(args, input=data, text=True, capture_output=True, timeout=timeout, cwd=ROOT)
        logs = self.output / 'commands'
        logs.mkdir(exist_ok=True)
        with gzip.open(logs / f'{len(self.commands):04d}.log.gz', 'wt') as log:
            log.write(process.stdout + '\n' + process.stderr)
        if process.returncode: raise RuntimeError('Experiment command failed; see commands.json and failure.json')
        return process.stdout

    def sql(self, database, sql):
        return self.command(['docker','exec','-i', self.container, 'psql','-X','-qAt','-v','ON_ERROR_STOP=1',
                             '-U','demo_admin','-d',database], data=sql)

    def clone(self, name):
        self.sql('postgres', f'CREATE DATABASE {name} TEMPLATE benchmark_seed;')

    def fingerprint(self, database):
        return self.sql(database, "SELECT json_build_object('records', (SELECT count(*) FROM public.records), 'groups', (SELECT count(*) FROM public.groups), 'heap_bytes', pg_relation_size('public.records') + pg_relation_size('public.groups'), 'checksum', (SELECT md5(string_agg(md5(r::text), '' ORDER BY id)) FROM public.records r), 'indexes', (SELECT json_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE schemaname='public'));" )

    def wal(self, database):
        try: return self.sql(database, 'SELECT pg_current_wal_insert_lsn();').strip()
        except RuntimeError: return None

    def operation(self, database, kind, query_id, query, folder, mode, variant, repeat):
        before = self.wal(database)
        args = ['docker','exec','-i',self.container,'psql','-X','--csv','-P','pager=off','-P','footer=off','-v','ON_ERROR_STOP=1','-U','demo_admin','-d',database]
        self.commands.append(args)
        start = time.perf_counter()
        try:
            process = subprocess.run(args, input='\\timing on\n' + query.rstrip(';') + ';\n', text=True,
                                     capture_output=True, timeout=180)
        except subprocess.TimeoutExpired as error:
            def text(value): return value.decode(errors='replace') if isinstance(value, bytes) else value or ''
            with gzip.open(folder / (query_id + '.log.gz'), 'wt') as log:
                log.write(text(error.stdout) + '\n' + text(error.stderr))
            save(folder / 'error.json', dict(kind='timeout', query_id=query_id, completed=False))
            raise
        wall = time.perf_counter() - start
        with gzip.open(folder / (query_id + '.log.gz'), 'wt') as log: log.write(process.stdout + '\n' + process.stderr)
        if process.returncode:
            save(folder/'error.json',dict(kind='sql_error',query_id=query_id,exit_code=process.returncode,completed=False))
            raise RuntimeError('Measured operation failed; raw operation log retained')
        ms, rows = parse_psql(process.stdout, kind)
        after = self.wal(database)
        wal_bytes = None
        if before is not None and after is not None:
            wal_bytes = int(self.sql(database, f"SELECT pg_wal_lsn_diff('{after}','{before}')::bigint;"))
        sample = dict(mode=mode, variant=variant, repeat=repeat, query_id=query_id, kind=kind,
                      rows=rows, psql_ms=ms, wall_seconds=wall, wal_bytes=wal_bytes)
        self.samples.append(sample)
        save(self.output / 'samples.json', self.samples)

    def cli(self, database, args, log_name):
        self.commands.append([str(CLI)] + args)
        env = os.environ.copy()
        env['INDEXBUDGET_DATABASE_URL'] = f'jdbc:postgresql://127.0.0.1:{self.port}/{database}?user=demo_admin'
        process = subprocess.run([str(CLI)] + args, env=env, text=True, capture_output=True, cwd=ROOT, timeout=180)
        (self.output / log_name).write_text(process.stdout + '\n' + process.stderr)
        if process.returncode not in (0, 3): raise RuntimeError('CLI did not produce a usable result')
        return process.returncode

    def run(self, repetitions, observation_seconds):
        image = 'indexbudget-benchmark-' + self.token
        print('Building owned PostgreSQL/HypoPG image', flush=True)
        self.command(['docker','build','-t',image,'demo/postgres'])
        self.image = image
        self.container = self.command(['docker','run','--rm','-d','--label','indexbudget.benchmark='+self.token,
            '--cpus','2','--memory','2g','--shm-size','256m','-p','127.0.0.1::5432',
            '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_USER=demo_admin', '-e','POSTGRES_DB=indexbudget',
            '-e','INDEXBUDGET_READER_PASSWORD=',image,
            '-c','autovacuum=off','-c','max_parallel_workers_per_gather=0']).strip()
        deadline = time.monotonic() + 60
        while True:
            try: self.sql('indexbudget','SELECT 1;'); break
            except RuntimeError:
                if time.monotonic() > deadline: raise
                time.sleep(.2)
        self.port = self.command(['docker','port',self.container,'5432/tcp']).strip().rsplit(':',1)[1]
        # The image does not COPY seed files; initialize this owned database explicitly.
        self.sql('indexbudget', (ROOT / 'demo/postgres/init/001-seed.sql').read_text())
        self.sql('postgres','ALTER DATABASE indexbudget RENAME TO benchmark_seed;')
        self.initial = self.fingerprint('benchmark_seed')
        save(self.output / 'environment.json', dict(postgres=self.sql('benchmark_seed','SELECT version();'),
            hypopg=self.sql('benchmark_seed',"SELECT extversion FROM pg_extension WHERE extname='hypopg';"),
            settings=self.sql('benchmark_seed',"SELECT json_object_agg(name,setting) FROM pg_settings WHERE name IN ('shared_buffers','work_mem','autovacuum','max_parallel_workers_per_gather','full_page_writes','wal_compression','synchronous_commit');"),
            code_revision=self.command(['git','rev-parse','HEAD']).strip(), seed_sha256=hashlib.sha256((ROOT/'demo/postgres/init/001-seed.sql').read_bytes()).hexdigest(),
            cpus=2, memory='2g', clients=1, parallelism=1, transaction='one committed statement per connection'))
        save(self.output/'operations.json', dict(read=self.operations(False), mixed=self.operations(True)))
        self.clone('observation')
        source = str(uuid4())
        observed_start = time.monotonic()
        for label in ('before','after'):
            if label == 'after':
                for kind, id, sql in self.operations(True):
                    folder = self.output/'observation-operations'; folder.mkdir(exist_ok=True)
                    self.operation('observation',kind,id,sql,folder,'observation','baseline',0)
                # Let writer backends publish shared counters; production collector never forces it.
                print(f'Waiting for the fixed {observation_seconds}s observation window and publication', flush=True)
                time.sleep(max(2, observation_seconds - (time.monotonic() - observed_start)))
            self.cli('observation',['table-stats','snapshot','--source-id',source,'--output-dir',str(self.output/label)], label+'-capture.log')
        args = [str(ROOT/'demo/scenarios/workload.json'),str(self.output/'before/snapshot-1.json'),str(self.output/'after/snapshot-1.json'),str(self.output/'workload.json')]
        self.command(['./gradlew','--no-daemon','benchmarkObservedWorkload','--args='+' '.join('\"'+a.replace('\\','\\\\').replace('\"','\\\"')+'\"' for a in args)],timeout=300)
        self.clone('analysis')
        self.cli('analysis',['analyze','--workload',str(self.output/'workload.json'),'--output-dir',str(self.output/'recommendation')], 'analysis.log')
        report = json.loads((self.output/'recommendation/report.json').read_text())
        if report['status'] not in ('completed','partial') or report.get('selected') is None: raise ValueError('No recommendation')
        self.ddl = (self.output/'recommendation/indexes.sql').read_text()
        self.selected = report['selected_indexes']
        save(self.output/'identity.json',dict(run_id=report['run_id'], input_hash=report['input_hash'],
            selected_ids=report['selected']['candidate_ids'], indexes_sql_sha256=hashlib.sha256(self.ddl.encode()).hexdigest()))
        for mode in ('read', 'mixed'):
            for repeat in range(repetitions):
                for variant in (('baseline','selected') if repeat % 2 == 0 else ('selected','baseline')):
                    self.trial(mode,variant,repeat)
        measured = [s for s in self.samples if s['mode'] != 'observation']
        save(self.output/'summary.json',dict(schema_version=1,status='completed',trials=self.trials, summary=summarize(measured), per_query_summary=summarize(measured, per_query=True),
            wal_scope='cluster-wide insert LSN in this owned container; setup/index DDL excluded, background WAL not attributed causally',
            latency_scope='psql timing includes query execution and result handling; TPS/rows per second use the entire measured trial, including WAL probes, logs, docker exec and connection startup',
            limitations=['Single sequential client, short fixed transaction count; not a production concurrency benchmark.',
                'Fresh physical database clone before each variant/repeat; shared OS cache is not evicted.',
                'Read-only deterministic warmup; no DML warmup or rollback restoration.',
                'Observed write profile is from one measured observation pass; script weights are not calls/hour.',
                'Synthetic dataset; planner cost, write proxy and measured metrics are distinct. No gain is guaranteed.']))

    def operations(self, mixed):
        workload = json.loads((ROOT/'demo/scenarios/workload.json').read_text())
        reads = [('SELECT',q['id'],q['sql']) for q in workload['queries'] if q['sql'].lstrip().upper().startswith('SELECT')]
        writes = [(q['sql'].split()[0].upper(),q['id'],q['sql']) for q in workload['queries'] if not q['sql'].lstrip().upper().startswith('SELECT')]
        insert = "INSERT INTO public.records (id,group_id,value,created_at,payload) SELECT i,42,7,timestamp '2026-01-03 08:00:00','benchmark-only' FROM generate_series(200001,200100) i"
        return reads + ([('INSERT','insert_records',insert)] + writes if mixed else [])

    def trial(self, mode, variant, repeat):
        name = 'trial_' + uuid4().hex
        folder = self.output / f'{mode}-{repeat}-{variant}'; folder.mkdir()
        self.clone(name)
        if self.fingerprint(name) != self.initial: raise ValueError('Initial data/index fingerprint drift')
        print(f'{mode}: repeat {repeat + 1}, {variant}; fresh physical clone', flush=True)
        if variant == 'selected': self.sql(name,self.ddl)
        indexes = json.loads(self.sql(name,"SELECT json_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE schemaname='public';"))
        baseline_indexes = json.loads(self.initial)['indexes']
        expected_names = sorted(c['ddl'].split(' ON ')[0].split('INDEX ',1)[1].strip('\"') for c in self.selected) if variant == 'selected' else []
        actual_names = json.loads(self.sql(name, "SELECT COALESCE(json_agg(indexname ORDER BY indexname) FILTER (WHERE indexname LIKE 'ib_%'),'[]'::json) FROM pg_indexes WHERE schemaname='public';"))
        if sorted(actual_names) != expected_names: raise ValueError('Physical recommendation IDs differ')
        if len(indexes) != len(baseline_indexes) + (len(self.selected) if variant=='selected' else 0): raise ValueError('Wrong physical index set')
        self.sql(name,'CHECKPOINT;')
        for _, _, query in self.operations(False):
            self.command(['docker','exec','-i',self.container,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','demo_admin','-d',name], data='\\o /dev/null\n'+query, timeout=180)
        measured_start = time.perf_counter()
        for kind,id,query in self.operations(mode == 'mixed'): self.operation(name,kind,id,query,folder,mode,variant,repeat)
        exposure = time.perf_counter() - measured_start
        for sample in self.samples:
            if (sample['mode'], sample['variant'], sample['repeat']) == (mode,variant,repeat): sample['exposure_seconds'] = exposure
        save(self.output/'samples.json', self.samples)
        trial = dict(exposure_seconds=exposure,mode=mode,variant=variant,repeat=repeat, initial_fingerprint=json.loads(self.initial), indexes=indexes,
            physical_sizes=json.loads(self.sql(name,"SELECT json_build_object('database_bytes',pg_database_size(current_database()),'indexes',(SELECT json_agg(json_build_object('name',indexrelid::regclass::text,'bytes',pg_relation_size(indexrelid))) FROM pg_index WHERE indrelid IN ('public.records'::regclass,'public.groups'::regclass)));")))
        self.trials.append(trial); save(self.output/'trials.json',self.trials)
        self.sql('postgres',f'DROP DATABASE {name};')

    def cleanup(self):
        if self.container:
            label = self.command(['docker','inspect','-f','{{index .Config.Labels "indexbudget.benchmark"}}',self.container]).strip()
            if label != self.token: raise ValueError('Cleanup ownership mismatch')
            self.command(['docker','rm','-f',self.container])
        if self.image:
            self.command(['docker','image','rm',self.image])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--repetitions',type=int,default=3)
    parser.add_argument('--observation-seconds',type=int,default=60)
    args = parser.parse_args()
    if args.output.exists() or args.repetitions < 3 or not 5 <= args.observation_seconds <= 3600: parser.error('New output path and >=3 repetitions required')
    args.output.mkdir(parents=True)
    experiment = Experiment(args.output.resolve())
    try: experiment.run(args.repetitions, args.observation_seconds)
    except BaseException as error:
        save(experiment.output/'failure.json',dict(status='failed',type=type(error).__name__,message=str(error)))
        raise
    finally:
        try:
            experiment.cleanup()
            save(experiment.output/'cleanup.json',dict(status='completed',owned_container=experiment.container,image=experiment.image))
        except BaseException as error:
            save(experiment.output/'cleanup.json',dict(status='failed',type=type(error).__name__))
            raise
        finally:
            save(experiment.output/'commands.json',experiment.commands)

if __name__ == '__main__': main()
