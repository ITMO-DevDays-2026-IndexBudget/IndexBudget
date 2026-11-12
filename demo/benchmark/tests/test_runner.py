import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch
import tempfile
import subprocess
import gzip
import json

spec = importlib.util.spec_from_file_location('benchmark_runner', Path(__file__).resolve().parents[1] / 'run.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class RunnerTests(unittest.TestCase):
    def test_real_command_tags_required_for_each_write(self):
        for kind, tag in [('INSERT','INSERT 0'),('UPDATE','UPDATE'),('DELETE','DELETE')]:
            self.assertEqual(module.parse_psql(f'Timing is on.\n{tag} 7\nTime: 1.25 ms\n',kind),(1.25,7))
            with self.assertRaises(ValueError): module.parse_psql(f'{tag} 0\nTime: 1 ms\n',kind)
            with self.assertRaises(ValueError): module.parse_psql('ERROR\n',kind)

    def test_csv_reads_and_latency_are_distinct_from_wall_tps(self):
        self.assertEqual(module.parse_psql('Timing is on.\nid,payload\n1,"a,b"\n2,"other"\nTime: 3.2 ms\n','SELECT'),(3.2,2))
        rows=[dict(mode='mixed',variant='selected',kind='UPDATE',rows=4,wall_seconds=2,psql_ms=1,wal_bytes=100),
              dict(mode='mixed',variant='selected',kind='UPDATE',rows=6,wall_seconds=1,psql_ms=2,wal_bytes=200)]
        summary=module.summarize(rows)[0]
        self.assertEqual(summary['calls'],2)
        self.assertAlmostEqual(summary['tps'],2/3)
        self.assertEqual(summary['wal_bytes_per_operation'],150)
        self.assertEqual(summary['wal_bytes_per_row'],30)
        rows[0]['wal_bytes']=None
        self.assertIsNone(module.summarize(rows)[0]['wal_bytes'])
        self.assertIsNone(module.summarize(rows)[0]['wal_bytes_per_operation'])

    def test_cleanup_refuses_to_touch_another_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            run=module.Experiment(Path(tmp));run.container='owned-id'
            with patch.object(run,'command',return_value='foreign-owner') as command:
                with self.assertRaises(ValueError):run.cleanup()
                self.assertEqual(command.call_count,1)
                self.assertEqual(command.call_args.args[0][1],'inspect')

    def test_throughput_counts_entire_trial_once_for_multiple_queries(self):
        rows = [dict(mode='read', variant='baseline', kind='SELECT', query_id=query,
                     repeat=repeat, exposure_seconds=10, rows=2, wall_seconds=.1,
                     psql_ms=1, wal_bytes=0)
                for repeat in range(3) for query in ('a', 'b')]
        self.assertEqual(module.summarize(rows)[0]['tps'], 6 / 30)
        self.assertEqual(module.summarize(rows)[0]['rows_per_second'], 12 / 30)
        self.assertTrue(all(s['tps'] == 3 / 30 for s in module.summarize(rows, per_query=True)))

    def test_cleanup_removes_only_the_recorded_owned_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            run=module.Experiment(Path(tmp));run.container='owned-id'
            with patch.object(run,'command',side_effect=[run.token,'']) as command:
                run.cleanup()
                self.assertEqual(command.call_args.args[0],['docker','rm','-f','owned-id'])

    def test_failed_operation_retains_raw_log_and_no_completed_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);run=module.Experiment(folder);run.container='owned-id'
            with patch.object(run,'wal',return_value=None), patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],1,'','SQL failed')):
                with self.assertRaises(RuntimeError):run.operation('trial','UPDATE','update', 'UPDATE t SET a=1',folder,'mixed','selected',0)
            self.assertEqual(run.samples,[])
            self.assertEqual(json.loads((folder/'error.json').read_text())['kind'],'sql_error')
            with gzip.open(folder/'update.log.gz','rt') as log:self.assertIn('SQL failed',log.read())

    def test_timed_out_operation_preserves_partial_stdout_and_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);run=module.Experiment(folder);run.container='owned-id'
            failure=subprocess.TimeoutExpired([],180,output=b'partial output',stderr=b'timed out')
            with patch.object(run,'wal',return_value=None), patch.object(module.subprocess,'run',side_effect=failure):
                with self.assertRaises(subprocess.TimeoutExpired):run.operation('trial','SELECT','read','SELECT 1',folder,'read','baseline',0)
            self.assertEqual(run.samples,[])
            self.assertEqual(json.loads((folder/'error.json').read_text())['kind'],'timeout')
            with gzip.open(folder/'read.log.gz','rt') as log:self.assertIn('partial output',log.read())

    def test_source_scenario_is_unchanged_and_insert_is_runner_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            run=module.Experiment(Path(tmp))
            self.assertTrue(all(kind=='SELECT' for kind,_,_ in run.operations(False)))
            mixed=run.operations(True)
            self.assertEqual(sum(kind=='INSERT' for kind,_,_ in mixed),1)
            self.assertTrue(any(kind=='UPDATE' for kind,_,_ in mixed))
            self.assertTrue(any(kind=='DELETE' for kind,_,_ in mixed))
