"""Транзакция обновления на реальных файлах, без systemd/Docker и сети."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / 'update.py'
SPEC = importlib.util.spec_from_file_location('frontend_update_under_test', MODULE_PATH)
update = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(update)

A, B, C = 'a' * 40, 'b' * 40, 'c' * 40


def contents(directory):
    """Независимый снимок: имена файлов и хэши, не реализация tree_hash updater."""
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in directory.rglob('*') if p.is_file()}


class FakeDeployer(update.Deployer):
    def __init__(self, root):
        super().__init__(root)
        self.commands = []
        self.service_active = False
        self.image = None
        self.bad_health = set()
        self.fail_tests = False
        self.after_preflight = None

    def run(self, arguments, *, env=None, cwd=None, timeout=600, check=True):
        arguments = list(arguments)
        self.commands.append((arguments, dict(env or {}), cwd))
        code, output = 0, ''
        if arguments[:3] == ['systemctl', '--user', 'is-active']:
            code = 0 if self.service_active else 3
        elif arguments[:3] == ['systemctl', '--user', 'stop']:
            self.service_active = False
        elif arguments[:3] == ['systemctl', '--user', 'start']:
            self.service_active = True
        elif arguments[:3] == ['systemctl', '--user', 'show']:
            output = 'active\n' if self.service_active else 'inactive\n'
        elif arguments[:3] == ['python3', '-m', 'unittest'] and self.fail_tests:
            code = 1
        elif arguments[:2] == ['docker', 'compose'] and 'up' in arguments:
            self.image = env['FRONTEND_DB_IMAGE']
        result = subprocess.CompletedProcess(arguments, code, output)
        if check and code:
            raise RuntimeError('Simulated command failure')
        return result

    def healthy(self, metadata):
        return metadata['revision'] not in self.bad_health

    def preflight(self, release, metadata):
        super().preflight(release, metadata)
        if self.after_preflight:
            self.after_preflight()

    def mutations(self):
        return [arguments for arguments, _, _ in self.commands
                if arguments[:3] in (['systemctl', '--user', 'stop'],
                                     ['systemctl', '--user', 'start'])
                or arguments[:2] == ['docker', 'compose'] and 'up' in arguments]


class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'frontend'
        self.root.mkdir()
        self.cli = self.base / 'cli'
        self.cli.mkdir()
        self.data = self.root / 'shared'
        self.java = self.base / 'jdk'
        (self.java / 'bin').mkdir(parents=True)
        (self.java / 'bin/java').write_text('jdk fixture')
        update.write_json(self.root / 'config.json', {
            'cli_root': str(self.cli), 'data_dir': str(self.data), 'java_home': str(self.java),
            'host': '0.0.0.0', 'port': 3000, 'db_port': 55433,
            'compose_project': 'indexbudget-frontend',
        })
        self.cli_release(A)
        self.set_cli(A)
        self.deployer = FakeDeployer(self.root)
        self.addCleanup(patch.stopall)
        patch.object(update.time, 'sleep', return_value=None).start()

    def cli_release(self, revision):
        release = self.cli / 'releases' / revision
        frontend = release / 'source/frontend'
        (frontend / 'tests').mkdir(parents=True)
        (frontend / 'static').mkdir()
        (frontend / 'server.py').write_text('# trusted server ' + revision)
        (frontend / 'compose.yaml').write_text('services: {}\n')
        (frontend / 'static/app.js').write_text('// trusted JS ' + revision)
        # Даже если в исходной копии случайно лежат runtime files, stage их не переносит.
        (frontend / '.local').mkdir()
        (frontend / '.local/unrelated').write_text('must not be copied')
        (release / 'source/demo/postgres').mkdir(parents=True)
        (release / 'source/demo/postgres/Dockerfile').write_text('FROM postgres:16\n# ' + revision)
        (release / 'app/bin').mkdir(parents=True)
        launcher = release / 'app/bin/indexbudget'
        launcher.write_text('#!/bin/sh\nexit 0\n')
        launcher.chmod(0o755)
        (release / 'REVISION').write_text(revision + '\n')
        return release

    def set_cli(self, revision):
        update.switch_link(self.cli / 'current', self.cli / 'releases' / revision)

    def preserve_report(self):
        report = self.data / 'reports' / 'old-report'
        report.mkdir(parents=True)
        (report / 'report.html').write_text('<html>Old report remains at the same URL</html>')
        (report / 'report.json').write_text('{"run_id": "old-report"}')
        (report / 'indexes.sql').write_text('-- saved recommendation\n')
        return contents(self.data)

    def test_a_to_b_pins_cli_image_and_preserves_report_files(self):
        self.deployer.update()
        first = self.deployer.current.resolve()
        before = self.preserve_report()
        self.cli_release(B)
        self.set_cli(B)
        self.deployer.commands.clear()
        self.deployer.update()
        second = self.deployer.current.resolve()
        metadata = update.read_json(second / 'metadata.json')
        self.assertNotEqual(first, second)
        self.assertEqual(metadata['revision'], B)
        self.assertEqual(metadata['cli_path'], str(self.cli / 'releases' / B / 'app/bin/indexbudget'))
        self.assertEqual(self.deployer.image, metadata['image'])
        self.assertEqual(contents(self.data), before)
        self.assertEqual((second / 'source/frontend/.local').resolve(), self.data)
        self.assertFalse((second / 'source/frontend/.local/unrelated').exists())
        self.assertFalse(self.deployer.pending.exists())
        self.assertEqual(update.read_json(self.root / 'deployed.json')['revision'], B)
        mutations = self.deployer.mutations()
        self.assertEqual(mutations[0][:3], ['systemctl', '--user', 'stop'])
        self.assertEqual(mutations[1][:2], ['docker', 'compose'])
        self.assertEqual(mutations[2][:3], ['systemctl', '--user', 'start'])

    def test_same_verified_revision_is_noop(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        deployed = (self.root / 'deployed.json').read_bytes()
        self.deployer.commands.clear()
        self.deployer.update()
        self.assertEqual(self.deployer.mutations(), [])
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual((self.root / 'deployed.json').read_bytes(), deployed)
        self.assertFalse(any(args[:3] == ['python3', '-m', 'unittest']
                             for args, _, _ in self.deployer.commands))

    def test_failed_tests_leave_previous_process_database_and_reports_untouched(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        image = self.deployer.image
        before = self.preserve_report()
        self.cli_release(B)
        self.set_cli(B)
        self.deployer.commands.clear()
        self.deployer.fail_tests = True
        with self.assertRaisesRegex(RuntimeError, 'command failure'):
            self.deployer.update()
        self.assertEqual(self.deployer.mutations(), [])
        self.assertTrue(self.deployer.service_active)
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual(self.deployer.image, image)
        self.assertEqual(contents(self.data), before)
        self.assertFalse(self.deployer.pending.exists())

    def test_false_health_rolls_back_code_image_and_keeps_reports(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        image = self.deployer.image
        before = self.preserve_report()
        self.cli_release(B)
        self.set_cli(B)
        self.deployer.bad_health.add(B)
        with self.assertRaisesRegex(RuntimeError, 'health failed'):
            self.deployer.update()
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual(self.deployer.image, image)
        self.assertTrue(self.deployer.service_active)
        self.assertEqual(contents(self.data), before)
        self.assertEqual(update.read_json(self.root / 'deployed.json')['revision'], A)
        self.assertFalse(self.deployer.pending.exists())

    def test_pending_recovery_runs_before_preparing_newer_cli(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        before = self.preserve_report()
        self.cli_release(B)
        self.set_cli(B)
        source, interrupted, metadata = self.deployer.snapshot()
        self.deployer.stage(source, interrupted, metadata)
        update.write_json(self.deployer.pending, {'previous': str(previous), 'candidate': str(interrupted),
                                                   'previous_active': True})
        update.switch_link(self.deployer.current, interrupted)
        self.deployer.image = metadata['image']
        self.cli_release(C)
        self.set_cli(C)
        self.deployer.commands.clear()
        self.deployer.update()
        up_images = [env['FRONTEND_DB_IMAGE'] for args, env, _ in self.deployer.commands
                     if args[:2] == ['docker', 'compose'] and 'up' in args]
        self.assertEqual(up_images[0], update.read_json(previous / 'metadata.json')['image'])
        self.assertEqual(up_images[-1], update.read_json(self.deployer.current.resolve() / 'metadata.json')['image'])
        first_stop = next(i for i, (args, _, _) in enumerate(self.deployer.commands)
                          if args[:3] == ['systemctl', '--user', 'stop'])
        first_test = next(i for i, (args, _, _) in enumerate(self.deployer.commands)
                          if args[:3] == ['python3', '-m', 'unittest'])
        self.assertLess(first_stop, first_test)
        self.assertEqual(update.read_json(self.root / 'deployed.json')['revision'], C)
        self.assertEqual(contents(self.data), before)

    def test_cli_changed_during_preflight_defers_without_stopping(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        self.cli_release(B)
        self.cli_release(C)
        self.set_cli(B)
        self.deployer.after_preflight = lambda: self.set_cli(C)
        self.deployer.commands.clear()
        self.deployer.update()
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual(self.deployer.mutations(), [])
        self.assertFalse(self.deployer.pending.exists())

    def test_installer_requires_deploy_and_rejects_moving_cli(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        self.cli_release(B)
        self.cli_release(C)
        self.set_cli(B)
        self.deployer.after_preflight = lambda: self.set_cli(C)
        self.deployer.commands.clear()
        with self.assertRaisesRegex(RuntimeError, 'CLI changed'):
            self.deployer.update(require_deploy=True)
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual(self.deployer.mutations(), [])
        self.assertFalse(self.deployer.pending.exists())

    def test_recovery_after_commit_marker_write_restores_observed_revision(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        previous_deployed = update.read_json(self.root / 'deployed.json')
        self.cli_release(B)
        self.set_cli(B)
        source, interrupted, metadata = self.deployer.snapshot()
        self.deployer.stage(source, interrupted, metadata)
        update.write_json(self.deployer.pending, {'previous': str(previous), 'candidate': str(interrupted),
            'previous_active': True, 'previous_deployed': previous_deployed})
        update.switch_link(self.deployer.current, interrupted)
        # Имитируем kill между записью успешного marker и удалением pending journal.
        update.write_json(self.root / 'deployed.json', {'revision': B, 'release': str(interrupted),
            'cli_release': metadata['cli_release'], 'deployed_at': 123.0})
        self.set_cli(A)
        self.deployer.update()
        self.assertEqual(self.deployer.current.resolve(), previous)
        self.assertEqual(update.read_json(self.root / 'deployed.json')['revision'], A)
        self.assertFalse(self.deployer.pending.exists())

    def test_prepare_only_does_not_create_data_or_switch_running_state(self):
        config_before = (self.root / 'config.json').read_bytes()
        self.deployer.update(prepare_only=True)
        self.assertFalse(self.data.exists())
        self.assertFalse(self.deployer.current.exists())
        self.assertFalse(self.deployer.pending.exists())
        self.assertFalse((self.root / 'deployed.json').exists())
        self.assertEqual((self.root / 'config.json').read_bytes(), config_before)
        self.assertEqual(self.deployer.mutations(), [])
        staged = list((self.root / 'releases').iterdir())
        self.assertEqual(len(staged), 1)
        self.assertTrue((staged[0] / 'source/frontend/.local').is_symlink())

    def test_unmanaged_journal_path_is_rejected_before_any_mutation(self):
        self.deployer.update()
        previous = self.deployer.current.resolve()
        outside = self.base / 'unrelated'
        outside.mkdir()
        update.write_json(outside / 'metadata.json', {'do_not_touch': True})
        for previous_path, candidate_path in ((str(outside), str(previous)),
                                              (str(previous), str(outside))):
            with self.subTest(previous=previous_path, candidate=candidate_path):
                update.write_json(self.deployer.pending, {'previous': previous_path,
                    'candidate': candidate_path, 'previous_active': True})
                before = contents(outside)
                self.deployer.commands.clear()
                with self.assertRaisesRegex(ValueError, 'unmanaged'):
                    self.deployer.update()
                self.assertEqual(self.deployer.mutations(), [])
                self.assertEqual(contents(outside), before)
                self.assertTrue(self.deployer.pending.exists())

    def test_update_lock_excludes_concurrent_owner_and_releases_after_error(self):
        with self.assertRaisesRegex(RuntimeError, 'interrupted'):
            with update.update_lock(self.root) as first:
                self.assertTrue(first)
                with update.update_lock(self.root) as second:
                    self.assertFalse(second)
                raise RuntimeError('interrupted')
        with update.update_lock(self.root) as third:
            self.assertTrue(third)

    def test_shared_database_project_is_rejected(self):
        config = update.read_json(self.root / 'config.json')
        config['compose_project'] = 'indexbudget-stand'
        update.write_json(self.root / 'config.json', config)
        with self.assertRaisesRegex(ValueError, 'shared database'):
            update.Deployer(self.root)

    def test_health_requires_true_flags_and_matching_revision(self):
        _, _, metadata = self.deployer.snapshot()
        for body in ({'database': False, 'cli': True}, {'database': True, 'cli': False},
                     {'database': 1, 'cli': True},
                     {'database': True, 'cli': True, 'deployment_revision': B}):
            with self.subTest(body=body), patch.object(update, 'urlopen', return_value=io.BytesIO(json.dumps(body).encode())):
                self.assertFalse(update.Deployer.healthy(self.deployer, metadata))
        for body in ({'database': True, 'cli': True},
                     {'database': True, 'cli': True, 'deployment_revision': A}):
            replies = [io.BytesIO(json.dumps(body).encode()), io.BytesIO(b'[{"id":"demo"}]'),
                       io.BytesIO(b'<html>IndexBudget</html>')]
            with self.subTest(body=body), patch.object(update, 'urlopen', side_effect=replies):
                self.assertTrue(update.Deployer.healthy(self.deployer, metadata))

    def test_health_rejects_malformed_json_shape(self):
        _, _, metadata = self.deployer.snapshot()
        for body in (None, [], 1, 'ready'):
            with self.subTest(body=body), patch.object(update, 'urlopen', return_value=io.BytesIO(json.dumps(body).encode())):
                self.assertFalse(update.Deployer.healthy(self.deployer, metadata))


if __name__ == '__main__':
    unittest.main()
