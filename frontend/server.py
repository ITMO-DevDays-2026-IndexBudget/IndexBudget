#!/usr/bin/env python3
"""Локальный UI и последовательная обёртка над production CLI. Python 3.10+."""
from __future__ import annotations

import argparse
import copy
import html
import json
import math
import mimetypes
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
TERMINAL = {"completed", "partial", "failed"}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(".pending")
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    pending.replace(path)


def read_json(path):
    return json.loads(path.read_text())


def finite(value, minimum=0):
    return type(value) in (int, float) and math.isfinite(value) and value >= minimum


def validate_request(data, templates):
    """Проверка формы; SQL и полная семантика workload проверяются самим CLI."""
    if not isinstance(data, dict) or data.get("template_id") not in templates:
        raise ValueError("Выберите существующий шаблон.")
    template = templates[data["template_id"]]
    if data.get("template_version") != template["version"]:
        raise ValueError("Версия шаблона изменилась. Загрузите шаблон заново.")
    for key in ("schema_sql", "seed_sql"):
        if not isinstance(data.get(key), str) or not data[key].strip():
            raise ValueError(f"{key}: нужен непустой SQL.")
        if re.search(r"^\s*\\", data[key], re.M):
            raise ValueError(f"{key}: используйте SQL, без метакоманд psql.")
    w = data.get("workload")
    if not isinstance(w, dict) or w.get("schema_version") != 2:
        raise ValueError("Ожидается workload JSON v2.")
    if len(json.dumps(w, ensure_ascii=False).encode()) > 1024 * 1024:
        raise ValueError("Workload превышает 1 MiB.")
    queries, profiles = w.get("queries"), w.get("profiles")
    if not isinstance(queries, list) or not 1 <= len(queries) <= 20:
        raise ValueError("Нужно от 1 до 20 запросов.")
    ids = []
    for q in queries:
        if not isinstance(q, dict) or not isinstance(q.get("id"), str) or not q["id"].strip():
            raise ValueError("У каждого запроса должен быть ID.")
        if not isinstance(q.get("sql"), str) or not q["sql"].strip():
            raise ValueError(f"{q['id']}: введите SQL.")
        if len(q["sql"].encode()) > 65536:
            raise ValueError(f"{q['id']}: SQL превышает 64 KiB.")
        ids.append(q["id"])
    if len(set(ids)) != len(ids):
        raise ValueError("ID запросов должны различаться.")
    if not isinstance(profiles, list) or not 1 <= len(profiles) <= 3:
        raise ValueError("Нужно от 1 до 3 профилей нагрузки.")
    profile_ids = []
    for p in profiles:
        if not isinstance(p, dict) or not isinstance(p.get("id"), str) or not p["id"].strip():
            raise ValueError("У каждого профиля должен быть ID.")
        profile_ids.append(p["id"])
        if not finite(p.get("weight")) or p["weight"] == 0:
            raise ValueError(f"{p['id']}: вес должен быть положительным.")
        calls = p.get("calls_per_hour")
        if not isinstance(calls, dict) or set(calls) != set(ids):
            raise ValueError(f"{p['id']}: задайте частоты для всех запросов.")
        if not all(finite(v) for v in calls.values()) or not any(calls.values()):
            raise ValueError(f"{p['id']}: частоты неотрицательные, хотя бы одна больше нуля.")
    if len(set(profile_ids)) != len(profile_ids):
        raise ValueError("ID профилей должны различаться.")
    if any(not any(p["calls_per_hour"][qid] > 0 for p in profiles) for qid in ids):
        raise ValueError("Каждый запрос должен использоваться хотя бы в одном профиле.")
    c = w.get("constraints")
    if not isinstance(c, dict) or type(c.get("storage_budget_bytes")) is not int or c["storage_budget_bytes"] < 0:
        raise ValueError("Бюджет места должен быть целым неотрицательным числом байтов.")
    if type(c.get("max_indexes")) is not int or not 0 <= c["max_indexes"] <= 5:
        raise ValueError("Число индексов должно быть от 0 до 5.")
    if c.get("write_budget_units_per_hour") is not None and not finite(c["write_budget_units_per_hour"]):
        raise ValueError("Бюджет записи должен быть неотрицательным.")
    for key, allowed, default in (
        ("objective", {"robust", "mean"}, "robust"),
        ("analysis_mode", {"fast", "normal", "deep"}, "normal"),
        ("candidate_ranking", {"frequency", "baseline_cost"}, "frequency"),
    ):
        if w.get(key, default) not in allowed:
            raise ValueError(f"Неверное значение {key}.")
    # Снимок не содержит присланных клиентом путей, подключения или статуса задачи.
    return {key: copy.deepcopy(data[key]) for key in
            ("template_id", "template_version", "schema_sql", "seed_sql", "workload")}


class LocalRunner:
    def __init__(self, host="127.0.0.1"):
        self.host = host
        self.compose = ["docker", "compose", "-f", str(ROOT / "compose.yaml")]
        self.cli = Path(os.environ.get("FRONTEND_CLI", str(REPO / "build/install/indexbudget/bin/indexbudget")))

    def command(self, args, input=None, timeout=120, env=None):
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, env=env, start_new_session=True)
        try:
            stdout, stderr = process.communicate(input, timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
            raise RuntimeError("Превышено время выполнения локальной команды.") from None
        return process.returncode, stdout, stderr

    def db(self, sql, database="frontend_demo"):
        code, out, err = self.command(self.compose + ["exec", "-T", "-e",
            "PGOPTIONS=-c statement_timeout=60000", "postgres", "psql", "-X", "-qAt",
            "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database], input=sql)
        if code:
            raise RuntimeError("PostgreSQL: " + (err.strip()[-3500:] or "локальная БД недоступна"))
        return out.strip()

    def health(self):
        try:
            code, _, _ = self.command(self.compose + ["exec", "-T", "postgres", "pg_isready",
                                                      "-U", "postgres"], timeout=8)
            database = code == 0
        except (OSError, RuntimeError):
            database = False
        return {"database": database, "cli": self.cli.is_file(), "local_only": self.host == "127.0.0.1"}

    def prepare(self, request):
        self.db("DROP DATABASE IF EXISTS frontend_demo WITH (FORCE);\nCREATE DATABASE frontend_demo;", "postgres")
        self.db("CREATE EXTENSION hypopg;\n" + request["schema_sql"])
        self.db(request["seed_sql"])
        self.db("""ANALYZE;
DO $$ BEGIN
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'frontend_reader') THEN
   CREATE ROLE frontend_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
 END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE frontend_demo FROM PUBLIC;
DO $$ DECLARE s record; BEGIN
 FOR s IN SELECT nspname FROM pg_namespace
   WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' LOOP
   EXECUTE format('GRANT USAGE ON SCHEMA %I TO frontend_reader', s.nspname);
   EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO frontend_reader', s.nspname);
 END LOOP;
END $$;""")
        return json.loads(self.db((ROOT / "catalog.sql").read_text()))

    def analyze(self, workload, output):
        if not self.cli.is_file():
            raise RuntimeError("CLI не собран. Выполните ./gradlew installDist в корне проекта.")
        env = os.environ.copy()
        port = int(env.get("FRONTEND_DB_PORT", "55433"))
        # Никогда не наследуем адрес общей БД из окружения пользователя.
        env["INDEXBUDGET_DATABASE_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/frontend_demo?user=frontend_reader"
        return self.command([str(self.cli), "analyze", "--workload", str(workload),
                             "--output-dir", str(output)], timeout=360, env=env)


class Jobs:
    def __init__(self, directory, runner, templates):
        self.directory, self.runner, self.templates = directory, runner, templates
        self.lock = threading.RLock()
        self.items = {}
        self.queue = queue.Queue()
        (directory / "jobs").mkdir(parents=True, exist_ok=True)
        (directory / "reports").mkdir(exist_ok=True)
        for path in (directory / "jobs").glob("*/status.json"):
            item = read_json(path)
            if item["status"] not in TERMINAL:
                item.update(status="failed", error="Сервер перезапущен. Отправьте запуск повторно.")
                write_json(path, item)
            self.items[item["run_id"]] = item
        threading.Thread(target=self.work, daemon=True).start()

    def update(self, id, **changes):
        with self.lock:
            self.items[id].update(changes)
            write_json(self.directory / "jobs" / id / "status.json", self.items[id])

    def get(self, id):
        with self.lock:
            item = copy.deepcopy(self.items[id])
            if item["status"] == "queued":
                item["queue_position"] = 1 + sum(
                    other["status"] == "queued" and other["created_at"] < item["created_at"]
                    for other in self.items.values())
            return item

    def submit(self, data, kind="analysis"):
        request = validate_request(data, self.templates)
        id = str(uuid4())
        item = {"run_id": id, "kind": kind, "status": "queued", "created_at": time.time(),
                "template_id": request["template_id"], "report_url": None, "error": None}
        with self.lock:
            write_json(self.directory / "jobs" / id / "request.json", request)
            self.items[id] = item
            self.update(id)
            self.queue.put(id)
        return self.get(id)

    def execute(self, id):
        folder = self.directory / "jobs" / id
        request = read_json(folder / "request.json")
        self.update(id, status="preparing", started_at=time.time())
        catalog = self.runner.prepare(request)
        self.update(id, catalog=catalog)
        if self.items[id]["kind"] == "schema":
            self.update(id, status="completed", finished_at=time.time())
            return
        write_json(folder / "workload.json", request["workload"])
        self.update(id, status="analyzing")
        staging = folder / "export"
        code, stdout, stderr = self.runner.analyze(folder / "workload.json", staging)
        (folder / "cli.log").write_text(stdout + "\n" + stderr)
        report_path = staging / "report.json"
        report = read_json(report_path) if report_path.is_file() else {}
        expected = {0: "completed", 3: "partial"}
        if code not in expected or report.get("status") != expected[code]:
            message = (report.get("error") or {}).get("message")
            if not message:
                message = stderr.strip()[-3000:] or f"CLI завершился с кодом {code}."
            raise RuntimeError(message)
        if not all((staging / name).is_file() for name in ("report.html", "report.json", "indexes.sql")):
            raise RuntimeError("Экспорт не завершён: отсутствуют файлы отчёта.")
        self.update(id, status="publishing")
        write_json(staging / "workload.json", request["workload"])
        write_json(staging / "demo-config.json", {**{k: v for k, v in request.items() if k != "workload"},
                                                  "run_id": id, "analysis_run_id": report.get("run_id")})
        write_json(staging / "schema.json", catalog)
        staging.rename(self.directory / "reports" / id)
        self.update(id, status=report["status"], report_url=f"/reports/{id}/report.html",
                    analysis_run_id=report.get("run_id"), finished_at=time.time())

    def work(self):
        while True:
            id = self.queue.get()
            try:
                self.execute(id)
            except Exception as error:
                self.update(id, status="failed", error=str(error), finished_at=time.time())
            finally:
                self.queue.task_done()


def make_handler(jobs, templates):
    class Handler(BaseHTTPRequestHandler):
        def send(self, status, body, content_type="application/json; charset=utf-8"):
            if not isinstance(body, bytes):
                body = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def file(self, path):
            if not path.is_file():
                self.send(404, {"error": "Файл не найден."})
                return
            self.send(200, path.read_bytes(), (mimetypes.guess_type(path)[0] or "application/octet-stream") + "; charset=utf-8")

        def do_GET(self):
            path = unquote(urlsplit(self.path).path)
            if path == "/api/templates":
                self.send(200, list(templates.values()))
            elif path.startswith("/api/templates/"):
                item = templates.get(path.rsplit("/", 1)[-1])
                self.send(200 if item else 404, item or {"error": "Шаблон не найден."})
            elif path == "/api/health":
                self.send(200, jobs.runner.health())
            elif path.startswith("/api/runs/"):
                try:
                    self.send(200, jobs.get(path.rsplit("/", 1)[-1]))
                except KeyError:
                    self.send(404, {"error": "Запуск не найден."})
            elif path in ("/reports", "/reports/"):
                entries = sorted((jobs.directory / "reports").iterdir(), key=lambda p: p.stat().st_mtime, reverse=True)
                links = "".join(f'<li><a href="/reports/{p.name}/report.html">{html.escape(p.name)}</a> '
                                f'<a href="/reports/{p.name}/report.json">JSON</a> · '
                                f'<a href="/reports/{p.name}/indexes.sql">SQL</a></li>' for p in entries if p.is_dir())
                page = '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><link rel="stylesheet" href="/style.css"><title>Отчёты · IndexBudget</title><main class="archive"><a href="/">← К настройке</a><h1>Отчёты</h1><p>Локальные опубликованные запуски</p><ul>' + (links or "<li>Пока нет отчётов</li>") + "</ul></main></html>"
                self.send(200, page.encode(), "text/html; charset=utf-8")
            elif path.startswith("/reports/"):
                parts = path.strip("/").split("/")
                allowed = {"report.html", "report.json", "indexes.sql", "workload.json", "demo-config.json", "schema.json"}
                try:
                    valid = len(parts) == 3 and str(UUID(parts[1])) == parts[1] and parts[2] in allowed
                except ValueError:
                    valid = False
                if valid:
                    self.file(jobs.directory / "reports" / parts[1] / parts[2])
                else:
                    self.send(404, {"error": "Файл не найден."})
            elif path in ("/", "/index.html", "/app.js", "/style.css"):
                self.file(ROOT / "static" / ("index.html" if path == "/" else path[1:]))
            else:
                self.send(404, {"error": "Страница не найдена."})

        def do_POST(self):
            path = urlsplit(self.path).path
            if path not in ("/api/runs", "/api/schema-previews"):
                self.send(404, {"error": "Операция не найдена."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 3 * 1024 * 1024:
                    raise ValueError("Размер запроса должен быть от 1 байта до 3 MiB.")
                data = json.loads(self.rfile.read(length), parse_constant=lambda v: (_ for _ in ()).throw(ValueError("Некорректное число JSON.")))
                item = jobs.submit(data, "schema" if path.endswith("schema-previews") else "analysis")
                self.send(202, {**item, "status_url": f"/api/runs/{item['run_id']}"})
            except (ValueError, TypeError, UnicodeError) as error:
                self.send(400, {"error": str(error)})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    templates = {t["id"]: t for t in (read_json(p) for p in sorted((ROOT / "templates").glob("*.json")))}
    directory = ROOT / ".local"
    directory.mkdir(exist_ok=True)
    # Один исполнитель на рабочую БД, даже если случайно запустили второй сервер.
    import fcntl
    with (directory / "server.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.exit(1, "Локальный сервер уже запущен.\n")
        jobs = Jobs(directory, LocalRunner(args.host), templates)
        server = ThreadingHTTPServer((args.host, args.port), make_handler(jobs, templates))
        print(f"IndexBudget → http://{args.host}:{args.port}", flush=True)
        def stop(_signum, _frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, stop)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nЗавершение очереди…", flush=True)
            jobs.queue.join()
        finally:
            server.server_close()


if __name__ == "__main__":
    main()
