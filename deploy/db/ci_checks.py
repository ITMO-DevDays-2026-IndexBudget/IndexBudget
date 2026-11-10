"""Path selection and fail-closed required check for the database workflow."""
import json
import os
from pathlib import Path
import subprocess
import sys


def affects_database(paths):
    return any(
        path in ("compose.yaml", ".github/workflows/demo-db.yml")
        or path.startswith(("demo/", "deploy/db/"))
        for path in paths
    )

def git_output(*args):
    return subprocess.check_output(["git", *args])


def database_changed(event_name, event, git=git_output):
    if event_name == "workflow_dispatch":
        return True
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        head = event["pull_request"]["head"]["sha"]
    elif event_name == "push":
        base, head = event["before"], event["after"]
    else:
        raise ValueError("Unsupported CI event")
    if base == "0" * 40:
        return True
    try:
        if event_name == "pull_request":
            base = git("merge-base", base, head).decode().strip()
        # No rename detection: a file moved out of a watched directory still counts.
        paths = git("diff", "--name-only", "--no-renames", "-z", base, head).decode().split("\0")
    except subprocess.CalledProcessError:
        # Force-pushed/deleted history must not silently skip verification.
        return True
    return affects_database(paths)


def checks_passed(needs):
    changes = needs.get("changes", {})
    if changes.get("result") != "success":
        return False
    database = changes.get("outputs", {}).get("database")
    if database not in ("true", "false"):
        return False
    expected = "success" if database == "true" else "skipped"
    return all(needs.get(job, {}).get("result") == expected for job in ("demo-db", "stand-persistence"))


def main():
    if sys.argv[1] == "changes":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        changed = database_changed(os.environ["GITHUB_EVENT_NAME"], event)
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"database={str(changed).lower()}\n")
    elif sys.argv[1] == "gate":
        passed = checks_passed(json.loads(os.environ["CHECK_RESULTS"]))
        print("Database checks passed or were not required." if passed else "Required database checks failed, were cancelled, or were unexpectedly skipped.")
        return 0 if passed else 1
    else:
        raise ValueError("Expected changes or gate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
