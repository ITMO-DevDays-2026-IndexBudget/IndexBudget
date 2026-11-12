import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location("frontend_" + name, Path(__file__).parents[1] / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


install = module("install")
launch = module("launch")


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.legacy = self.root / "frontend/.local"
        (self.legacy / "reports/one").mkdir(parents=True)
        (self.legacy / "reports/one/report.html").write_text("Сохранённый отчёт")
        (self.legacy / "reports/one/report.json").write_text('{"run_id":"one"}')
        self.shared, self.backup = self.root / "shared", self.root / "frontend/.local.backup"

    def tearDown(self):
        self.temp.cleanup()

    def test_copy_verify_switch_and_idempotent_repeat(self):
        expected = install.file_manifest(self.legacy)
        self.assertEqual(install.migrate_data(self.legacy, self.shared, self.backup), expected)
        self.assertTrue(self.legacy.is_symlink())
        self.assertEqual(install.file_manifest(self.shared), expected)
        self.assertEqual(install.file_manifest(self.backup), expected)
        (self.legacy / "reports/one/indexes.sql").write_text("new report after deploy")
        self.assertTrue((self.shared / "reports/one/indexes.sql").exists())
        self.assertFalse((self.backup / "reports/one/indexes.sql").exists())
        self.assertEqual(install.migrate_data(self.legacy, self.shared, self.backup), install.file_manifest(self.shared))

    def test_conflicting_nonempty_history_is_never_merged(self):
        self.shared.mkdir()
        (self.shared / "important.txt").write_text("preserve")
        with self.assertRaisesRegex(RuntimeError, "слияние"):
            install.migrate_data(self.legacy, self.shared, self.backup)
        self.assertEqual((self.shared / "important.txt").read_text(), "preserve")
        self.assertFalse(self.legacy.is_symlink())
        self.assertFalse(self.backup.exists())

    def test_recover_interruption_between_rename_and_symlink(self):
        import shutil
        shutil.copytree(self.legacy, self.shared)
        self.legacy.rename(self.backup)
        install.migrate_data(self.legacy, self.shared, self.backup)
        self.assertTrue(self.legacy.is_symlink())
        self.assertEqual(install.file_manifest(self.shared), install.file_manifest(self.backup))

    def test_symlinks_inside_history_are_not_followed(self):
        (self.legacy / "external").symlink_to(self.root / "outside")
        with self.assertRaisesRegex(RuntimeError, "ссылка"):
            install.migrate_data(self.legacy, self.shared, self.backup)
        self.assertFalse(self.shared.exists())


class LauncherTests(unittest.TestCase):
    def test_cli_and_frontend_are_pinned_even_when_current_switches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            old = root / "releases/old"
            old.mkdir(parents=True)
            metadata = {"java_home": "/jdk/25", "cli_path": "/cli/releases/old/app/bin/indexbudget",
                        "data_dir": str(root / "shared"), "revision": "old-sha", "image": "db:old",
                        "db_port": 55433, "compose_project": "indexbudget-frontend", "host": "127.0.0.1", "port": 3000}
            (old / "metadata.json").write_text(json.dumps(metadata))
            (root / "current").symlink_to(old)
            frontend, args, env = launch.launch_configuration(root, {"PATH": "/usr/bin", "FRONTEND_CLI": "wrong"})
            (root / "current").unlink()
            (root / "current").symlink_to(root / "releases/new")
            self.assertEqual(frontend, old / "source/frontend")
            self.assertIn(str(old / "source/frontend/server.py"), args)
            self.assertEqual(env["FRONTEND_CLI"], metadata["cli_path"])
            self.assertEqual(env["FRONTEND_DATA_DIR"], metadata["data_dir"])
            self.assertEqual(env["FRONTEND_REVISION"], "old-sha")
            self.assertEqual(env["COMPOSE_PROJECT_NAME"], "indexbudget-frontend")

    def test_unit_paths_quote_spaces_and_specifiers(self):
        unit = install.unit_files(Path('/tmp/a b%$"'))[install.SERVICE]
        self.assertIn('%%$$\\"', unit)
        self.assertIn('TimeoutStopSec=infinity', unit)


class FakeCommands:
    def __init__(self, root, units, legacy):
        self.root, self.units, self.legacy = root, units, legacy
        self.active = True
        self.calls = []
        self.failure = None
        self.failed_once = False
        self.enabled = {install.SERVICE: "enabled", install.TIMER: "disabled", install.UPDATE_SERVICE: "static"}

    def __call__(self, args, check=True, **kwargs):
        self.calls.append((args, kwargs))
        out, code = "", 0
        if args[0] == "systemctl":
            action = args[2]
            if action == "show":
                values = {"FragmentPath": str(self.units / install.SERVICE), "DropInPaths": "",
                          "ExecStart": "/usr/bin/python3 /old/server.py --host 0.0.0.0 --port 3000 ;",
                          "WorkingDirectory": str(self.legacy.parent), "ActiveState": "active" if self.active else "inactive",
                          "Environment": 'FRONTEND_DB_IMAGE=db:old FRONTEND_DB_PORT=55433 PRIVATE_SECRET=do-not-copy'}
                out = values[args[5]]
            elif action == "is-active":
                code = 1  # Timer initially disabled.
            elif action == "is-enabled":
                out = self.enabled[args[3]]
                code = 0 if out == "enabled" else 1
            elif action == "stop" and args[3] == install.SERVICE:
                self.active = False
            elif action == "start" and args[3] == install.SERVICE:
                self.active = True
            elif action == "enable" and "--now" in args and self.failure == "timer" and not self.failed_once:
                self.failed_once = True
                raise subprocess.CalledProcessError(1, args)
        elif args[1].endswith("update.py"):
            self.assert_inherited_lock(args, kwargs)
            if "--prepare-only" in args:
                if self.failure == "prepare":
                    raise subprocess.CalledProcessError(1, args, output="preflight failed")
            else:
                candidate = self.root / "releases/new"
                candidate.mkdir(parents=True, exist_ok=True)
                config = json.loads((self.root / "config.json").read_text())
                (candidate / "metadata.json").write_text(json.dumps({**config, "image": "db:new"}))
                current = self.root / "current"
                current.unlink(missing_ok=True)
                current.symlink_to(candidate)
                (self.root / "deployed.json").write_text('{"revision":"new"}')
                self.active = True
                if self.failure == "deploy":
                    raise subprocess.CalledProcessError(1, args)
        result = subprocess.CompletedProcess(args, code, stdout=out, stderr="")
        if check and code:
            raise subprocess.CalledProcessError(code, args, output=out)
        return result

    @staticmethod
    def assert_inherited_lock(args, kwargs):
        fd = int(args[args.index("--lock-fd") + 1])
        if kwargs.get("pass_fds") != (fd,):
            raise AssertionError("Updater must inherit installation lock")


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve() / "deployment"
        self.units = Path(self.temp.name) / "units"
        self.units.mkdir()
        self.old_unit = "[Service]\nExecStart=/old/server.py\n"
        (self.units / install.SERVICE).write_text(self.old_unit)
        self.legacy = self.root / "frontend/.local"
        (self.legacy / "reports/run").mkdir(parents=True)
        (self.legacy / "reports/run/report.html").write_text("retained")
        (self.legacy.parent / "compose.yaml").write_text("services: {}")
        self.source = Path(self.temp.name) / "source"
        self.source.mkdir()
        for name in ("update.py", "launch.py", "install.py"):
            (self.source / name).write_text("new script")
        self.commands = FakeCommands(self.root, self.units, self.legacy)
        self.installer = install.Installer(self.root, self.source, self.units, Path(self.temp.name) / "runtime", self.commands)
        self.installer.verify_service = lambda port: None

    def tearDown(self):
        self.temp.cleanup()

    def test_initial_install_preserves_reports_and_enables_timer_after_update(self):
        expected = install.file_manifest(self.legacy)
        self.installer.install({"cli_root": str(Path(self.temp.name) / "cli")})
        self.assertEqual(install.file_manifest(self.root / "shared"), expected)
        self.assertTrue(self.legacy.is_symlink())
        self.assertFalse(self.installer.journal.exists())
        actions = [args for args, _ in self.commands.calls]
        prepare = next(i for i, args in enumerate(actions) if "--prepare-only" in args)
        stop = next(i for i, args in enumerate(actions) if args[:4] == ["systemctl", "--user", "stop", install.SERVICE])
        deploy = next(i for i, args in enumerate(actions) if len(args) > 1 and args[1].endswith("update.py") and "--prepare-only" not in args)
        enable = next(i for i, args in enumerate(actions) if args[:4] == ["systemctl", "--user", "enable", "--now"])
        self.assertLess(prepare, stop)
        self.assertLess(stop, deploy)
        self.assertLess(deploy, enable)
        self.assertNotIn("PRIVATE_SECRET", (self.root / "config.json").read_text())

    def test_preflight_failure_restores_config_scripts_without_stopping_service(self):
        self.commands.failure = "prepare"
        old_config = {"cli_root": "/old-cli", "data_dir": str(self.root / "shared"), "host": "127.0.0.1",
                      "port": 3000, "db_port": 55433, "compose_project": "indexbudget-frontend", "java_home": "/old-jdk"}
        (self.root / "config.json").write_text(json.dumps(old_config))
        (self.root / "bin").mkdir()
        (self.root / "bin/update.py").write_text("old script")
        with self.assertRaises(subprocess.CalledProcessError):
            self.installer.install({"port": 3001})
        self.assertEqual(json.loads((self.root / "config.json").read_text()), old_config)
        self.assertEqual((self.root / "bin/update.py").read_text(), "old script")
        self.assertFalse(any(args[:4] == ["systemctl", "--user", "stop", install.SERVICE] for args, _ in self.commands.calls))
        self.assertTrue(self.commands.active)

    def test_repeated_install_keeps_shared_history_and_existing_settings(self):
        self.installer.install({"port": 3300})
        (self.root / "shared/reports/run/later.json").write_text("new report")
        expected = install.file_manifest(self.root / "shared")
        self.installer.install()
        self.assertEqual(install.file_manifest(self.root / "shared"), expected)
        self.assertEqual(json.loads((self.root / "config.json").read_text())["port"], 3300)
        self.assertEqual(len(list(self.legacy.parent.glob(".local.pre-autodeploy-*"))), 1)
        self.assertTrue(self.legacy.is_symlink())

    def test_bootstrap_failure_restores_legacy_unit_image_and_shared_history(self):
        self.commands.failure = "deploy"
        with self.assertRaises(subprocess.CalledProcessError):
            self.installer.install()
        self.assertEqual((self.units / install.SERVICE).read_text(), self.old_unit)
        self.assertEqual((self.legacy / "reports/run/report.html").read_text(), "retained")
        self.assertTrue(self.legacy.is_symlink())
        self.assertFalse((self.root / "current").exists())
        self.assertTrue(self.commands.active)
        compose = [kwargs for args, kwargs in self.commands.calls if args[0] == "docker"]
        self.assertEqual(compose[-1]["env"]["FRONTEND_DB_IMAGE"], "db:old")
        self.assertFalse(self.installer.journal.exists())

    def test_legacy_rollback_checks_original_port_when_new_port_differs(self):
        self.commands.failure = "deploy"
        checked = []
        self.installer.verify_service = checked.append
        with self.assertRaises(subprocess.CalledProcessError):
            self.installer.install({"port": 3001})
        self.assertEqual(checked, [3000])
        self.assertEqual(install.legacy_http_port("python3 server.py"), 8090)
        self.assertEqual(install.legacy_http_port("python3 server.py --port=3012"), 3012)

    def test_failure_after_deploy_restores_previous_managed_release(self):
        old = self.root / "releases/old"
        old.mkdir(parents=True)
        old_config = {"cli_root": "/old-cli", "data_dir": str(self.root / "shared"), "host": "127.0.0.1",
                      "port": 3000, "db_port": 55433, "compose_project": "indexbudget-frontend", "java_home": "/old-jdk"}
        (old / "metadata.json").write_text(json.dumps({**old_config, "image": "db:old-pinned"}))
        (self.root / "current").symlink_to(old)
        (self.root / "config.json").write_text(json.dumps(old_config))
        (self.root / "deployed.json").write_text('{"revision":"old"}')
        self.commands.failure = "timer"
        with self.assertRaises(subprocess.CalledProcessError):
            self.installer.install()
        self.assertEqual((self.root / "current").resolve(), old)
        self.assertEqual(json.loads((self.root / "deployed.json").read_text()), {"revision": "old"})
        self.assertEqual(json.loads((self.root / "config.json").read_text()), old_config)
        self.assertEqual((self.legacy / "reports/run/report.html").read_text(), "retained")
        compose = [kwargs for args, kwargs in self.commands.calls if args[0] == "docker"]
        self.assertEqual(compose[-1]["env"]["FRONTEND_DB_IMAGE"], "db:old-pinned")

    def test_failed_stop_during_rollback_preserves_journal_and_does_not_touch_database(self):
        self.commands.failure = "timer"
        real_run = self.commands.__call__

        def fail_second_stop(args, **kwargs):
            if args[:4] == ["systemctl", "--user", "stop", install.SERVICE] and self.commands.failed_once:
                return subprocess.CompletedProcess(args, 1, stdout="", stderr="failed stop")
            return real_run(args, **kwargs)

        self.installer.execute = fail_second_stop
        with self.assertRaisesRegex(RuntimeError, "не остановлен"):
            self.installer.install()
        self.assertTrue(self.installer.journal.exists())
        self.assertFalse(any(args[0] == "docker" for args, _ in self.commands.calls))
        self.assertTrue(self.commands.active)
        # Следующий install сначала восстанавливает оставленный журнал, затем делает новую попытку.
        self.installer.execute = self.commands
        self.commands.failure = None
        self.installer.install()
        self.assertFalse(self.installer.journal.exists())
        self.assertEqual((self.legacy / "reports/run/report.html").read_text(), "retained")


if __name__ == "__main__":
    unittest.main()
