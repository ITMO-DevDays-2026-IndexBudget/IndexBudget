"""Доверенный frontend-датасет из общей библиотеки demo/scenarios."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import re


SCENARIO_TEMPLATE_ID = "04-scenarios"
INITIAL_QUERY_IDS = ("records_by_group", "records_by_value", "category_join")
QUERY_TABLES = {id: ["public.records"] for id in (
    "records_by_group", "records_by_value", "time_window", "group_list", "alternative_filters",
    "all_records", "update_payload", "delete_recent")}
QUERY_TABLES["category_join"] = ["public.records", "public.groups"]


def read_json(path):
    return json.loads(path.read_text())


def seed_statements(source, table):
    """Выделить ровно два известных блока seed v1, не исполняя его роли/psql."""
    prefix = re.escape("public." + table)
    statements = []
    for expression in (rf"^CREATE TABLE {prefix} \(\n.*?^\);", rf"^INSERT INTO {prefix}\n.*?;"):
        matches = re.findall(expression, source, re.MULTILINE | re.DOTALL)
        if len(matches) != 1:
            raise ValueError(f"Ожидается один блок seed v1 для public.{table}; проверьте frontend-адаптер.")
        statements.append(matches[0])
    return tuple(statements)


def scenario_template(repository, records_template):
    directory = Path(repository) / "demo/scenarios"
    catalog = read_json(directory / "catalog.json")
    workload = read_json(directory / "workload.json")
    if (catalog.get("schema_version") != 1 or catalog.get("kind") != "demo_sql_catalog"
            or catalog.get("workload") != "workload.json"
            or catalog.get("dataset", {}).get("id") != "records-groups-v1"
            or catalog["dataset"].get("seed") != "demo/postgres/init/001-seed.sql"
            or catalog["dataset"].get("seed_version") != "v1"):
        raise ValueError("Неподдержанная версия общей библиотеки demo/scenarios.")
    seed_path = Path(repository) / catalog["dataset"]["seed"]
    seed = seed_path.read_text()
    records_schema, records_seed = seed_statements(seed, "records")
    groups_schema, groups_seed = seed_statements(seed, "groups")
    queries = {query["id"]: query for query in workload["queries"]}
    example_ids = [example["query_id"] for example in catalog["examples"]]
    if (len(queries) != len(workload["queries"]) or len(set(example_ids)) != len(example_ids)
            or set(example_ids) != set(queries) or set(queries) != set(QUERY_TABLES)):
        raise ValueError("SQL и метаданные общей библиотеки не согласованы.")
    guards = workload["constraints"].get("query_cost_guards", {})
    examples = []
    for metadata in catalog["examples"]:
        query = queries[metadata["query_id"]]
        kind = query["sql"].split(None, 1)[0].lower()
        if kind not in {"select", "update", "delete"}:
            raise ValueError("Библиотека frontend допускает только SELECT/UPDATE/DELETE.")
        example = {"id": query["id"], "sql": query["sql"], "statement_kind": kind,
                   "tables": list(QUERY_TABLES[query["id"]]),
                   **{key: metadata[key] for key in ("name", "purpose", "syntax_hint")},
                   "calls_per_hour": {profile["id"]: profile["calls_per_hour"][query["id"]]
                                      for profile in workload["profiles"]}}
        if query["id"] in guards:
            example["cost_guard"] = guards[query["id"]]
        examples.append(example)
    initial = copy.deepcopy(workload)
    initial["index_methods"] = ["btree", "brin", "hash"]
    initial["queries"] = [copy.deepcopy(queries[id]) for id in INITIAL_QUERY_IDS]
    for profile in initial["profiles"]:
        profile["calls_per_hour"] = {id: profile["calls_per_hour"][id] for id in INITIAL_QUERY_IDS}
    initial["constraints"]["query_cost_guards"] = {
        id: limit for id, limit in guards.items() if id in INITIAL_QUERY_IDS}
    records_catalog = copy.deepcopy(records_template["catalog"]["tables"][0])
    records_catalog["rows"] = catalog["dataset"]["tables"]["public.records"]
    groups_catalog = {
        "name": "public.groups", "rows": catalog["dataset"]["tables"]["public.groups"],
        "columns": [
            {"name": "id", "type": "integer", "primary": True, "nullable": False},
            {"name": "category", "type": "integer", "primary": False, "nullable": False},
            {"name": "name", "type": "text", "primary": False, "nullable": False},
        ],
        "indexes": ["CREATE UNIQUE INDEX groups_pkey ON public.groups USING btree (id)"],
    }
    return {
        "id": SCENARIO_TEMPLATE_ID, "version": 1, "name": "Чтение и запись", "tag": "Библиотека SQL",
        "description": "Записи и группы. Девять примеров SELECT, UPDATE и DELETE для обычного дня и пика.",
        "accent": "mint", "schema_sql": records_schema + "\n\n" + groups_schema,
        "seed_sql": records_seed + "\n\n" + groups_seed,
        "analysis_role": "frontend_scenarios", "workload": initial,
        "examples": examples, "example_profiles": copy.deepcopy(catalog["profiles"]),
        "example_source": {"dataset_id": catalog["dataset"]["id"], "seed_version": "v1",
                           "seed_sha256": hashlib.sha256(seed_path.read_bytes()).hexdigest()},
        "catalog": {"tables": [records_catalog, groups_catalog], "relations": []},
    }


def load_templates(frontend):
    frontend = Path(frontend)
    templates = {template["id"]: template for template in
                 (read_json(path) for path in sorted((frontend / "templates").glob("*.json")))}
    templates[SCENARIO_TEMPLATE_ID] = scenario_template(frontend.parent, templates["01-records"])
    return templates
