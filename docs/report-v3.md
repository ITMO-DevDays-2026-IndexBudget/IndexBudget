# Формат отчёта v3

Нормативные требования к поведению — [спецификация v0.2, §8](../IndexBudget-spec-v0.2.md#8-архитектура-cli-и-отчёты).
Здесь зафиксирован JSON-контракт реализации #19 с расширениями INCLUDE #79 и сводки #68. `DefaultReportBuilder` собирает общий
`AnalysisReport`; `JsonReportWriter` переводит его в явно заданную схему, а `ReportRenderer`
получает ту же модель для HTML. Сериализация внутренних Kotlin-классов не определяет wire-формат.

JSON записывается в UTF-8. Все перечисленные поля присутствуют, включая поля со значением `null`.
Статус, objective, comparison scope, фазы, направления ключей и `source` записываются строчными
английскими значениями. Устойчивые коды ошибок, предупреждений и причин сохраняют верхний регистр.
Числа costs — единицы планировщика, размеры — байты, длительности — миллисекунды, write units —
условная модель изменений строк × (число ключевых + число INCLUDE-колонок) в час. Cost не является временем или SLA.

Отчёт v3 несовместим с reader v2, который видит только ключи индекса. Обновлённый reader должен сохранять `definition.included_columns` при воспроизведении DDL и учитывать новую модель записи. Вход workload остаётся v2; старые отчёты не перезаписываются и не принимаются экспортёром как v3.

## Корневые поля `report.json`

| Поле | Тип и смысл |
| --- | --- |
| `schema_version`, `spec_version` | `3` и `"0.2"` |
| `run_id` | ID одного запуска, общий для JSON/HTML/SQL |
| `input_hash` | SHA-256 исходного валидированного JSON; `null`, если hash не был получен при failed |
| `duration_millis` | Длительность до сборки модели; время записи файлов сюда не входит |
| `environment` | Версии окружения, см. ниже |
| `status` | `completed`, `partial` или `failed` |
| `analysis_mode` | Эффективный режим `fast`, `normal` или `deep`, с учётом приоритета CLI над JSON; доступен и для failed |
| `candidate_ranking` | Эффективный рейтинг `frequency` или экспериментальный `baseline_cost` с учётом CLI; сохраняется при completed/partial/failed и раннем завершении до shortlist |
| `selected_objective` | `robust` или `mean`, критерий, управлявший поиском |
| `comparison_scope` | Всегда `evaluated_sets`: оба incumbents из одного исследованного пула |
| `incumbents_coincide` | Совпадают ли отсортированные IDs двух наборов; `null` для failed |
| `price_of_robustness` | Готовая разность `C_mean(best_robust) - C_mean(best_mean)`; `null` для failed |
| `summary` | Сводка рекомендации из сохранённых данных; присутствует и при failed |
| `profile_comparisons`, `query_effects` | Готовые серии baseline/selected/best_robust/best_mean по профилям и SQL |
| `workload_provenance` | Метаданные наблюдений из входного `provenance`, либо `null` |
| `profiles` | Исходный порядок профилей; поля `id`, `weight`, `normalized_weight`, `calls_per_hour` |
| `budgets` | `storage_budget_bytes`, `write_budget_units_per_hour`, `max_indexes`, `query_cost_guards` (эхо заданных пределов) |
| `cost_guards` | Ограничения критических SQL (#78): `basis: "planner_cost_ratio"`, подпись «не latency и не SLA», `max_ratio_by_query_id` и `outcomes` для выбранного набора: `query_id`, `max_ratio`, `baseline_cost`, `selected_cost`, `observed_ratio` (`null` при нулевом baseline), `zero_baseline`, `violated`. Пусто без guards; при failed без выбранного набора `outcomes` пуст |
| `write_proxy_model` | Всегда `row_changes_times_key_and_include_columns`; payload оплачивается как ключ |
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
выполнено и роста нет, `null` — если нет одного из costs. Проценты снижения planner cost
передаются отдельно в `query_effects`; это не проценты ускорения.

`environment` содержит `application_version`, `java_version`, `kotlin_version`, `postgres_version`,
`hypopg_version`. Неизвестные версии остаются `null`; PostgreSQL/HypoPG берутся из фактов сессии,
сохранённых до её закрытия. Отчёт не подключается к серверу для получения версий.

Hash вычисляет `WorkloadReader` до применения DTO defaults: рекурсивно сортируются ключи JSON-объектов,
убираются разделяющие пробелы, сохраняются порядок массивов и содержимое SQL-строк. В builder передаётся
готовый `RunContext.inputHash`; повторная сериализация DTO не используется как источник hash.
CLI `--analysis-mode` и `--candidate-ranking` меняют эффективные настройки запуска, сохраняя hash исходного JSON. `candidate_ranking` — совместимое добавочное поле выходной схемы 3; вход workload остаётся v2. Прежний вход без этого поля выбирает `frequency`; в старых отчётах v2/v3 без поля рейтинг неявно равен `frequency`. Изменение рейтинга не меняет модель INCLUDE или `write_proxy_model`.
`analysis_mode` — добавочное поле выходной схемы 3; вход workload остаётся v2,
и прежний вход без этого поля выбирает `normal`.
Читатели отчётов без поля режима могут трактовать режим как `normal`; фактические
ограничения берутся из `diagnostics.limits`.

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
`generation_reasons`, `source_branches`, `ddl`, `estimated_bytes`, `budgeted_bytes`, `write_units_per_hour`.
`source_branches` (#74) — массив `{query_id, condition_path}` по query ID / пути: `condition/L/R/...`
для листа разрешённого AND/OR-дерева, `order_by` для безусловной сортировки. Это объединение
породивших вхождений, не один совместный DNF-терм. Без OR массив пуст. INCLUDE-вариант
наследует источники своих ключей; payload обозначен отдельно в определении и причине
`COVERING_PROJECTION`. Добавление provenance не меняет ID/DDL или рейтинг по уникальным SQL.
`definition` содержит `table: {schema, name}`, `method`, `unique`, `keys`, `included_columns`. Ключ содержит
`column: {table: {schema, name}, name}`, `direction`, `nulls`, `collation`, `operator_class`.
Порядок ключей сохраняется. `source_query_ids` и `generation_reasons` отсортированы.

DDL формирует общий `PostgresDdlRenderer` из разрешённого определения. Для выбранных индексов
каталожные defaults обязательны. У ранее исключённого кандидата без defaults `ddl` остаётся `null`.
Неизвестные размеры/write units остаются `null`; кандидатам не приписывается cost всей конфигурации.

`candidate_diagnostics` содержит:

- `column_limit_per_query_table`, `local_candidate_limit`, `global_candidate_limit` (16/32/16);
- `candidates_before_global_deduplication`, `candidates_after_global_deduplication`;
- `initial_shortlisted_candidates` — число начальных кандидатов до проверки бюджетов, не более 16;
- `reserve_candidates`, `reserve_candidate_limit` — фактический резерв и его предел 48;
- `max_size_attempts` — предел 32 вызовов оценки размера, включая ошибки;
- `shortlisted_candidates` — прежнее число первоначально отобранных кандидатов, равное `initial_shortlisted_candidates`;
- `preparation` — статистика подготовки, описанная ниже, либо `null`, если стадия не выполнялась;
- `ungenerated_definitions` — `null`, если точное число неизвестно;
- `local_scopes`: `query_id`, `table`, `discovered_columns`, `retained_columns`, `enumerated_definitions`,
  `deduplicated_definitions`, `unindexable_variants`, `retained_definitions`, `excluded_columns` (структуры `ColumnRef`),
  `enumerated_include_definitions`, `retained_include_definitions`;
- `decisions`, `warnings`.

`preparation` содержит:

| Поле | Смысл |
| --- | --- |
| `examined_candidates` | Рассмотренные записи начального списка и резерва, включая ранние отказы |
| `size_attempts` | Фактически начатые вызовы `estimateSize`, включая ошибочные |
| `budget_rejected_candidates` | Кандидаты, отклонённые индивидуальными бюджетами |
| `candidate_errors` | Локальные ошибки гипотез; потеря сессии и deadline сюда не входят |
| `refilled_candidates` | Допустимые кандидаты из резерва, включённые в финальный shortlist |
| `final_shortlisted_candidates` | Число подготовленных допустимых кандидатов |
| `stop_reason` | `SHORTLIST_FULL`, `RESERVE_EXHAUSTED`, `SIZE_LIMIT`, `DEADLINE_EXCEEDED`, `CANCELLED`, `CONNECTION_LOST` либо `FAILED` |

Начальный список и резерв образуют одну детерминированную очередь до 64 записей. В `candidates`
сохраняются все записи этой очереди, в том числе бюджетные отказы, ошибки и непроверенный остаток;
размеры не получавших оценку кандидатов остаются `null`. Поиск получает только допустимый финальный
список до 16. Отказ по write budget может происходить до size-вызова и не создаёт вымышленной оценки
размера. `SIZE_LIMIT` обозначает ограничение подготовки с warning; он сам по себе не требует partial.
Потеря сессии и deadline отражаются также в общей диагностике запуска.

Поля подготовки добавлены аддитивно в выходную схему 3; входной JSON и CLI-параметры не меняются.
Существующий `shortlisted_candidates` сохраняет прежний смысл отбора до подготовки; итоговый
размер передаётся только в новом `preparation.final_shortlisted_candidates`. У прежнего отчёта
без этих полей сведения о refill неизвестны. [Пример наблюдаемых счётчиков](candidate-refill.md).

Диагностика фиксированного пула: `preparation_scope` равен `run` для обычного анализа и
`common_pool` для бюджетной точки. В последнем случае `preparation` сохраняет работу общей
подготовки при максимальном бюджете. Отдельный `fixed_pool_selection` описывает текущую точку:
`pool_candidates`, `admissible_candidates`, `budget_rejected_candidates`, `size_attempts` (всегда 0).
В обычном анализе `fixed_pool_selection` равен `null`. HTML подписывает общую подготовку и
текущую точку отдельно; общий shortlist точки показывает число допустимых для неё кандидатов.
Значение `initial_shortlisted_candidates` по умолчанию наследует `shortlisted_candidates`,
в том числе для producers, заполняющих только прежнее поле.

`diagnostics` содержит `completed_phases`, `stop_reasons`, `statistics`, `limits`, `warnings`,
`errors`, `candidate_decisions`, `unexamined_configurations`. Последнее остаётся `null`, если точное
число неисследованных конфигураций неизвестно. `statistics` содержит `explain_attempts`,
`evaluation_attempts`, `completed_evaluations`, `cache_hits`, `repair_attempts`. `limits` содержит
`command_timeout_millis`, `search_timeout_millis`, `explain_timeout_millis`, `connect_timeout_millis`,
`max_explain_attempts`, `max_repair_attempts`.

Все шесть полей `limits` описывают эффективные пределы выбранного режима, включая failed/partial.
Обычный режим сохраняет прежние значения: 90 000/60 000 мс команды/поиска, 2 000/5 000 мс
одного EXPLAIN/подключения, 2 000 попыток EXPLAIN и 28 новых repair-попыток.
Для `fast` команда/поиск ограничены 30 000/20 000 мс, EXPLAIN — 500 попытками, repair — 8;
для `deep` — 300 000/240 000 мс, 10 000 и 256 соответственно. Таймауты одного EXPLAIN
и подключения одинаковы во всех режимах и дополнительно ограничены оставшимся временем.
Пул остаётся до 16 кандидатов, алгоритм — greedy и repair вокруг фиксированного `S0`.
Граница 1 980 EXPLAIN относится к прежней обычной стратегии; для `deep` действует его
явный лимит. Больший бюджет может оставить качество найденного набора прежним.

Решение о кандидате имеет `reason`, `candidate_id`, `query_id`, `table`, `phase`, `details`.
Отказ всему набору может иметь `candidate_id: null` и список отсортированных IDs через запятую
в `details.candidate_ids`. `MAX_INDEXES` означает нарушение числа индексов, `EVALUATION_FAILED` —
ошибку полной оценки этого набора. `NO_MARGINAL_IMPROVEMENT` относится к конкретному greedy-шагу;
`ZERO_BASELINE_COST_SUPPORT` означает нулевой рейтинг кандидата по baseline cost во всех профилях и отличается от глобального cap. `NOT_SELECTED` и `NOT_EVALUATED_DUE_TO_LIMIT` различают проверенные и не проверенные из-за лимитов
варианты. `unexamined_configurations` при остановке на repair cap считает допустимых ещё
не проверенных соседей fixed S0; при исчерпании соседства равно 0, при прерывании до подсчёта — `null`.
Предупреждение/ошибка имеют `code`, `message`, `query_id`, `profile_id`, `details`.
Неизвестные optional ID/таблица/фаза — `null`, пустые details — `{}`. Содержимое диагностики уже
обезличено у источника: контракты запрещают DSN, пароли и сырые тексты исключений драйвера.

В HTML раздел границ поиска показывает начальный список, резерв, бюджетные отказы, допустимые пополнения, итоговый shortlist, size-вызовы и причину остановки подготовки. Счётчики берутся из той же модели, что JSON; итог показывается через `preparation.final_shortlisted_candidates`, когда статистика подготовки доступна.

## Согласованность файлов

JSON и HTML получают один `AnalysisReport`; SQL содержит только его выбранный набор и заголовок
с `run_id`/`input_hash`. Для пустого набора CREATE INDEX отсутствует. Для failed SQL не публикуется.
Экспорт повторно проверяет output-dir и готовит файлы во временном каталоге с run ID перед
публикацией. Ошибка записи/рендера/публикации — `EXPORT_FAILED` и exit 1; существующие чужие файлы
не заменяются. Сохранённый partial можно экспортировать после остановки поиска или отмены.

В HTML указаны эффективные режим и рейтинг shortlist; `baseline_cost` помечен экспериментальным; раздел границ поиска показывает все шесть лимитов,
фактические пять счётчиков и причины остановки по сохранённой модели.

## INCLUDE (#79)

`definition.included_columns` — обязательный массив объектов `{table: {schema, name}, name}`: 0–2 физических колонки той же таблицы, без ключей и повторов, в каноническом порядке имён. Они не имеют direction/nulls/collation/opclass и не увеличивают арность ключа. `generation_reasons` сохраняет `COVERING_PROJECTION` вместе с причинами исходных ключей.

В `candidate_diagnostics.local_scopes` обязательны `enumerated_include_definitions` и `retained_include_definitions` (целые, включая ноль). Отсечения INCLUDE имеют причины `INCLUDE_PAYLOAD_LIMIT` / `INCLUDE_VARIANT_LIMIT` с `excluded_variants` и `limit`; локальный cap содержит также `include_before`, `include_after`, `include_limit`. Глобальный cap относится ко всем кандидатам независимо от payload.

Карточки и список кандидатов показывают INCLUDE, исходный и бюджетный размер и write units. Возможность index-only scan не обещает отсутствие обращений к таблице. SQL-экспорт использует общий DDL renderer, включая payload.

## Сводка и данные сравнений (#68)

Расширение **аддитивное**: версия отчёта остаётся 3, существующие поля не меняют смысл.
Новый reader должен допускать отсутствие этих четырёх полей в ранее выпущенных отчётах v3;
строгим readers с закрытым списком полей требуется обновить список. Внутри приложения
`AnalysisReport.summary`, `profileComparisons`, `queryEffects`, `workloadProvenance` готовятся
при создании модели, включая `copy`. JSON/HTML только отображают эти значения.

`summary` содержит:

| Поле | Смысл |
| --- | --- |
| `status` | Статус того же запуска |
| `selected_index_ids` | Отсортированные ID выбранных индексов; `[]` для baseline-only, `null` при failed. Определения находятся в корневом `selected_indexes` |
| `mean_effect` | Эффект по `B_mean`/`C_mean`; `ratio` — сохранённое M, не среднее отношений профилей |
| `worst_ratio` | Сохранённое R; `null` при нулевых baseline или отсутствии решения |
| `budget_usage`, `budget_limits` | Те же расходы из domain и ограничения, что в `selected.metrics.budget_usage` и `budgets`. Расходы `null` при failed, ограничения `null`, если вход отсутствует |
| `write_limit_enabled` | `false` при отключённом лимите, `true` при любом числовом лимите, включая 0; `null`, если вход отсутствует |
| `regressed_query_count` | Число общих SQL с `selected_cost > baseline_cost`; каждый SQL считается один раз независимо от профилей. `null` при отсутствии выбранной оценки |
| `comparison_scope`, `incumbents_coincide`, `price_of_robustness` | Те же значения, что в корне; цена в абсолютных единицах взвешенного planner cost |
| `stop_reasons`, `errors` | Сохранённые причины остановки и уникальные ошибки; основная ошибка failed также включена |

`CostEffect` сериализуется как `baseline_cost`, `cost`, `gain`, `ratio`, `gain_percent`,
`relative_status`. `gain = baseline_cost - cost`; положительное число означает снижение cost.
`gain_percent = 100 * gain / baseline_cost` при положительном baseline. Профильные значения
переносятся из готовых `ProfileMetrics` без повторного расчёта. Для среднего эффекта и SQL
используется общий `domain.CostEffect.calculate`, который также использует `MetricsCalculatorImpl`.
Ни builder, ни renderer не вызывают evaluator. Расходы не суммируются из отображаемых карточек
и не получают повторный запас 1.2.

| `relative_status` | Числовые значения |
| --- | --- |
| `available` | Отношение и процент известны; реальный 0 сохраняется |
| `zero_baseline` | Отношение и процент `null`, абсолютные costs и gain сохранены |
| `unavailable` | Нет одного из costs; имеющийся cost сохраняется, gain/ratio/процент `null` |
| `out_of_range` | Производное отношение или процент не представимы конечным Double, включая потерю положительного отношения в ноль; только непредставимые поля `null`, абсолютные costs/gain сохранены |

`out_of_range` относится к дополнительному представлению сохранённых costs: такой SQL может
входить в корректный полный вектор, хотя его индивидуальный процент непредставим.
Это не маскирует ошибки поиска: неконечные профильные метрики или underflow положительного
профильного отношения либо общего M дают `NUMERIC_ERROR`, даже если отношения отдельных
профилей представимы. Реальный M = 0 при C_mean = 0 остаётся допустимым. При `cost > 0` отношение, потерянное в ноль,
сохраняется как `null`/`out_of_range`; представимые subnormal-отношения и реальный ноль
при `cost = 0` остаются числовыми.
Порядок операций процентов сохранён (`100 * gain / baseline`), включая проверку промежуточного
переполнения. При failed численные профильные расчёты не повторяются: baseline costs SQL могут
быть известны при неизвестных baseline-метриках профилей.

`profile_comparisons` и `query_effects` — массивы в исходном порядке profiles/queries.
Каждый элемент имеет `id` и четыре `CostEffect`: `baseline`, `selected`, `best_robust`, `best_mean`.
Все четыре сравниваются с одним исходным baseline. Профильные costs учитывают частоты;
per-query costs — стоимость одного планирования SQL, без умножения на частоты.
При отсутствии входа массивы пусты. HTML при этом сохраняет известные абсолютные costs
и признаки регрессии из `result.baseline`/`result.selected`; отсутствующий процент остаётся
неизвестным. Эти серии предназначены для графиков #69; масштабирование
отрисовки не должно менять числа или выдавать неизвестный эффект за нулевой.

M — отношение ожидаемых стоимостей, R — худшее профильное отношение; R не даёт гарантий
для каждого SQL. Сравнение ограничено `evaluated_sets`. Совпавшие incumbents, регрессии SQL,
partial и failed отображаются явно. Никакой новый EXPLAIN при сборке/экспорте не нужен.

## Происхождение нагрузки (#68)

Необязательное поле `provenance` во входном workload v2 передаёт **метаданные для отчёта**.
Отсутствие поля или `null` сохраняет обычный ручной ввод. CLI-флаги не меняются.
Это не реализация извлечения частот/импорта #66/#67: importer отвечает за правильность
наблюдений и соответствие примеров статистике; reader проверяет форму и связи метаданных.
Импортёр может вложить этот объект из своего манифеста в воспроизводимый workload.
Интеграция с фактическим выходом #67 пока не проверена: для закрытия #68 требуется
сопоставить источник, окна, отбор, охват и происхождение примеров в отчёте с настоящим
манифестом импортёра. Синтетические примеры и тесты передачи метаданных этого не заменяют.

Объект имеет собственный `schema_version: 1` и в той же структуре экспортируется как
`workload_provenance`. Неизвестные поля и иные версии отклоняются до анализа.

- `source`: только `pg_stat_statements`.
- `source_id`: канонический UUID источника снимков #65. URL, хосты, DSN и credentials сюда не передаются.
- `selection_rule`: непустое описание фактически применённого правила, до 1000 символов;
  без секретов. Отчёт не выполняет это правило и не отбирает запросы заново.
- `windows`: ровно одно окно на каждый profile ID, без дубликатов. Каждое содержит `profile_id`,
  `started_at`, `finished_at` (ISO-8601 с UTC offset; конец строго позже начала), `complete` (boolean)
  и `coverage`. На выходе даты приводятся к UTC. `complete: false` явно показывает неполное
  наблюдение и не подменяет статус выполнения анализа.
- `coverage.denominator`: всегда `available_complete_entries`. Это доступные полные записи
  конкретного окна **до отбора**, а не весь трафик сервера.
- `coverage.selected_calls`, `available_calls`: неотрицательные целые int64 либо явный `null`.
- `coverage.selected_exec_time_millis`, `available_exec_time_millis`: конечные неотрицательные
  миллисекунды либо явный `null`. Положительное значение не должно теряться в ноль при чтении.
  Известный selected не превышает известный available. Все четыре поля обязательны;
  неизвестность не заменяется нулём. Охват показан парой «выбрано / доступно», без выдуманного
  процента при неизвестном или нулевом знаменателе.
- `query_examples`: ровно одна запись на каждый query ID, без дубликатов: `query_id`,
  `source` (`user_supplied_literals` — репрезентативный пример пользователя;
  `original_literal_sql` — исходный SQL уже содержит литералы).

Поле входит в общий лимит входа 1 MiB и в исходный input hash. Оно не меняет SQL, частоты,
write rates, критерий или бюджеты. Ошибки валидации не копируют значения произвольных полей.
HTML экранирует описание отбора и IDs; исторические миллисекунды выводятся отдельной таблицей,
без пересчёта в ожидаемое время с новыми индексами. При ручном вводе источник/охват неизвестны.

Пример входа: [workload-v2-provenance.json](../examples/workload-v2-provenance.json).
Пример полного **синтетического** отчёта: [report-v3-provenance.json](../examples/report-v3-provenance.json).
`./gradlew reportPreview` создаёт согласованные HTML/JSON-варианты, включая `imported`,
`zero-baseline`, `regression`, `partial`, `failed`, без обращения к БД. Фикстуры, меняющие
результат или workload, пересобирают производные данные через `DefaultReportBuilder`;
согласованность `queries`, `query_effects` и сохранённых оценок проверяется для всех вариантов.

## Ручное сравнение (#77)

Необязательные `manual_requested_indexes` и `manual_comparison` описывают отдельную
ручную оценку вне автоматического shortlist; без неё оба поля равны `null`.
Статусы, определения, полные costs/метрики, бюджетные нарушения и диагностика описаны
в [контракте ручного сравнения](manual-index-comparison.md#отчёт). Эти данные не меняют
`best_robust`, `best_mean`, `selected`, `price_of_robustness` и автоматический SQL.
