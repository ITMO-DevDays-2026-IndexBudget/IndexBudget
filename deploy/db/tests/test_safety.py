"""Deployment control-flow checks. These do not replace the real PostgreSQL CI test."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[3]


class DeploymentFailures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="indexbudget-safety-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / "repo"
        (self.repo / "demo/postgres").mkdir(parents=True)
        (self.repo / "demo/postgres/init").mkdir()
        (self.repo / "demo/postgres/init/001-seed.sql").write_text("SELECT 1;\n")
        (self.repo / "deploy/db").mkdir(parents=True)
        (self.repo / "demo/postgres/Dockerfile").write_text("FROM postgres:16.15-bookworm\n")
        shutil.copy(PROJECT / "deploy/db/compose.yaml", self.repo / "deploy/db/compose.yaml")
        self.run_git("init", "-q")
        self.run_git("add", ".")
        self.run_git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                     "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.revision = self.run_git("rev-parse", "HEAD").strip()
        self.root = self.base / "state"
        self.root.mkdir()
        for name in ("releases", "backups"):
            (self.root / name).mkdir()
        subprocess.run(["git", "clone", "--bare", "-q", str(self.repo), str(self.root / "app.git")], check=True)
        (self.root / "db.env").write_text("# Mock only; no database connection\n")
        (self.root / "db.env").chmod(0o600)
        self.root.chmod(0o700)
        binaries = self.base / "bin"
        binaries.mkdir()
        docker = binaries / "docker"
        docker.write_text('''#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
with open(os.environ['CALL_LOG'], 'a') as out:
    out.write(json.dumps(a) + '\\n')
mode = os.environ['FAIL_MODE']
if a[:2] == ['volume', 'inspect']:
    sys.exit(0)
if a[:1] == ['inspect']:
    if '.Config.Image' in a[2]:
        print(os.environ.get('ACTUAL_RELEASE', 'wrong-image') + '|running|healthy')
    else:
        print('unrelated-volume' if mode == 'wrong-volume' else 'indexbudget-stand-pg16-data')
elif 'ps' in a:
    if mode != 'orphan-volume': print('existing-container')
elif 'build' in a and mode == 'build':
    sys.exit(23)
elif 'pg_dump' in a and mode == 'backup':
    sys.exit(24)
''')
        docker.chmod(0o755)
        df = binaries / "df"
        df.write_text("#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nmock 99999999 0 99999999 0%% /\\n'\n")
        df.chmod(0o755)
        self.log = self.base / "calls.jsonl"
        self.env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"], CALL_LOG=str(self.log))

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True)

    def assert_aborts_before_update(self, mode):
        result = subprocess.run(
            ["bash", str(PROJECT / "deploy/db/deploy.sh"), str(self.root), str(self.repo), self.revision],
            env=dict(self.env, FAIL_MODE=mode), capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertFalse(any("up" in call for call in calls), calls)
        self.assertFalse(any(call[:2] == ["volume", "create"] for call in calls), calls)
        self.assertFalse((self.root / "current").exists())
        return calls

    def prepare_current(self):
        trees = [self.run_git("rev-parse", "HEAD:" + path).strip()
                 for path in ("deploy/db", "demo/postgres")]
        release = hashlib.sha256(("\n".join(trees) + "\n").encode()).hexdigest()
        current = self.root / "current"
        current.mkdir()
        (current / "RELEASE").write_text(release + "\n")
        return release

    def deploy(self, **env):
        return subprocess.run(
            ["bash", str(PROJECT / "deploy/db/deploy.sh"), str(self.root), str(self.repo), self.revision],
            env=dict(self.env, FAIL_MODE="none", **env), capture_output=True, text=True,
        )

    def test_matching_healthy_container_is_noop(self):
        release = self.prepare_current()
        result = self.deploy(ACTUAL_RELEASE="indexbudget-stand-postgres:" + release)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertFalse(any("up" in call or "build" in call for call in calls))

    def test_mismatching_container_cannot_be_noop(self):
        self.prepare_current()
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manual recovery required", result.stderr)

    def test_unfinished_other_release_blocks_revert(self):
        release = self.prepare_current()
        (self.root / "pending-release").write_text("failed-b\n")
        result = self.deploy(ACTUAL_RELEASE="indexbudget-stand-postgres:" + release)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unfinished database deployment", result.stderr)
        self.assertFalse(self.log.exists(), "Must stop before any Docker mutations")
        self.assertEqual((self.root / "pending-release").read_text(), "failed-b\n")

    def test_build_failure_does_not_replace_database(self):
        calls = self.assert_aborts_before_update("build")
        self.assertTrue(any("build" in call for call in calls))
        self.assertFalse(any("pg_dump" in call for call in calls))

    def test_seed_is_readable_by_container_uid_but_secrets_remain_private(self):
        self.assert_aborts_before_update("build")
        releases = list((self.root / "releases").iterdir())
        self.assertEqual(len(releases), 1)
        seed = releases[0] / "source/demo/postgres/init"
        self.assertEqual(stat.S_IMODE(seed.stat().st_mode), 0o755)
        self.assertEqual(stat.S_IMODE((seed / "001-seed.sql").stat().st_mode), 0o644)
        self.assertEqual(stat.S_IMODE((self.root / "db.env").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.root.stat().st_mode), 0o700)

    def test_backup_failure_does_not_replace_database(self):
        calls = self.assert_aborts_before_update("backup")
        self.assertTrue(any("pg_dump" in call for call in calls))
        self.assertEqual(list((self.root / "backups").glob("*.dump")), [])

    def test_orphan_volume_requires_manual_recovery(self):
        calls = self.assert_aborts_before_update("orphan-volume")
        self.assertFalse(any("build" in call for call in calls))

    def test_unexpected_volume_is_not_touched(self):
        calls = self.assert_aborts_before_update("wrong-volume")
        self.assertFalse(any("build" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
