#!/usr/bin/env python3
"""Server-pull frontend из успешно собранного CLI-релиза. Только Python stdlib."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from urllib.request import urlopen

SERVICE = "indexbudget-frontend.service"
# Меняет identity при расширении состава bundle, включая уже подготовленные
# прежним updater релизы того же CLI SHA.
BUNDLE_VERSION = 2


def read_json(path):
    return json.loads(Path(path).read_text())


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_json(path, value):
    path = Path(path)
    pending = path.with_name(path.name + ".next")
    with pending.open("w") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    pending.replace(path)
    sync_directory(path.parent)


def switch_link(link, target):
    link = Path(link)
    pending = link.with_name(link.name + ".next")
    pending.unlink(missing_ok=True)
    pending.symlink_to(target, target_is_directory=True)
    pending.replace(link)
    sync_directory(link.parent)


def tree_hash(directory):
    digest = hashlib.sha256()
    for path in sorted(Path(directory).rglob("*")):
        if path.is_symlink():
            raise ValueError("Build context must not contain symlinks")
        if path.is_file():
            digest.update(str(path.relative_to(directory)).encode() + b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def log(message):
    print(time.strftime("%Y-%m-%dT%H:%M:%S%z"), message, flush=True)


@contextmanager
def update_lock(root):
    with (root / "update.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False
        else:
            try:
                yield True
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)


class Deployer:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.config = read_json(self.root / "config.json")
        self.pending = self.root / "pending.json"
        self.current = self.root / "current"
        for key in ("cli_root", "data_dir", "java_home"):
            if not Path(self.config[key]).is_absolute():
                raise ValueError(f"{key} must be an absolute path")
        for key in ("port", "db_port"):
            if type(self.config[key]) is not int or not 1 <= self.config[key] <= 65535:
                raise ValueError(f"Invalid {key}")
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", self.config["compose_project"]):
            raise ValueError("Invalid Compose project")
        if self.config["compose_project"] == "indexbudget-stand":
            raise ValueError("The shared database project cannot be used by frontend")

    def run(self, arguments, *, env=None, cwd=None, timeout=600, check=True):
        result = subprocess.run(arguments, env=env, cwd=cwd, timeout=timeout,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.stdout.strip():
            print(result.stdout.rstrip(), flush=True)
        if check and result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {arguments[0]}")
        return result

    def environment(self, metadata):
        env = os.environ.copy()
        env.pop("INDEXBUDGET_DATABASE_URL", None)
        env.update(JAVA_HOME=metadata["java_home"], FRONTEND_CLI=metadata["cli_path"],
                   FRONTEND_DB_PORT=str(metadata["db_port"]), FRONTEND_DB_IMAGE=metadata["image"],
                   COMPOSE_PROJECT_NAME=metadata["compose_project"],
                   FRONTEND_DATA_DIR=metadata["data_dir"], FRONTEND_REVISION=metadata["revision"])
        return env

    def compose(self, release, *arguments):
        metadata = read_json(release / "metadata.json")
        return self.run(["docker", "compose", "--project-name", metadata["compose_project"],
                         "-f", str(release / "source/frontend/compose.yaml"), *arguments],
                        env=self.environment(metadata))

    def active(self):
        return self.run(["systemctl", "--user", "is-active", "--quiet", SERVICE], check=False).returncode == 0

    def stop(self):
        # systemd остаётся в graceful stop даже если updater прервётся. Kill не применяется.
        self.run(["systemctl", "--user", "stop", SERVICE], timeout=900)
        result = self.run(["systemctl", "--user", "show", SERVICE, "--property=ActiveState", "--value"])
        if result.stdout.strip() not in ("inactive", "failed"):
            raise RuntimeError("Frontend has not stopped; database will not be changed")

    def start(self):
        self.run(["systemctl", "--user", "start", SERVICE])

    def healthy(self, metadata):
        base = f"http://127.0.0.1:{metadata['port']}"
        try:
            with urlopen(base + "/api/health", timeout=12) as response:
                health = json.load(response)
            if not isinstance(health, dict) or health.get("database") is not True or health.get("cli") is not True:
                return False
            # Старый main поддерживается при первой миграции, новые версии подтверждают SHA.
            if "deployment_revision" in health and health["deployment_revision"] != metadata["revision"]:
                return False
            with urlopen(base + "/api/templates", timeout=5) as response:
                templates = json.load(response)
            if not isinstance(templates, list) or not templates:
                return False
            with urlopen(base + "/", timeout=5) as response:
                return b"IndexBudget" in response.read(1024 * 1024)
        except (OSError, ValueError, TypeError):
            return False

    def wait_healthy(self, metadata):
        for attempt in range(20):
            if self.active() and self.healthy(metadata):
                return
            time.sleep(1)
        raise RuntimeError("Frontend health failed (HTTP, database, CLI or revision)")

    def snapshot(self):
        cli_root = Path(self.config["cli_root"])
        cli_release = (cli_root / "current").resolve(strict=True)
        revision = (cli_release / "REVISION").read_text().strip()
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise ValueError("Invalid successful CLI revision")
        source = cli_release / "source"
        cli_path = cli_release / "app/bin/indexbudget"
        if not cli_path.is_file() or not os.access(cli_path, os.X_OK):
            raise ValueError("Verified CLI launcher is missing")
        if not (source / "frontend/server.py").is_file():
            raise ValueError("Successful CLI release does not contain frontend")
        if not (Path(self.config["java_home"]) / "bin/java").is_file():
            raise ValueError("Configured JDK is missing")
        metadata = {key: self.config[key] for key in
                    ("data_dir", "java_home", "host", "port", "db_port", "compose_project")}
        metadata.update(revision=revision, cli_release=str(cli_release), cli_path=str(cli_path),
                        bundle_version=BUNDLE_VERSION,
                        image="indexbudget-frontend-db:" + tree_hash(source / "demo/postgres")[:24])
        identity = hashlib.sha256(json.dumps(metadata, sort_keys=True).encode()).hexdigest()[:12]
        release = self.root / "releases" / f"{revision}-{identity}"
        return source, release, metadata

    def stage(self, source, release, metadata):
        if release.exists():
            if read_json(release / "metadata.json") != metadata:
                raise ValueError("Existing release metadata differs")
            return
        release.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix=".pending-", dir=release.parent))
        try:
            ignore = shutil.ignore_patterns(".local", "__pycache__", "*.pyc", ".git")
            shutil.copytree(source / "frontend", temporary / "source/frontend", ignore=ignore)
            shutil.copytree(source / "demo/postgres", temporary / "source/demo/postgres")
            # Сценарии — runtime-источник SQL и частот frontend, из того же CLI SHA.
            # Старые успешные CLI-релизы до библиотеки остаются устанавливаемыми.
            if (source / "demo/scenarios").is_dir():
                shutil.copytree(source / "demo/scenarios", temporary / "source/demo/scenarios", ignore=ignore)
            (temporary / "source/frontend/.local").symlink_to(metadata["data_dir"], target_is_directory=True)
            write_json(temporary / "metadata.json", metadata)
            temporary.rename(release)
            sync_directory(release.parent)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)

    def preflight(self, release, metadata):
        env = self.environment(metadata)
        source = release / "source"
        self.run(["python3", "-m", "unittest", "discover", "-s", "frontend/tests", "-v"], cwd=source, env=env)
        node_tests = source / "frontend/tests/test_app.js"
        if node_tests.exists():
            if shutil.which("node"):
                self.run(["node", "--test", str(node_tests)], cwd=source, env=env)
            else:
                log("Node.js absent: browser unit tests are covered by CI, skipped on stand")
        for argument in ("--help", "--version"):
            self.run([metadata["cli_path"], argument], env=env)
        self.compose(release, "config", "--quiet")
        if self.run(["docker", "image", "inspect", metadata["image"], "--format", "{{.Id}}"], check=False).returncode:
            self.run(["docker", "build", "--tag", metadata["image"], str(source / "demo/postgres")], timeout=900)

    def managed_release(self, value):
        release = Path(value).resolve(strict=True)
        if release.parent != (self.root / "releases").resolve() or not (release / "metadata.json").is_file():
            raise ValueError("Refusing an unmanaged release in recovery journal")
        return release

    def recover(self):
        if not self.pending.exists():
            return
        pending = read_json(self.pending)
        previous = self.managed_release(pending["previous"]) if pending["previous"] else None
        candidate = self.managed_release(pending["candidate"])
        log("Recovering interrupted frontend deployment")
        self.stop()
        if previous:
            self.compose(previous, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "180")
            switch_link(self.current, previous)
            if pending["previous_active"]:
                self.start()
                self.wait_healthy(read_json(previous / "metadata.json"))
        elif self.current.is_symlink() and self.current.resolve() == candidate:
            self.current.unlink()
            sync_directory(self.root)
        deployed = self.root / "deployed.json"
        if pending.get("previous_deployed") is not None:
            write_json(deployed, pending["previous_deployed"])
        elif previous:
            metadata = read_json(previous / "metadata.json")
            write_json(deployed, {"revision": metadata["revision"], "release": str(previous),
                                  "cli_release": metadata["cli_release"], "restored_at": time.time()})
        else:
            deployed.unlink(missing_ok=True)
        # Нет previous при первой установке: старый unit/образ восстанавливает installer.
        self.pending.unlink()
        sync_directory(self.root)
        log("Previous frontend restored; reports retained")

    def update(self, prepare_only=False, require_deploy=False):
        if not prepare_only:
            self.recover()
        elif self.pending.exists():
            raise RuntimeError("Unfinished deployment: run updater recovery before reinstalling")
        source, candidate, metadata = self.snapshot()
        previous = self.managed_release(self.current.resolve()) if self.current.is_symlink() else None
        if not prepare_only and previous == candidate and self.active() and self.healthy(metadata):
            log(f"Frontend already uses CLI {metadata['revision']}")
            return
        self.stage(source, candidate, metadata)
        self.preflight(candidate, metadata)
        if (Path(self.config["cli_root"]) / "current").resolve() != Path(metadata["cli_release"]):
            log("CLI changed during preparation; deployment deferred until next tick")
            if prepare_only or require_deploy:
                raise RuntimeError("CLI changed; retry installation")
            return
        if prepare_only:
            log(f"Prepared frontend {metadata['revision']}")
            return
        Path(metadata["data_dir"]).mkdir(parents=True, exist_ok=True)
        write_json(self.pending, {"previous": str(previous) if previous else None,
                                  "candidate": str(candidate), "previous_active": self.active(),
                                  "previous_deployed": read_json(self.root / "deployed.json")
                                  if (self.root / "deployed.json").exists() else None})
        try:
            self.stop()
            self.compose(candidate, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "180")
            switch_link(self.current, candidate)
            self.start()
            self.wait_healthy(metadata)
            write_json(self.root / "deployed.json", {"revision": metadata["revision"],
                "release": str(candidate), "cli_release": metadata["cli_release"], "deployed_at": time.time()})
            self.pending.unlink()
            sync_directory(self.root)
            log(f"Frontend deployed with CLI {metadata['revision']}; shared reports preserved")
        except Exception:
            # Journal остаётся на диске при неуспешном откате; следующий tick сначала восстанавливает его.
            self.recover()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=os.environ.get("INDEXBUDGET_FRONTEND_ROOT", str(Path.home() / "indexbudget-frontend")))
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--lock-fd", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.lock_fd is not None:
        descriptor = os.fstat(args.lock_fd)
        expected = (root / "update.lock").stat()
        if (descriptor.st_dev, descriptor.st_ino) != (expected.st_dev, expected.st_ino):
            raise ValueError("Inherited lock is not this installation's update.lock")
        # Installer передаёт тот же open-file description, удерживая lock весь transaction.
        fcntl.flock(args.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        Deployer(root).update(args.prepare_only, require_deploy=True)
        return
    with update_lock(root) as acquired:
        if not acquired:
            log("Another frontend deployment holds the lock; skipped")
            return
        if (root / "install-pending.json").exists():
            raise RuntimeError("Interrupted installation: rerun install.py before automatic updates")
        Deployer(root).update(args.prepare_only)


if __name__ == "__main__":
    main()
