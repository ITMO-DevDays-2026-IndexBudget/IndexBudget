# Наблюдаемый профиль записи (#107)

Профиль записи — изменённые **строки** в час, а не SQL-вызовы. Из совместимой пары табличных
снимков он получается автоматически по единой формуле и подключается к существующему write
budget без ручного угадывания. Один общий write profile обслуживает весь workload;
1–3 read-профиля не создают дополнительных write-профилей, окна не усредняются и не
максимизируются молча.

## Формула

```
rate = Δcounter × 3600 / Δseconds
```

- `Δcounter` — приращение `n_tup_ins` / `n_tup_upd` / `n_tup_del` между снимками пары;
- `Δseconds` — длительность окна от середины интервала чтения первого снимка до середины
  интервала второго (та же семантика, что у окон частот `pg_stat_statements`);
- арифметика десятичная, результат проверяется на конечность и неотрицательность;
  переполнение — `NUMERIC_ERROR`;
- нулевое приращение — законный нулевой rate, а не отсутствие данных.

Формула write units (`row_changes_times_key_and_include_columns`) остаётся единой в domain
(`MetricsCalculator`) и не зависит от источника rates: одинаковые ручные и наблюдаемые числа
дают одинаковые расходы и решения бюджетов.

## Снимки: `pg_stat_user_tables_snapshot` v1

Пара файлов `before`/`after` — JSON вида:

```json
{
  "schema_version": 1,
  "kind": "pg_stat_user_tables_snapshot",
  "snapshot_id": "uuid",
  "source_id": "uuid",
  "scope": {"database_oid": 16384, "reader_userid": 10, "rows": "current_database_all_schemas"},
  "postgres_version": "16.8",
  "track_counts": true,
  "stats_reset": null,
  "started_at": "2026-11-12T10:00:00Z",
  "finished_at": "2026-11-12T10:00:00Z",
  "status": "completed",
  "reasons": [],
  "row_count": 1,
  "entries": [
    {
      "table_oid": 16385,
      "schema": "public",
      "table": "records",
      "n_tup_ins": 100, "n_tup_upd": 50, "n_tup_del": 10,
      "n_tup_hot_upd": 20
    }
  ]
}
```

Требования к паре: разные `snapshot_id`; одинаковые `database_oid` и `reader_userid`;
PostgreSQL 16; `track_counts: true` (явный `false` — `INVALID_INPUT`); интервалы чтения
упорядочены и не пересекаются; длительность окна положительна; `table_oid` уникален и
сохраняет `schema.table` внутри окна.

`n_tup_hot_upd` — только историческая диагностика: HOT-обновления никогда не вычитаются из
будущей цены индекса и не входят в rates.

## Причины неполноты

| Причина | Смысл |
| --- | --- |
| `INCOMPLETE_SNAPSHOT` | хотя бы один снимок пары собран не целиком (свои лимиты сбора) |
| `STATS_RESET` | изменился общий `stats_reset`: всё окно недостоверно, rates не выдаются |
| `TABLE_MISSING` | таблица отсутствует на одной из сторон: её rate неизвестен, не ноль |
| `COUNTER_DECREASE` | счётчик уменьшился без смены эпохи: PG16 не отличает адресный `pg_stat_reset_single_table_counters`, rate не выдумывается |

Неполное окно не становится успешным полным профилем: rates выдаются только для надёжных
таблиц, полнота и причины сохраняются в workload (`table_write_rates_origin`), манифесте
импорта (`write_profile`) и отчёте. При включённом `write_budget_units_per_hour` неполное
наблюдение — `INVALID_WRITE_PROFILE`: лимит не отключается молча и не подменяется ручными
значениями.

## Подключение к CLI

`import-workload` принимает ровно один источник записи — явный выбор:

```json
{
  "schema_version": 1,
  "kind": "pg_stat_statements_import_config",
  "windows": [{"id": "observed", "weight": 1, "before": "s1.json", "after": "s2.json"}],
  "examples": "examples.json",
  "constraints": {"storage_budget_bytes": 10000000, "write_budget_units_per_hour": 100},
  "table_write_window": {"before": "write-before.json", "after": "write-after.json"}
}
```

`table_write_window` и ручной `table_write_rates` взаимно исключаются. Рассчитанные rates
попадают в workload v2 как обычный `table_write_rates` с `source: "observed"` плюс
`table_write_rates_origin` с окном, полнотой и причинами; `analyze` работает с ним как с
ручным профилем. Наблюдаемые строки никогда не складываются с `calls`/`rows` из
`pg_stat_statements` — двойного учёта DML нет.

Сами снимки собирает отдельная задача [#102](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/102);
экспортированные вручную снимки того же формата также пригодны.
