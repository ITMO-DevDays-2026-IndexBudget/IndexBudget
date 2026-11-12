#!/usr/bin/env python3
"""Запустить frontend и CLI одного неизменяемого релиза."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys


def launch_configuration(root, environment=None):
    # current читается один раз: последующее переключение не смешает Python и CLI.
    release = (Path(root) / "current").resolve(strict=True)
    metadata = json.loads((release / "metadata.json").read_text())
    frontend = release / "source" / "frontend"
    env = dict(os.environ if environment is None else environment)
    env.update({
        "JAVA_HOME": metadata["java_home"],
        "FRONTEND_CLI": metadata["cli_path"],
        "FRONTEND_DATA_DIR": metadata["data_dir"],
        "FRONTEND_REVISION": metadata["revision"],
        "FRONTEND_DB_IMAGE": metadata["image"],
        "FRONTEND_DB_PORT": str(metadata["db_port"]),
        "COMPOSE_PROJECT_NAME": metadata["compose_project"],
    })
    env["PATH"] = str(Path(metadata["java_home"]) / "bin") + os.pathsep + env.get("PATH", "/usr/bin:/bin")
    return frontend, [sys.executable, "-u", str(frontend / "server.py"),
                      "--host", metadata["host"], "--port", str(metadata["port"])], env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path,
                        default=Path(os.environ.get("INDEXBUDGET_FRONTEND_ROOT", Path.home() / "indexbudget-frontend")))
    args = parser.parse_args()
    frontend, command, env = launch_configuration(args.root)
    os.chdir(frontend)
    os.execve(command[0], command, env)


if __name__ == "__main__":
    main()
