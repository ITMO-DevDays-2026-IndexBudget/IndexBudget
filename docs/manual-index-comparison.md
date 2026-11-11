# Сравнение ручного набора

Дополнительный режим обычного `analyze`: JSON v2 получает необязательное поле
`manual_indexes`. Старые входы сохраняют прежнее поведение. Пример —
[workload-v2-manual.json](../examples/workload-v2-manual.json).

```sh
build/install/indexbudget/bin/indexbudget analyze --workload examples/workload-v2-manual.json --output-dir reports/manual
```

Формат:

```json
"manual_indexes": [
  {"schema":"public","table":"records","keys":[
    {"column":"group_id"},
    {"column":"created_at","direction":"desc","nulls":"first"}
  ]}
]
```

При наличии поля требуется 1–5 определений. Поддержаны nonunique B-tree на 1–2 разных
физических колонках workload-таблиц. Имена — точные физические строки, без SQL-кавычек
и без folding регистра. Direction: asc/desc (default asc); nulls: first/last
(default last для asc, first для desc). Method, unique, predicate, expressions,
INCLUDE, произвольные collation/opclass и текст DDL во входе запрещены. Каталог задаёт
collation/default B-tree opclass. Все остальные поля строгие, как основной JSON v2.
Весь файл, включая ручной набор, входит в прежний лимит 1 MiB и input hash.

Колонки разрешаются через тот же полный CatalogSnapshot до начала baseline. Неизвестная
колонка/таблица отклоняется с UNKNOWN_COLUMN/UNKNOWN_TABLE, exit 2. Отдельные таблицы вне
SQL workload не поддержаны: для них не определён общий профиль записи. Обычные ограничения
SQL, каталога и целевой БД сохраняются.

## Порядок оценки

Сначала выполняется обычный автоматический поиск. Затем на той же физической сессии,
общих исходных SQL/профилях/baseline и оставшемся RunContext оценивается ручной набор.
Точный канонический дубль внутри ручного списка объединяется; точный дубль доступного
реального индекса удаляется с EXACT_EXISTING_DUPLICATE. Префиксы, partial/INCLUDE и
невалидные индексы не считаются точными дублями. Правило реализует общий canonicalizer.

Размер каждого оставшегося ручного кандидата получается через HypoPG; общий calculator
вычисляет запас места и write proxy. Весь набор передаётся в evaluate один раз: эффекты
одиночных индексов не складываются. Превышение бюджета не мешает измерить идею пользователя,
но итог получает admissible=false и полный список бюджетных нарушений. Если все определения
оказались реальными дублями, используется сохранённый baseline без нового EXPLAIN.

Ручной набор не добавляется в автоматический shortlist, не проходит его caps 16/32/16 и не
обновляет best_robust/best_mean, price_of_robustness или indexes.sql. Ручных определений
не более пяти. Стабильные IDs и DDL формирует та же реализация, что для автоматических
кандидатов. Источник ручных кандидатов — manual, без выдуманных source_query_ids и
причин автоматической генерации.

Используются общие 90/60 секунд и лимит 2000 EXPLAIN: отдельный бюджет не выдаётся.
Обычный поиск может исчерпать бюджет и оставить ручной набор not_evaluated/failed.
Глобальный timeout/cancel/потеря соединения сохраняет автоматические incumbents через
существующий partial-механизм. Локальная ошибка создания гипотезы/EXPLAIN сохраняется
в manual_comparison.status=failed с безопасной ошибкой; результат обычного поиска
может оставаться completed. Неполный вектор ручной оценки не сохраняется как результат.
При cleanup-ошибке сохраняются существующие правила pipeline; она не маскируется как
локальная ручная ошибка.

## Отчёт

JSON v2 дополнен optional-данными (null при отсутствии режима):

- manual_requested_indexes — исходные структурированные определения;
- manual_comparison: origin=manual_outside_automatic_shortlist,
  comparison_scope=separate_manual_evaluation, status=not_evaluated/completed/failed,
  admissible, candidates (с отдельными оценками размеров и DDL), decisions,
  configuration (полные costs/plans и raw метрики), budget_violations,
  evaluation_violations, queries (baseline_cost/manual_cost/regression), error.

Raw метрики и zero-baseline violations рассчитывает общий MetricsCalculator.
Неизвестные размеры/метрики остаются null. Admissible=false у всех неоценённых/ошибочных
результатов; это не доказательство бюджетного отказа — причину задают status/error.
Ручной набор, даже допустимый, остаётся отдельной идеей пользователя, а не рекомендацией.

Автономный HTML показывает ручной набор рядом с автоматическими результатами: расходы,
профильные стоимости и per-query регрессии. SQL содержит только автоматическую рекомендацию.
Общие counters отражают весь запуск, включая дополнительную ручную оценку и ошибки;
они не являются количеством наборов, участвовавших в обновлении incumbents.
Сравнение не обещает SLA, физического ускорения, выигрыша от ручного индекса или
неизменности внешних данных/статистики в течение анализа.

## Проверка

```sh
./gradlew --no-daemon build installDist
bash demo/check-manual-comparison.sh
```

Скрипт поднимает собственный одноразовый PostgreSQL 16.15 + HypoPG и не обращается
к общей demo/stand DB. Интеграционный тест создаёт 50000 строк, ANALYZE вне анализа,
выполняет настоящий pipeline и сравнивает manual/recommendation с общим baseline.
Проверяются отдельный JSON/HTML, сохранение автоматического SQL и отсутствие новых
физических индексов. Unit tests покрывают полный набор, канонический/реальный дубль,
неизвестную колонку, бюджеты, ошибку оценки и строгий структурированный вход.
