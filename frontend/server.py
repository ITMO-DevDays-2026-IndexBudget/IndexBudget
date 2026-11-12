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
MAX_CALLS_PER_HOUR = 1_000_000


class ShuttingDownError(RuntimeError):
    """Новые задания не принимаются после начала штатной остановки."""


class FrontendHTTPServer(ThreadingHTTPServer):
    # Дожидаемся уже начатых HTTP-обработчиков до освобождения общей блокировки.
    daemon_threads = False


def data_directory():
    directory = Path(os.environ.get("FRONTEND_DATA_DIR") or ROOT / ".local").expanduser().resolve()
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(".pending")
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    pending.replace(path)


def read_json(path):
    return json.loads(path.read_text())


def finite(value, minimum=0):
    try:
        return type(value) in (int, float) and math.isfinite(value) and value >= minimum
    except OverflowError:
        return False


def valid_frequency(value):
    # Проверка диапазона до is_integer также исключает NaN, infinity и огромные int.
    return (type(value) in (int, float) and 0 <= value <= MAX_CALLS_PER_HOUR
            and (type(value) is int or value.is_integer()))


def validate_request(data, templates):
    """Проверка формы; SQL и полная семантика workload проверяются самим CLI."""
    if (not isinstance(data, dict) or not isinstance(data.get("template_id"), str)
            or data["template_id"] not in templates):
        raise ValueError("Выберите существующий шаблон.")
    template = templates[data["template_id"]]
    if (type(data.get("template_version")) is not type(template["version"])
            or data["template_version"] != template["version"]):
        raise ValueError("Версия шаблона изменилась. Загрузите шаблон заново.")
    for key in ("schema_sql", "seed_sql"):
        if key in data:
            raise ValueError(f"{key}: схема и данные задаются только серверным шаблоном. Обновите страницу.")
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
        for qid, value in calls.items():
            if not valid_frequency(value):
                raise ValueError(f"{p['id']} / {qid}: частота должна быть целым числом от 0 до 1 000 000 вызовов/час.")
        if not any(calls.values()):
            raise ValueError(f"{p['id']}: хотя бы одна частота должна быть больше нуля.")
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
    result = {key: copy.deepcopy(data[key]) for key in ("template_id", "template_version", "workload")}
    series = data.get("series")
    if series is not None:
        if not isinstance(series, dict) or set(series) - {"schema_version", "budget", "values", "timeout_seconds"}:
            raise ValueError("Неверный запрос серии.")
        budget, values = series.get("budget"), series.get("values")
        if type(series.get("schema_version")) is not int or series["schema_version"] != 1 or budget not in {"storage_budget_bytes", "write_budget_units_per_hour"}:
            raise ValueError("Серия требует schema_version=1 и один вид бюджета.")
        if not isinstance(values, list) or not 3 <= len(values) <= 5 or any(not finite(v) for v in values) or len(set(values)) != len(values):
            raise ValueError("Задайте 3–5 различных неотрицательных бюджетов.")
        if budget == "storage_budget_bytes" and any(type(v) is not int or v > 9223372036854775807 for v in values):
            raise ValueError("Бюджеты места должны быть целыми байтами в диапазоне Long.")
        timeout = series.get("timeout_seconds", 600)
        if type(timeout) is not int or not 1 <= timeout <= 86400:
            raise ValueError("Общий timeout серии должен быть от 1 до 86400 секунд.")
        if budget == "write_budget_units_per_hour":
            rates = w.get("table_write_rates")
            if not isinstance(rates, dict) or not rates:
                raise ValueError("Серия записи требует полного table_write_rates; окончательная проверка таблиц выполняется CLI.")
            for rate in rates.values():
                if not isinstance(rate, dict) or any(not finite(rate.get(k)) for k in ("inserts_per_hour", "updates_per_hour", "deletes_per_hour")):
                    raise ValueError("Неполный профиль записи.")
        result["series"] = copy.deepcopy(series)
    return result


def report_artifact(directory, parts):
    allowed = {"report.html", "report.json", "indexes.sql", "workload.json", "demo-config.json", "schema.json", "series.json", "series.html", "series-request.json"}
    try:
        if len(parts) < 3 or str(UUID(parts[1])) != parts[1] or parts[-1] not in allowed:
            return None
        if any(p in {".", "..", ""} for p in parts[2:]):
            return None
        base = (directory / "reports" / parts[1]).resolve()
        target = base.joinpath(*parts[2:]).resolve()
        return target if target.is_relative_to(base) else None
    except (ValueError, OSError):
        return None


class LocalRunner:
    def __init__(self, host="127.0.0.1"):
        self.host = host
        self.compose = ["docker", "compose", "-f", str(ROOT / "compose.yaml")]
        self.cli = Path(os.environ.get("FRONTEND_CLI", str(REPO / "build/install/indexbudget/bin/indexbudget")))
        self.deployment_revision = os.environ.get("FRONTEND_REVISION")

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
        health = {"database": database, "cli": self.cli.is_file(), "local_only": self.host == "127.0.0.1"}
        if self.deployment_revision:
            health["deployment_revision"] = self.deployment_revision
        return health

    def prepare(self, request):
        # schema_sql/seed_sql — снимок доверенного шаблона, созданный Jobs.submit.
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

    def analyze(self, workload, output, series=None):
        if not self.cli.is_file():
            raise RuntimeError("CLI не собран. Выполните ./gradlew installDist в корне проекта.")
        env = os.environ.copy()
        port = int(env.get("FRONTEND_DB_PORT", "55433"))
        # Никогда не наследуем адрес общей БД из окружения пользователя.
        env["INDEXBUDGET_DATABASE_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/frontend_demo?user=frontend_reader"
        args = [str(self.cli), "analyze-series" if series else "analyze", "--workload", str(workload), "--output-dir", str(output)]
        timeout = 360
        if series:
            workload_input = read_json(workload)
            args += ["--series", str(series), "--analysis-mode", workload_input.get("analysis_mode", "normal"),
                     "--candidate-ranking", workload_input.get("candidate_ranking", "frequency")]
            timeout = read_json(series).get("timeout_seconds", 600) + 60
        return self.command(args, timeout=timeout, env=env)


class Jobs:
    def __init__(self, directory, runner, templates):
        self.directory, self.runner, self.templates = directory, runner, templates
        self.lock = threading.RLock()
        self.accepting = True
        self.items = {}
        self.queue = queue.Queue()
        (directory / "jobs").mkdir(parents=True, exist_ok=True)
        (directory / "reports").mkdir(exist_ok=True)
        for path in (directory / "jobs").glob("*/status.json"):
            item = read_json(path)
            if item["status"] not in TERMINAL:
                item.update(status="failed", error="Сервер перезапущен. Отправьте запуск повторно.")
                # Resume observation of saved series artifacts, never rerun against a changed dataset.
                run_id = item["run_id"]
                published = directory / "reports" / run_id
                staged = path.parent / "export"
                saved = published if published.is_dir() else staged
                if (saved / "series.json").is_file() and (saved / "report.html").is_file():
                    try:
                        summary = read_json(saved / "series.json")
                        if saved == staged:
                            staged.rename(published)
                        item.update(status="partial", report_url=f"/reports/{run_id}/report.html",
                                    series_url=f"/reports/{run_id}/series.json", points=summary.get("points", []),
                                    error="Сервер перезапущен. Сохранены опубликованные результаты серии.")
                    except (OSError, ValueError):
                        pass
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
        # Фиксируем серверный шаблон до очереди: клиент не поставляет DDL/seed,
        # а обновление шаблонов не изменяет уже принятый запуск и его артефакты.
        template = self.templates[request["template_id"]]
        request.update({key: copy.deepcopy(template[key]) for key in ("schema_sql", "seed_sql")})
        id = str(uuid4())
        item = {"run_id": id, "kind": kind, "status": "queued", "created_at": time.time(),
                "template_id": request["template_id"], "launch_mode": "series" if "series" in request else "single", "report_url": None, "error": None}
        with self.lock:
            if not self.accepting:
                raise ShuttingDownError("Сервер обновляется. Дождитесь перезапуска и повторите отправку.")
            write_json(self.directory / "jobs" / id / "request.json", request)
            self.items[id] = item
            self.update(id)
            self.queue.put(id)
        return self.get(id)

    def begin_shutdown(self):
        # В одной критической секции с фиксацией и постановкой задания в очередь.
        # Поэтому после возврата queue.join не может пропустить поздний submit.
        with self.lock:
            self.accepting = False

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
        if "series" in request:
            write_json(folder / "series-request.json", request["series"])
            stop = threading.Event()
            def observe():
                while not stop.wait(0.2):
                    try:
                        saved = read_json(staging / "series.json")
                        self.update(id, points=saved.get("points", []), series_status=saved.get("status"))
                    except (OSError, ValueError):
                        pass
            observer = threading.Thread(target=observe, daemon=True)
            observer.start()
            try:
                try:
                    code, stdout, stderr = self.runner.analyze(folder / "workload.json", staging, folder / "series-request.json")
                except RuntimeError as error:
                    if not (staging / "series.json").is_file() or not (staging / "report.html").is_file():
                        raise
                    code, stdout, stderr = 1, "", str(error)
            finally:
                stop.set()
                observer.join()
        else:
            code, stdout, stderr = self.runner.analyze(folder / "workload.json", staging)
        (folder / "cli.log").write_text(stdout + "\n" + stderr)
        if "series" in request:
            series_path = staging / "series.json"
            saved = read_json(series_path) if series_path.is_file() else {}
            if not saved or not (staging / "report.html").is_file():
                raise RuntimeError("Серия не опубликовала пригодный отчёт: " + (stderr.strip()[-1000:] or str(code)))
            write_json(staging / "workload.json", request["workload"])
            write_json(staging / "series-request.json", request["series"])
            write_json(staging / "schema.json", catalog)
            write_json(staging / "demo-config.json", {**{k: v for k, v in request.items() if k != "workload"}, "run_id": id, "series_id": saved.get("series_id")})
            staging.rename(self.directory / "reports" / id)
            status = "completed" if code == 0 and saved.get("status") == "completed" else (
                "partial" if any(p.get("artifacts", {}).get("report.html") for p in saved.get("points", [])) else "failed")
            self.update(id, status=status, points=saved.get("points", []), cli_exit_code=code,
                        error=(stderr.strip()[-3000:] or "Серия остановлена до получения результатов.") if status == "failed" else None,
                        report_url=f"/reports/{id}/report.html", series_url=f"/reports/{id}/series.json", finished_at=time.time())
            return
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
        def setup(self):
            super().setup()
            # Зависший клиент не должен бесконечно удерживать штатную остановку.
            self.connection.settimeout(15)

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
                links = "".join(
                    f'<li><a href="/reports/{p.name}/report.html">{html.escape(p.name)}</a> '
                    f'<a href="/reports/{p.name}/{"series.json" if (p / "series.json").is_file() else "report.json"}">JSON</a>'
                    + (f' · <a href="/reports/{p.name}/indexes.sql">SQL</a>' if not (p / "series.json").is_file() else '')
                    + '</li>' for p in entries if p.is_dir())
                page = '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><link rel="stylesheet" href="/style.css"><title>Отчёты · IndexBudget</title><main class="archive"><a href="/">← К настройке</a><h1>Отчёты</h1><p>Локальные опубликованные запуски</p><ul>' + (links or "<li>Пока нет отчётов</li>") + "</ul></main></html>"
                self.send(200, page.encode(), "text/html; charset=utf-8")
            elif path.startswith("/reports/"):
                parts = path.strip("/").split("/")
                target = report_artifact(jobs.directory, parts)
                if target is not None:
                    self.file(target)
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
                with jobs.lock:
                    if not jobs.accepting:
                        raise ShuttingDownError("Сервер обновляется. Дождитесь перезапуска и повторите отправку.")
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 3 * 1024 * 1024:
                    raise ValueError("Размер запроса должен быть от 1 байта до 3 MiB.")
                data = json.loads(self.rfile.read(length), parse_constant=lambda v: (_ for _ in ()).throw(ValueError("Некорректное число JSON.")))
                item = jobs.submit(data, "schema" if path.endswith("schema-previews") else "analysis")
                self.send(202, {**item, "status_url": f"/api/runs/{item['run_id']}"})
            except ShuttingDownError as error:
                self.send(503, {"error": str(error)})
            except TimeoutError:
                self.send(408, {"error": "Превышено время получения запроса."})
            except (ValueError, TypeError, UnicodeError) as error:
                self.send(400, {"error": str(error)})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    templates = {t["id"]: t for t in (read_json(p) for p in sorted((ROOT / "templates").glob("*.json")))}
    directory = data_directory()
    # Один исполнитель на рабочую БД, даже если случайно запустили второй сервер.
    import fcntl
    with (directory / "server.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.exit(1, "Локальный сервер уже запущен.\n")
        jobs = Jobs(directory, LocalRunner(args.host), templates)
        server = FrontendHTTPServer((args.host, args.port), make_handler(jobs, templates))
        print(f"IndexBudget → http://{args.host}:{server.server_port}", flush=True)
        stopping = threading.Event()
        def stop(_signum, _frame):
            if stopping.is_set():
                return
            stopping.set()
            jobs.begin_shutdown()
            # shutdown ждёт serve_forever и не может вызываться в его же потоке.
            threading.Thread(target=server.shutdown, daemon=True).start()
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        try:
            server.serve_forever()
        finally:
            jobs.begin_shutdown()
            print("\nЗавершение очереди…", flush=True)
            # Сначала завершаются обработчики HTTP, затем все принятые задания.
            # .local/server.lock остаётся захваченным вплоть до публикации отчётов.
            server.server_close()
            jobs.queue.join()


if __name__ == "__main__":
    main()
