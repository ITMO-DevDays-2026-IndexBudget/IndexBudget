#!/usr/bin/env python3
"""Установить пользовательский автодеплой frontend, сохранив прежние отчёты."""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen
from uuid import uuid4

SERVICE = "indexbudget-frontend.service"
UPDATE_SERVICE = "indexbudget-frontend-update.service"
TIMER = "indexbudget-frontend-update.timer"
UNITS = (SERVICE, UPDATE_SERVICE, TIMER)


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".install-", delete=False) as output:
        output.write(data if isinstance(data, bytes) else data.encode())
        output.flush()
        os.fsync(output.fileno())
        pending = Path(output.name)
    pending.replace(path)
    directory_fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def write_json(path, value):
    atomic_write(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def legacy_http_port(exec_start):
    # Старый server.py по умолчанию слушает 8090; штатный installer передавал --port явно.
    match = re.search(r"(?:^|\s)--port(?:=|\s+)([0-9]+)(?=\s|;|$)", exec_start)
    return int(match.group(1)) if match else 8090


def file_manifest(directory):
    """Хэши всех файлов, не только количества отчётов; ссылки не копируются извне."""
    directory = Path(directory)
    manifest = {}
    if not directory.exists():
        return manifest
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise RuntimeError(f"В каталоге данных обнаружена ссылка: {path.name}; автоматическая миграция остановлена.")
        if path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            manifest[str(path.relative_to(directory))] = digest.hexdigest()
    return manifest


def migrate_data(legacy, shared, backup):
    """Вызывается только после остановки сервиса, под deployment lock."""
    legacy, shared, backup = Path(legacy), Path(shared), Path(backup)
    if legacy.is_symlink():
        if legacy.resolve() != shared.resolve():
            raise RuntimeError("Старый каталог данных ссылается в другое место; укажите фактический legacy-data-dir.")
        shared.mkdir(parents=True, exist_ok=True)
        return file_manifest(shared)
    if legacy.resolve() == shared.resolve():
        shared.mkdir(parents=True, exist_ok=True)
        return file_manifest(shared)
    if not legacy.exists():
        # Прерывание могло произойти между rename старого каталога и созданием ссылки.
        if backup.exists() and shared.exists():
            legacy.symlink_to(shared, target_is_directory=True)
        else:
            shared.mkdir(parents=True, exist_ok=True)
        return file_manifest(shared)
    if not legacy.is_dir():
        raise RuntimeError("Старый путь данных не является каталогом.")
    expected = file_manifest(legacy)
    if shared.exists() and any(shared.iterdir()):
        if file_manifest(shared) != expected:
            raise RuntimeError("Оба каталога данных непусты и различаются; автоматическое слияние запрещено.")
    else:
        pending = shared.with_name(shared.name + ".pending-" + uuid4().hex)
        try:
            shutil.copytree(legacy, pending)
            if file_manifest(pending) != expected:
                raise RuntimeError("Проверка контрольных сумм копии отчётов не пройдена.")
            if shared.exists():
                shared.rmdir()  # Только заведомо пустой каталог.
            pending.replace(shared)
        finally:
            if pending.exists():
                shutil.rmtree(pending)
    if file_manifest(legacy) != expected:
        raise RuntimeError("Данные изменились во время копирования; сервис должен быть остановлен.")
    if backup.exists():
        raise RuntimeError("Резервная копия старого каталога уже существует.")
    legacy.replace(backup)
    legacy.symlink_to(shared, target_is_directory=True)
    return expected


def unit_value(value, argument=False):
    value = str(value)
    if any(c in value for c in "\n\r\0"):
        raise ValueError("Переводы строк недопустимы в пути systemd.")
    value = value.replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%")
    if argument:
        value = value.replace("$", "$$")
    return '"' + value + '"'


def unit_files(root):
    launch = unit_value(Path(root) / "bin" / "launch.py", True)
    updater = unit_value(Path(root) / "bin" / "update.py", True)
    arg_root = unit_value(root, True)
    return {
        SERVICE: f"""[Unit]
Description=IndexBudget demonstration frontend
After=network.target
[Service]
Type=simple
ExecStart=/usr/bin/python3 -u {launch} --root {arg_root}
Restart=on-failure
RestartSec=3
TimeoutStopSec=infinity
KillMode=mixed
[Install]
WantedBy=default.target
""",
        UPDATE_SERVICE: f"""[Unit]
Description=Update IndexBudget frontend from verified CLI release
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 -u {updater} --root {arg_root}
TimeoutStartSec=20min
""",
        TIMER: f"""[Unit]
Description=Check IndexBudget frontend updates
[Timer]
OnStartupSec=1min
OnUnitInactiveSec=1min
Unit={UPDATE_SERVICE}
[Install]
WantedBy=timers.target
""",
    }


@contextlib.contextmanager
def lock(path):
    with Path(path).open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield handle


class Installer:
    def __init__(self, root, source=None, unit_dir=None, runtime_dir=None, run=None):
        self.root = Path(root).expanduser().resolve()
        self.source = Path(source or Path(__file__).resolve().parent)
        self.units = Path(unit_dir or Path.home() / ".config/systemd/user")
        self.runtime = Path(runtime_dir or os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"))
        self.execute = run or subprocess.run
        self.journal = self.root / "install-pending.json"

    def command(self, args, check=True, **kwargs):
        return self.execute([str(v) for v in args], check=check, text=True, capture_output=True, **kwargs)

    def systemctl(self, *args, check=True):
        return self.command(["systemctl", "--user", *args], check=check)

    def property(self, name):
        result = self.systemctl("show", SERVICE, "-p", name, "--value", check=False)
        return result.stdout.strip() if result.returncode == 0 else ""

    def updater(self, handle, prepare=False):
        command = [sys.executable, self.root / "bin/update.py", "--root", self.root,
                   "--lock-fd", str(handle.fileno())]
        if prepare:
            command.append("--prepare-only")
        try:
            result = self.command(command, pass_fds=(handle.fileno(),))
        except subprocess.CalledProcessError as error:
            print((error.stdout or "")[-6000:], file=sys.stderr)
            print((error.stderr or "")[-6000:], file=sys.stderr)
            raise
        if result.stdout:
            print(result.stdout.rstrip(), flush=True)

    def verify_service(self, port):
        for attempt in range(30):
            try:
                with urlopen(f"http://127.0.0.1:{port}/api/health", timeout=10) as response:
                    value = json.load(response)
                if isinstance(value, dict) and value.get("database") is True and value.get("cli") is True:
                    return
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise RuntimeError("Не удалось проверить восстановленный frontend; журнал установки сохранён.")

    def set_drain_override(self):
        path = self.runtime / "systemd/user" / (SERVICE + ".d") / "90-indexbudget-install-drain.conf"
        if path.exists():
            raise RuntimeError("Временная настройка остановки уже существует; сначала восстановите предыдущую установку.")
        atomic_write(path, "[Service]\nTimeoutStopSec=infinity\n")
        self.systemctl("daemon-reload")
        return path

    def restore(self, record):
        # Сохраняем журнал, если восстановление не удалось; следующий install повторит его.
        self.systemctl("stop", TIMER, check=False)
        if record.get("service_changed"):
            self.systemctl("stop", SERVICE, check=False)
            if self.property("ActiveState") not in ("inactive", "failed", ""):
                raise RuntimeError("Нельзя восстанавливать БД и релиз, пока frontend не остановлен.")
        legacy = record.get("legacy_data")
        backup = record.get("legacy_backup")
        if legacy and backup and not Path(legacy).exists() and Path(backup).exists():
            shared = Path(record["data_dir"])
            if shared.exists():
                Path(legacy).symlink_to(shared, target_is_directory=True)
            else:
                Path(backup).replace(legacy)
        previous = record.get("current")
        if record.get("deployment_started") and previous:
            metadata = json.loads((Path(previous) / "metadata.json").read_text())
            env = os.environ.copy()
            env.update({"FRONTEND_DB_IMAGE": metadata["image"],
                        "FRONTEND_DB_PORT": str(metadata["db_port"]),
                        "COMPOSE_PROJECT_NAME": metadata["compose_project"]})
            self.command(["docker", "compose", "-f", Path(previous) / "source/frontend/compose.yaml",
                          "up", "--detach", "--no-build", "--wait"], env=env)
        current = self.root / "current"
        if previous:
            temporary = self.root / (".current-restore-" + uuid4().hex)
            temporary.symlink_to(previous, target_is_directory=True)
            temporary.replace(current)
        elif current.is_symlink():
            current.unlink()
        if record.get("deployed") is None:
            (self.root / "deployed.json").unlink(missing_ok=True)
        else:
            atomic_write(self.root / "deployed.json", record["deployed"])
        for name, text in record.get("bin", {}).items():
            path = self.root / "bin" / name
            if text is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, text)
        for name, text in record["units"].items():
            path = self.units / name
            if text is None:
                self.systemctl("disable", name, check=False)
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, text)
        if record.get("config") is None:
            (self.root / "config.json").unlink(missing_ok=True)
        else:
            atomic_write(self.root / "config.json", record["config"])
        if record.get("drain_override_content") is None:
            Path(record["drain_override"]).unlink(missing_ok=True)
        else:
            atomic_write(Path(record["drain_override"]), record["drain_override_content"])
        self.systemctl("daemon-reload")
        if record.get("deployment_started") and record.get("legacy_frontend"):
            env = os.environ.copy()
            env.update(record["legacy_environment"])
            self.command(["docker", "compose", "-f", Path(record["legacy_frontend"]) / "compose.yaml",
                          "up", "--detach", "--no-build", "--wait"], env=env)
        if record["active"]:
            self.systemctl("start", SERVICE)
            if record.get("service_changed"):
                self.verify_service(record["port"])
        for name, state in record.get("enabled", {}).items():
            if state == "enabled":
                self.systemctl("enable", name)
            elif state not in ("static", "indirect", "alias"):
                self.systemctl("disable", name, check=False)
        if record["timer_active"]:
            self.systemctl("start", TIMER)
        if record.get("deployment_started"):
            (self.root / "pending.json").unlink(missing_ok=True)
        self.journal.unlink(missing_ok=True)

    def install(self, overrides=None, legacy_data=None):
        self.root.mkdir(parents=True, exist_ok=True)
        self.units.mkdir(parents=True, exist_ok=True)
        with lock(self.root / "install.lock"), lock(self.root / "update.lock") as update_handle:
            if self.journal.exists():
                self.restore(json.loads(self.journal.read_text()))
            self.systemctl("show-environment")
            if (self.root / "current").exists() and not (self.root / "current").is_symlink():
                raise RuntimeError("current уже существует и не является ссылкой; автоматическая замена запрещена.")
            old_config = (self.root / "config.json").read_text() if (self.root / "config.json").exists() else None
            config = json.loads(old_config) if old_config else {
                "cli_root": os.environ.get("INDEXBUDGET_DEPLOY_ROOT", str(Path.home() / "indexbudget")), "data_dir": str(self.root / "shared"),
                "host": "0.0.0.0", "port": 3000, "db_port": 55433,
                "compose_project": "indexbudget-frontend",
            }
            config.update({k: v for k, v in (overrides or {}).items() if v is not None})
            config["cli_root"] = str(Path(config["cli_root"]).expanduser().resolve())
            config.setdefault("java_home", str(Path(config["cli_root"]) / "jdk"))
            config["java_home"] = str(Path(config["java_home"]).expanduser().resolve())
            config["data_dir"] = str(Path(config["data_dir"]).expanduser().resolve())
            for key in ("port", "db_port"):
                if type(config[key]) is not int or not 1 <= config[key] <= 65535:
                    raise ValueError(f"{key}: ожидается порт 1–65535.")
            for key in ("host", "compose_project", "cli_root", "java_home", "data_dir"):
                if not isinstance(config[key], str) or not config[key] or any(c in config[key] for c in "\n\r\0"):
                    raise ValueError(f"Неверная настройка {key}.")
            fragment = self.property("FragmentPath")
            expected = self.units / SERVICE
            if fragment and Path(fragment).resolve() != expected.resolve():
                raise RuntimeError("Сервис установлен вне пользовательского каталога units; автоматическая замена запрещена.")
            if self.property("DropInPaths"):
                raise RuntimeError("У сервиса есть дополнительные настройки systemd; сохраните их явно перед миграцией.")
            bin_dir = self.root / "bin"
            bin_dir.mkdir(exist_ok=True)
            active = self.property("ActiveState") == "active"
            frontend = Path(self.property("WorkingDirectory") or self.root / "frontend")
            is_legacy = (frontend / "compose.yaml").is_file() and not (self.root / "current").exists()
            old_environment = dict(v.split("=", 1) for v in shlex.split(self.property("Environment")) if "=" in v)
            legacy_environment = {
                "FRONTEND_DB_PORT": old_environment.get("FRONTEND_DB_PORT", str(config["db_port"])),
                "FRONTEND_DB_IMAGE": old_environment.get("FRONTEND_DB_IMAGE", "indexbudget-frontend-db:local"),
                "COMPOSE_PROJECT_NAME": old_environment.get("COMPOSE_PROJECT_NAME", "indexbudget-frontend"),
            }
            legacy_path = Path(legacy_data).expanduser().resolve() if legacy_data else frontend / ".local"
            backup = legacy_path.with_name(legacy_path.name + ".pre-autodeploy-" + uuid4().hex[:12])
            drain_path = self.runtime / "systemd/user" / (SERVICE + ".d") / "90-indexbudget-install-drain.conf"
            record = {"units": {name: (self.units / name).read_text() if (self.units / name).exists() else None for name in UNITS},
                      "bin": {name: (bin_dir / name).read_text() if (bin_dir / name).exists() else None
                              for name in ("update.py", "launch.py", "install.py")},
                      "config": old_config, "active": active,
                      "enabled": {name: self.systemctl("is-enabled", name, check=False).stdout.strip() for name in UNITS},
                      "current": str((self.root / "current").resolve()) if (self.root / "current").is_symlink() else None,
                      "deployed": (self.root / "deployed.json").read_text() if (self.root / "deployed.json").exists() else None,
                      "port": json.loads(old_config)["port"] if old_config else legacy_http_port(self.property("ExecStart")),
                      "timer_active": self.systemctl("is-active", TIMER, check=False).returncode == 0,
                      "legacy_data": str(legacy_path), "legacy_backup": str(backup),
                      "data_dir": config["data_dir"], "legacy_frontend": str(frontend) if is_legacy else None,
                      "legacy_environment": legacy_environment, "drain_override": str(drain_path),
                      "drain_override_content": drain_path.read_text() if drain_path.exists() else None,
                      "service_changed": False, "deployment_started": False}
            write_json(self.journal, record)
            try:
                for name in ("update.py", "launch.py", "install.py"):
                    if (self.source / name).resolve() != (bin_dir / name).resolve():
                        atomic_write(bin_dir / name, (self.source / name).read_bytes())
                write_json(self.root / "config.json", config)
                # Preflight проходит под тем же lock, до остановки живого frontend.
                self.updater(update_handle, prepare=True)
                # Проверяем настоящим парсером systemd до остановки действующего сервиса.
                with tempfile.TemporaryDirectory(prefix=".units-", dir=self.root) as directory:
                    paths = []
                    for name, content in unit_files(self.root).items():
                        path = Path(directory) / name
                        path.write_text(content)
                        paths.append(path)
                    self.command(["systemd-analyze", "--user", "verify", *paths])
                self.systemctl("stop", TIMER, check=False)
                self.set_drain_override()
                self.systemctl("stop", SERVICE, check=False)
                if self.property("ActiveState") not in ("inactive", "failed", ""):
                    raise RuntimeError("Сервис не завершил очередь; миграция отложена.")
                # Не копируем открытые JSON/отчеты и не объединяем разные истории.
                manifest = migrate_data(legacy_path, Path(config["data_dir"]), backup)
                write_json(self.root / "migration-manifest.json", manifest)
                record["service_changed"] = True
                write_json(self.journal, record)
                for name, content in unit_files(self.root).items():
                    atomic_write(self.units / name, content)
                drain_path.unlink(missing_ok=True)
                self.systemctl("daemon-reload")
                record["deployment_started"] = True
                write_json(self.journal, record)
                self.updater(update_handle)
                self.systemctl("enable", SERVICE)
                self.systemctl("enable", "--now", TIMER)
                self.journal.unlink()
                print("Автодеплой frontend установлен; отчёты сохранены в " + config["data_dir"])
            except BaseException:
                self.restore(record)
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path,
                        default=Path(os.environ.get("INDEXBUDGET_FRONTEND_ROOT", Path.home() / "indexbudget-frontend")))
    parser.add_argument("--cli-root")
    parser.add_argument("--java-home")
    parser.add_argument("--legacy-data-dir")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--db-port", type=int)
    parser.add_argument("--compose-project")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("Установка требует Linux с пользовательским systemd.")
    overrides = {name: getattr(args, name) for name in
                 ("cli_root", "java_home", "host", "port", "db_port", "compose_project")}
    try:
        Installer(args.root).install(overrides, args.legacy_data_dir)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        # Не выводим окружение subprocess: там могут быть приватные параметры оператора.
        parser.exit(1, f"Автодеплой не установлен: {error}\n")


if __name__ == "__main__":
    main()
