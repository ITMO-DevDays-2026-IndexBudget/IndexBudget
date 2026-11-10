# Формат отчёта v2

Нормативные требования к поведению — [спецификация v0.2, §8](../IndexBudget-spec-v0.2.md#8-архитектура-cli-и-отчёты).
Здесь зафиксирован JSON-контракт реализации #19. `DefaultReportBuilder` собирает общий
`AnalysisReport`; `JsonReportWriter` переводит его в явно заданную схему, а `ReportRenderer`
получает ту же модель для HTML. Сериализация внутренних Kotlin-классов не определяет wire-формат.

JSON записывается в UTF-8. Все перечисленные поля присутствуют, включая поля со значением `null`.
Статус, objective, comparison scope, фазы, направления ключей и `source` записываются строчными
английскими значениями. Устойчивые коды ошибок, предупреждений и причин сохраняют верхний регистр.
Числа costs — единицы планировщика, размеры — байты, длительности — миллисекунды, write units —
условная модель изменений строк × число ключевых колонок в час. Cost не является временем или SLA.

## Корневые поля `report.json`

| Поле | Тип и смысл |
| --- | --- |
| `schema_version`, `spec_version` | `2` и `"0.2"` |
| `run_id` | ID одного запуска, общий для JSON/HTML/SQL |
| `input_hash` | SHA-256 исходного валидированного JSON; `null`, если hash не был получен при failed |
| `duration_millis` | Длительность до сборки модели; время записи файлов сюда не входит |
| `environment` | Версии окружения, см. ниже |
| `status` | `completed`, `partial` или `failed` |
| `selected_objective` | `robust` или `mean`, критерий, управлявший поиском |
| `comparison_scope` | Всегда `evaluated_sets`: оба incumbents из одного исследованного пула |
| `incumbents_coincide` | Совпадают ли отсортированные IDs двух наборов; `null` для failed |
| `price_of_robustness` | Готовая разность `C_mean(best_robust) - C_mean(best_mean)`; `null` для failed |
| `profiles` | Исходный порядок профилей; поля `id`, `weight`, `normalized_weight`, `calls_per_hour` |
| `budgets` | `storage_budget_bytes`, `write_budget_units_per_hour`, `max_indexes` |
| `table_write_rates` | Объект по физическим `schema.table` либо `null`; значения содержат `inserts_per_hour`, `updates_per_hour`, `deletes_per_hour`, `source` |
| `queries` | Общий список SQL в исходном порядке; поля `id`, `sql`, `baseline_cost`, `selected_cost`, `regression` |
| `baseline` | Сохранённая полная оценка пустого гипотетического набора; `null`, если полного baseline нет |
| `selected` | Та же конфигурация, что `best_robust` или `best_mean` по выбранному objective; `null` для failed |
| `best_robust`, `best_mean` | Полные сохранённые конфигурации из общего пула; `null` для failed |
| `selected_indexes` | Определения только выбранных индексов, в порядке ID; пустой массив при пустом наборе или failed |
| `candidates` | Все переданные pipeline кандидаты, в том числе исключённые, в порядке ID |
| `candidate_diagnostics` | Ограничения и статистика генерации, см. ниже |
| `diagnostics` | Фазы, остановки, счётчики и диагностика поиска, см. ниже |
| `error` | Основная ошибка failed: `code`, `message`, `query_id`, `profile_id`, `details`; иначе `null` |

Если вход недоступен, `profiles`, `queries`, `budgets` и `table_write_rates` равны `null`.
При отсутствии корректного нормированного веса его поле — `null`. Для запроса `regression` равно
`true`, только если сохранённый selected cost больше baseline cost, `false` — если сравнение
выполнено и роста нет, `null` — если нет одного из costs. Per-query проценты ускорения не вычисляются.

`environment` содержит `application_version`, `java_version`, `kotlin_version`, `postgres_version`,
`hypopg_version`. Неизвестные версии остаются `null`; PostgreSQL/HypoPG берутся из фактов сессии,
сохранённых до её закрытия. Отчёт не подключается к серверу для получения версий.

Hash вычисляет `WorkloadReader` до применения DTO defaults: рекурсивно сортируются ключи JSON-объектов,
убираются разделяющие пробелы, сохраняются порядок массивов и содержимое SQL-строк. В builder передаётся
готовый `RunContext.inputHash`; повторная сериализация DTO не используется как источник hash.

## Конфигурации и метрики

`baseline`, `selected`, `best_robust`, `best_mean` имеют одинаковую структуру:

```json
{
  "candidate_ids": [],
  "cost_by_query_id": {"q1": 100.0},
  "plans_by_query_id": {"q1": [{"Plan": {"Total Cost": 100.0}}]},
  "metrics": null
}
```

Это пример сохранённого baseline у failed: полные costs/plans есть, но корректные метрики могли
не получиться. В completed/partial `metrics` присутствует. `plans_by_query_id` содержит разобранный
JSON сохранённого EXPLAIN, а не строку с JSON внутри; невалидный JSON плана вызывает ошибку экспорта.
Экспорт не запускает новый EXPLAIN. Costs/plans покрывают каждый общий query ID ровно один раз.

`metrics` содержит `profiles`, `baseline_mean_cost`, `mean_cost`, `mean_ratio`, `worst_ratio`,
`budget_usage`. Каждая запись в `profiles` (порядок по `profile_id`) содержит `profile_id`,
`normalized_weight`, `baseline_cost`, `cost`, `gain`, `ratio`, `gain_percent`. При нулевом baseline
`ratio` и `gain_percent` равны `null`, абсолютные значения сохраняются. Все вычисления и допустимость
принадлежат общему `MetricsCalculator`; builder не пересчитывает метрики incumbents.

`budget_usage`: `estimated_bytes`, `budgeted_bytes`, `write_units_per_hour`, `index_count`,
`size_safety_factor` (1.2). Расходы взяты из общей реализации бюджетов, включая округление запаса
по каждому индексу. Без профиля записи `write_units_per_hour` равен `null`, в том числе у пустого набора.

Цена устойчивости вычисляется единственным доменным `PriceOfRobustness.calculate(SearchResult)`
по сохранённым абсолютным mean costs. Helper и его проверки реализованы в [PR #52](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/pull/52)
(#44); отчёт использует этот общий расчёт. Разность не округляется и не ограничивается снизу нулём; оба incumbents
выбирает Optimizer. Совпадение наборов не означает два независимых поиска или глобальный оптимум.

## Индексы и диагностика

Элемент `candidates`/`selected_indexes` содержит `id`, `definition`, `source_query_ids`,
`generation_reasons`, `ddl`, `estimated_bytes`, `budgeted_bytes`, `write_units_per_hour`.
`definition` содержит `table: {schema, name}`, `method`, `unique`, `keys`. Ключ содержит
`column: {table: {schema, name}, name}`, `direction`, `nulls`, `collation`, `operator_class`.
Порядок ключей сохраняется. `source_query_ids` и `generation_reasons` отсортированы.

DDL формирует общий `PostgresDdlRenderer` из разрешённого определения. Для выбранных индексов
каталожные defaults обязательны. У ранее исключённого кандидата без defaults `ddl` остаётся `null`.
Неизвестные размеры/write units остаются `null`; кандидатам не приписывается cost всей конфигурации.

`candidate_diagnostics` содержит:

- `column_limit_per_query_table`, `local_candidate_limit`, `global_candidate_limit` (16/32/16);
- `candidates_before_global_deduplication`, `candidates_after_global_deduplication`, `shortlisted_candidates`;
- `ungenerated_definitions` — `null`, если точное число неизвестно;
- `local_scopes`: `query_id`, `table`, `discovered_columns`, `retained_columns`, `enumerated_definitions`,
  `deduplicated_definitions`, `retained_definitions`, `excluded_columns` (структуры `ColumnRef`);
- `decisions`, `warnings`.

`diagnostics` содержит `completed_phases`, `stop_reasons`, `statistics`, `limits`, `warnings`,
`errors`, `candidate_decisions`, `unexamined_configurations`. Последнее остаётся `null`, если точное
число неисследованных конфигураций неизвестно. `statistics` содержит `explain_attempts`,
`evaluation_attempts`, `completed_evaluations`, `cache_hits`, `repair_attempts`. `limits` содержит
`command_timeout_millis`, `search_timeout_millis`, `explain_timeout_millis`, `connect_timeout_millis`,
`max_explain_attempts`, `max_repair_attempts`.

Решение о кандидате имеет `reason`, `candidate_id`, `query_id`, `table`, `phase`, `details`.
Отказ всему набору может иметь `candidate_id: null` и список отсортированных IDs через запятую
в `details.candidate_ids`. `MAX_INDEXES` означает нарушение числа индексов, `EVALUATION_FAILED` —
ошибку полной оценки этого набора. `NO_MARGINAL_IMPROVEMENT` относится к конкретному greedy-шагу;
`NOT_SELECTED` и `NOT_EVALUATED_DUE_TO_LIMIT` различают проверенные и не проверенные из-за лимитов
варианты. `unexamined_configurations` при остановке на repair cap считает допустимых ещё
не проверенных соседей fixed S0; при исчерпании соседства равно 0, при прерывании до подсчёта — `null`.
Предупреждение/ошибка имеют `code`, `message`, `query_id`, `profile_id`, `details`.
Неизвестные optional ID/таблица/фаза — `null`, пустые details — `{}`. Содержимое диагностики уже
обезличено у источника: контракты запрещают DSN, пароли и сырые тексты исключений драйвера.

## Согласованность файлов

JSON и HTML получают один `AnalysisReport`; SQL содержит только его выбранный набор и заголовок
с `run_id`/`input_hash`. Для пустого набора CREATE INDEX отсутствует. Для failed SQL не публикуется.
Экспорт повторно проверяет output-dir и готовит файлы во временном каталоге с run ID перед
публикацией. Ошибка записи/рендера/публикации — `EXPORT_FAILED` и exit 1; существующие чужие файлы
не заменяются. Сохранённый partial можно экспортировать после остановки поиска или отмены.
