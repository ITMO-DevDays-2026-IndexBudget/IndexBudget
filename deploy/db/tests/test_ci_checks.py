import importlib.util
from pathlib import Path
import subprocess
import unittest


spec = importlib.util.spec_from_file_location("ci_checks", Path(__file__).resolve().parents[1] / "ci_checks.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class DatabaseChecks(unittest.TestCase):
    def results(self, changed="true", changes="success", demo="success", persistence="success"):
        return {
            "changes": {"result": changes, "outputs": {"database": changed}},
            "demo-db": {"result": demo},
            "stand-persistence": {"result": persistence},
        }

    def test_kotlin_only_pr_passes_with_docker_jobs_skipped(self):
        self.assertFalse(ci.affects_database(["src/main/kotlin/Parser.kt", "build.gradle.kts"]))
        self.assertTrue(ci.checks_passed(self.results(changed="false", demo="skipped", persistence="skipped")))

    def test_failed_seed_cannot_pass_via_skipped_dependent_job(self):
        self.assertFalse(ci.checks_passed(self.results(demo="failure", persistence="skipped")))

    def test_persistence_failure_or_cancellation_blocks_gate(self):
        for status in ("failure", "cancelled", "skipped"):
            with self.subTest(status=status):
                self.assertFalse(ci.checks_passed(self.results(persistence=status)))

    def test_detection_failure_or_missing_output_blocks_gate(self):
        self.assertFalse(ci.checks_passed(self.results(changes="failure", demo="skipped", persistence="skipped")))
        self.assertFalse(ci.checks_passed(self.results(changed="")))
        self.assertFalse(ci.checks_passed({}))

    def test_database_changes_require_both_successes(self):
        for path in ("compose.yaml", "demo/postgres/Dockerfile", "deploy/db/migrate.sh", ".github/workflows/demo-db.yml"):
            self.assertTrue(ci.affects_database([path]))
        self.assertTrue(ci.checks_passed(self.results()))

    def test_manual_run_always_selects_database(self):
        self.assertTrue(ci.database_changed("workflow_dispatch", {}))

    def test_pr_uses_merge_base_and_counts_deleted_old_path(self):
        calls = []
        def git(*args):
            calls.append(args)
            return b"ancestor\n" if args[0] == "merge-base" else b"demo/removed.sql\0other/moved.sql\0"
        event = {"pull_request": {"base": {"sha": "base"}, "head": {"sha": "head"}}}
        self.assertTrue(ci.database_changed("pull_request", event, git))
        self.assertEqual(calls[0], ("merge-base", "base", "head"))
        self.assertEqual(calls[1], ("diff", "--name-only", "--no-renames", "-z", "ancestor", "head"))

    def test_unavailable_push_history_runs_checks_instead_of_skipping(self):
        def git(*args):
            raise subprocess.CalledProcessError(128, "git")
        self.assertTrue(ci.database_changed("push", {"before": "old", "after": "new"}, git))


if __name__ == "__main__":
    unittest.main()
