#!/usr/bin/env python3
"""Физические замеры только в отдельном Compose-проекте indexbudget-experiments."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "-f", str(Path(__file__).with_name("compose.yaml"))]


def psql(sql, env_file):
    result = subprocess.run(COMPOSE + ["--env-file", str(env_file), "exec", "-T", "postgres", "psql", "-XAtq",
                            "-U", "demo_admin", "-d", "indexbudget", "-v", "ON_ERROR_STOP=1"],
                            input=sql, text=True, capture_output=True, check=False)
    if result.returncode:
        # PostgreSQL diagnostics can contain SQL/private connection details.
        raise RuntimeError("Physical measurement failed; transaction was closed and rolled back")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def ident(value):
    if not isinstance(value, str) or not value or "\x00" in value:
        raise ValueError("Invalid index identifier")
    return '"' + value.replace('"', '""') + '"'


def qualified(value):
    # Canonical names can already quote components, including embedded dots/quotes.
    token = r'(?:"(?:[^"\x00]|"")*"|[A-Za-z_][A-Za-z0-9_$]*)'
    match = re.fullmatch(r'\s*(' + token + r')\s*\.\s*(' + token + r')\s*', value)
    if match is None:
        raise ValueError("Expected qualified PostgreSQL name")
    def decode(part):
        return part[1:-1].replace('""', '"') if part.startswith('"') else part.lower()
    return ".".join(ident(decode(part)) for part in match.groups())


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def physical_ddl(candidate):
    definition = candidate["definition"]
    table = definition["table"]
    if table["schema"] != "public" or table["name"] not in {"records", "groups", "wide_records"}:
        raise ValueError("Benchmark accepts only the disposable seed tables")
    if definition["method"] != "btree" or definition["unique"] or not 1 <= len(definition["keys"]) <= 2:
        raise ValueError("Expected a nonunique B-tree with one or two columns")
    keys = []
    for key in definition["keys"]:
        if key["column"]["table"] != table or key["direction"] not in {"asc", "desc"} or key["nulls"] not in {"first", "last"}:
            raise ValueError("Invalid key definition")
        text = ident(key["column"]["name"])
        if key["collation"] is not None:
            text += " COLLATE " + qualified(key["collation"])
        if key["operator_class"] is not None:
            text += " " + qualified(key["operator_class"])
        keys.append(text + " " + key["direction"].upper() + " NULLS " + key["nulls"].upper())
    name = "ib_physical_" + hashlib.sha256(candidate["id"].encode()).hexdigest()[:16]
    return name, "CREATE INDEX " + ident(name) + " ON " + qualified("public." + table["name"]) + " (" + ", ".join(keys) + ");"


def measure_variant(label, report, round_id, warmup, repetitions, writes, env_file):
    indexes = [physical_ddl(candidate) for candidate in report["selected_indexes"]]
    sql = ["BEGIN; SET LOCAL statement_timeout = '30s'; SET LOCAL lock_timeout = '5s';",
           "CREATE FUNCTION pg_temp.measure(q text) RETURNS jsonb LANGUAGE plpgsql AS $$ "
           "DECLARE p jsonb; BEGIN EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || q INTO p; RETURN p->0; END $$;"]
    sql.extend(ddl for _, ddl in indexes)
    for name, _ in indexes:
        sql.append("SELECT json_build_object('kind','size','variant'," + literal(label) +
                   ",'round'," + str(round_id) + ",'index'," + literal(name) +
                   ",'actual_bytes',pg_relation_size(" + literal("public." + name) + "::regclass));")
    operations = [("query", query["id"], query["sql"]) for query in report["queries"]]
    if writes:
        # Each operation has its own SAVEPOINT and restores identical logical rows.
        operations += [
            ("write", "records_insert_100", "INSERT INTO public.records SELECT i, i % 1000, i % 997, timestamp '2026-01-01', repeat('x',128) FROM generate_series(300001,300100) s(i)"),
            ("write", "records_update_1000", "UPDATE public.records SET value = (value + 1) % 997 WHERE id BETWEEN 10001 AND 11000"),
            ("write", "records_delete_100", "DELETE FROM public.records WHERE id BETWEEN 12001 AND 12100"),
            ("write", "groups_update_10", "UPDATE public.groups SET category = (category + 1) % 10 WHERE id BETWEEN 10 AND 19"),
        ]
    for kind, query_id, query in operations:
        for sample in range(warmup + repetitions):
            sql.append("SAVEPOINT sample;")
            sql.append("SELECT json_build_object('kind'," + literal(kind) + ",'variant'," + literal(label) +
                       ",'round'," + str(round_id) + ",'query_id'," + literal(query_id) + ",'sample'," + str(sample) +
                       ",'warmup'," + ("true" if sample < warmup else "false") + ",'plan',pg_temp.measure(" + literal(query) + "));")
            sql.append("ROLLBACK TO SAVEPOINT sample; RELEASE SAVEPOINT sample;")
    sql.append("ROLLBACK;")
    return psql("\n".join(sql), env_file)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", nargs="+", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--confirm-disposable", action="store_true", required=True)
    parser.add_argument("--write-benchmark", action="store_true")
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--repetitions", type=int, default=7)
    args = parser.parse_args()
    if args.warmup < 1 or args.repetitions < 2 or args.output.exists():
        parser.error("Need warmup >=1, repetitions >=2 and a new output path")
    # No host/DSN option: only the explicitly named disposable Compose project is reachable.
    guard = psql("SELECT json_build_object('disposable',disposable) FROM demo_meta.experiment_guard;", args.env_file)
    if guard != [{"disposable": True}]:
        raise RuntimeError("Disposable experiment marker is absent")
    versions = psql("SELECT json_build_object('postgres',current_setting('server_version'),"
                    "'hypopg',(SELECT extversion FROM pg_extension WHERE extname='hypopg'),"
                    "'seed',(SELECT version FROM demo_meta.seed_status WHERE ready),"
                    "'physical_indexes',(SELECT count(*) FROM pg_indexes WHERE schemaname='public')); ", args.env_file)[0]
    reports = [(path.parent.name, json.loads(path.read_text())) for path in args.reports]
    if len({name for name, _ in reports}) != len(reports):
        raise ValueError("Report parent directories must have unique names")
    first = reports[0][1]
    for _, report in reports:
        if report["status"] == "failed" or [(q["id"], q["sql"]) for q in report["queries"]] != [(q["id"], q["sql"]) for q in first["queries"]]:
            raise ValueError("Reports must be usable and have identical shared SQL")
    baseline = dict(first, selected_indexes=[])
    variants = [("baseline", baseline)] + reports
    records = []
    order = []
    for round_id in range(2):
        for label, report in variants if round_id == 0 else list(reversed(variants)):
            order.append({"round": round_id, "variant": label})
            records += measure_variant(label, report, round_id, args.warmup, args.repetitions, args.write_benchmark, args.env_file)
    groups = {}
    for row in records:
        if row["kind"] in {"query", "write"} and not row["warmup"]:
            groups.setdefault((row["variant"], row["kind"], row["query_id"]), []).append(row["plan"]["Execution Time"])
    summaries = [{"variant": key[0], "kind": key[1], "query_id": key[2], "samples": len(samples),
                  "median_execution_ms": statistics.median(samples), "min_execution_ms": min(samples),
                  "max_execution_ms": max(samples), "stdev_execution_ms": statistics.stdev(samples)}
                 for key, samples in groups.items()]
    after = psql("SELECT json_build_object('physical_indexes',count(*)) FROM pg_indexes WHERE schemaname='public';", args.env_file)[0]
    if after["physical_indexes"] != versions["physical_indexes"]:
        raise RuntimeError("Physical index cleanup check failed")
    result = {"schema_version": 1, "environment": versions, "order": order, "warmup": args.warmup,
              "repetitions_per_round": args.repetitions, "rounds": 2, "samples": records, "summary": summaries,
              "report_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest()
                                for (name, _), path in zip(reports, args.reports)},
              "limitations": ["EXPLAIN ANALYZE execution time includes instrumentation overhead; warm-cache experiment only.",
                              "Each variant builds physical indexes inside a transaction and rolls it back.",
                              "Write samples roll back rows via SAVEPOINT; caches, WAL and physical dead tuples are not restored.",
                              "The fixed write microbenchmark is not a replay of hourly rates; no production write slowdown claim.",
                              "Data are synthetic; this experiment provides no SLA or universal speedup."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Physical experiment failed; verify disposable Compose state and input reports.", file=sys.stderr)
        sys.exit(1)
