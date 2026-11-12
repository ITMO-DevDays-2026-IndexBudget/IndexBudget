# Контракты компонентов v0.2

Веб-демо v0.2 (#101) использует отдельный конверт
`{template_id, template_version, workload}` для анализа и просмотра схемы.
Сервер отклоняет клиентские `schema_sql`/`seed_sql` до очереди и фиксирует доверенный
датасет по ID/версии. Ручные частоты SQL в браузере/API — целые 0–1 000 000;
вход CLI и частоты изменения строк не сужаются. [Контракт веб-демо](demo-web-interface.md).

Расчёт частот по выходу коллектора #65: `FrequencyCalculator.calculate` принимает
1–3 `FrequencyWindow`, возвращает частоты и диагностический manifest для #67.
[Ключи, периоды, политика неполноты и формат profiles v1](statistics-frequencies.md).

Модели и интерфейсы задают стыки совместной реализации [#3](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/3) и [#14](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/14). Источник поведения — [спецификация v0.2](../IndexBudget-spec-v0.2.md). Реализованы валидация JSON v2, orchestration, AST-валидация и разрешение SQL, JDBC-каталог, сессия HypoPG, полный ограниченный генератор кандидатов, профильные метрики, расчёт бюджетов, компаратор, проверка допустимости после оценки, жадный поиск и ограниченный repair с кэшем одного запуска. Реализованы сборка отчёта v3 и согласованный экспорт JSON/SQL; для реального запуска ещё нужен HTML-renderer (#20). Проверки orchestration используют подставные реализации недостающих интерфейсов.

Проект пока собирается одним Gradle-модулем. Пакеты `cli`, `application`, `domain`, `sql`, `candidates`, `optimizer`, `postgres` и `report` обозначают границы ответственности. Доменные модели и интерфейсы расчётов не зависят от Spring, JDBC, Jackson и JSqlParser AST.

## Вход и разрешённый workload

`WorkloadInput` содержит общий список `QueryInput` и отдельные `Profile`. Частоты находятся в `Profile.callsPerHour`, а не внутри SQL. В каждом профиле должны быть ровно все query ID. `Profile.weight` сохраняет исходный относительный вес. Общий `ProfileWeights.normalize(profiles)` в domain возвращает карту нормированных весов по profile ID, проверяя конечность суммы и сохранение положительных весов. `WorkloadReader` и `MetricsCalculatorImpl` используют этот helper, без второй формулы нормирования.

- [workload-v2.json](../examples/workload-v2.json): два SQL, два профиля, критерий `robust`, полный профиль записи.
- [workload-v2-no-write.json](../examples/workload-v2-no-write.json): тот же workload, критерий `mean`, выключенный write budget и отсутствующий профиль записи.
- [workload-poc.json](../demo/workload-poc.json): три SQL, два профиля, полный ручной профиль записи для demo-таблицы и оба бюджета. Команда запуска приведена в [README](../README.md#cli); пока она выполняет валидацию и возвращает `NOT_IMPLEMENTED`.

JSON использует `snake_case` и регистрозависимые значения `robust`/`mean`, `fast`/`normal`/`deep`, `frequency`/`baseline_cost`. Kotlin-модели используют `camelCase` и enum. Jackson настраивается на границе приложения; аннотации сериализатора в домен не добавляются. Неизвестные поля, повторяющиеся ключи объекта, неверные типы и trailing content отклоняются. Числовые строки и дробные значения целых полей не приводятся автоматически к нужному типу.

`WorkloadReader.read(path, context)` проверяет размер файла ≤1 MiB до JSON-разбора, UTF-8, `schema_version: 2`, структуру и семантику входа. V1 возвращает `UNSUPPORTED_SCHEMA_VERSION` с подсказкой перенести частоты из queries в profiles. SQL сохраняется без изменений и до передачи SQL-парсеру проверяется на размер ≤64 KiB UTF-8. Превышения дают `INPUT_LIMIT_EXCEEDED`.

Проверяются 1–20 queries и 1–3 profiles, уникальные непустые IDs, точное покрытие query IDs каждым `calls_per_hour`, конечные неотрицательные частоты и положительное использование каждого профиля и каждого SQL. Веса положительны и конечны. `objective` по умолчанию `ROBUST`, допустима также `MEAN`. `WorkloadInput.analysisMode` — `AnalysisMode` (`FAST`, `NORMAL`, `DEEP`), JSON `analysis_mode` необязателен и по умолчанию равен `normal`; явный `null`, неверный тип или неизвестное значение дают `INVALID_INPUT`. Поле добавлено совместимо в v2, прежний вход сохраняет обычные ресурсные пределы. `WorkloadInput.candidateRanking` — `CandidateRanking` (`FREQUENCY`, `BASELINE_COST`), JSON `candidate_ranking` необязателен, default `frequency`; `baseline_cost` экспериментален. Явный `null`, неверный тип и неизвестное значение — `INVALID_INPUT`. Старый вход v2 сохраняет прежний рейтинг. Storage — неотрицательный `Long`; `max_indexes` — целое 0–5, по умолчанию 5. `write_budget_units_per_hour` обязателен: `null` отключает лимит, число 0 сохраняет включённый нулевой лимит.

При включённом write budget профиль записи обязателен; при выключенном он может отсутствовать или быть `null`. Переданный профиль должен быть непустым объектом, каждая запись содержит `inserts_per_hour`, `updates_per_hour`, `deletes_per_hour` — конечные неотрицательные числа. Поле `source`, если передано, равно `manual` или `observed`; при отсутствии действует существующий default `WriteRateSource.MANUAL`. Наблюдаемые rates (изменённые строки в час, а не SQL-вызовы) требуют объект `table_write_rates_origin` с ровно одним окном `{from, to, period_seconds > 0}`, флагом `complete` и `reasons`; ручные rates не переносят его, смешение источников — `INVALID_WRITE_PROFILE`. При включённом write budget неполное окно — `INVALID_WRITE_PROFILE`: наблюдение не отключает лимит и не подменяется ручными значениями. Расчёт наблюдаемых rates из пары табличных снимков — единая реализация `WriteProfileCalculator` (см. [table-write-profile.md](table-write-profile.md)); формула write units в domain едина для обоих источников, двойного учёта с `pg_stat_statements` нет. `ResolvedWorkloadValidator.validate(workload, context)` после resolution сверяет профиль с объединением физических таблиц всех запросов. Частичный профиль и записи для посторонних таблиц недопустимы и при отключённом лимите. Ключ — точная строка `schema.name` из разрешённых имён: регистр сохраняется, SQL-алиасы, кавычки и `search_path` в ключах не интерпретируются. Если разные таблицы дают одинаковый ключ из-за точек в именах, переданный профиль отклоняется с `INVALID_WRITE_PROFILE`. Проверки проходят с общим deadline.

Проверка нормирования использует общий `ProfileWeights`: переполнение суммы и underflow положительного нормированного веса дают `NUMERIC_ERROR` (exit 1). Превращение положительного входного десятичного числа в ноль при чтении также даёт `NUMERIC_ERROR`. Неправильный тип, знак, нулевой вес или число вне конечного диапазона `Double` дают ошибку соответствующего поля (`INVALID_INPUT`/`INVALID_WRITE_PROFILE`, exit 2). Конечность сумм, произведений, отношений и процентов, зависящих от costs, проверяет `MetricsCalculatorImpl.calculate`.

Создание `WorkloadInput` напрямую не выполняет эти проверки. Успешное чтение JSON ещё не подтверждает корректность SQL или полноту профиля по таблицам: синтаксис, поддержанный поднабор и предел 256 сравнений WHERE/JOIN остаются за SQL-анализатором #6, семантическое разрешение и проверка профиля записи — за #8/#9/#14.

`SqlAnalyzer.parse` проверяет синтаксис и собирает имена таблиц. `AnalysisSession.readCatalog` разрешает их в PostgreSQL, затем `SqlAnalyzer.resolve` возвращает `ResolvedWorkload`. AST остаётся внутри реализации парсера. Исходный SQL сохраняется целиком; ограничение кандидатов не удаляет его условия.

AST-валидация отклоняет квалификацию через `..`, `...`, `:`, database link `@` и любой
другой не-точечный разделитель name parts, Oracle-модификаторы `(+)`/`PRIOR` и побитовый `~`
с `UNSUPPORTED_SQL` и `query_id` — до какого-либо изменения `nameParts` в AST. Знаки `+`/`-`
у числовых литералов и поддержанные приведения сохраняются. Точки, `@` и экранированные
кавычки **внутри double-quoted идентификатора** являются частью имени, включая колонки
и алиасы. Для JSqlParser 5.4 адаптер временно защищает quoted-токены с точками от разбиения
при создании AST: каждый такой токен целиком заменяется коротким уникальным плейсхолдером
фиксированной длины (префикс — дайджест SHA-256 входного SQL), а таблица восстановления
возвращает исходные токены, имена и алиасы после парсинга. Размер защищённого представления
линейно зависит от числа dotted-токенов и не растёт от длины имени или коллизий маркера
в комментариях. Исходный SQL и JVM-свойство `SPLIT_NAMES_ON_DELIMITER` не меняются. Имя
таблицы извлекается из `nameParts`, поскольку `Table.getName()` в этой версии обрезает даже
quoted-имя по `@`. Deadline проверяется при подготовке защиты, сразу после внешнего парсера
и до проверки числа и вида statements.

`CatalogSnapshot` сохраняет все нужные колонки таблиц. Лимит 16 колонок относится к shortlist генератора, а не к каталогу. `ResolvedQuery` отдельно хранит проекции, предикаты и сортировку: `SELECT *` не означает «сгенерировать индекс на каждой колонке». [Расширение #103](dml-analysis.md) добавляет statementKind (SELECT/UPDATE/DELETE) и modifiedColumns: SET не является проекцией и не даёт INCLUDE. Assignment-only casts каталога используются только для SET, а не для выбора операторов сравнения. Исходный DML отправляется в EXPLAIN без ANALYZE под подтверждённой серверной read-only защитой; смешанный вектор и общий RunContext не разделяются по kind.

### SQL resolution (#8/#9)

Расширение #73 добавляет WHERE-формы `IN` (1–256 скалярных литералов), обычный положительный
`BETWEEN`, `IS NULL` / `IS NOT NULL`, соединяемые AND/OR (#74). Общий лимит 256 для WHERE/JOIN считает
каждый элемент IN, обе границы BETWEEN и один null-test; лимит проверяется до JDBC.
Неподдержанные варианты (NOT IN/NOT BETWEEN, SYMMETRIC/ASYMMETRIC, подзапрос/выражение в IN,
ISNULL/NOTNULL, null-test над выражением) дают `UNSUPPORTED_SQL` с `query_id`; превышение —
`INPUT_LIMIT_EXCEEDED`. JOIN по-прежнему допускает только равенство колонок.

`ResolvedPredicate.operator` различает `IN`, `BETWEEN`, `IS_NULL`, `IS_NOT_NULL`; `literalCount`
содержит длину списка, 2 или 0 соответственно (для прежних сравнений остаётся `null`).
Предикат не хранит копию SQL/значений и не расширяет IN до нескольких AND-признаков.
Генератор использует `equalityFeature`: IN/IS NULL — equality-like, BETWEEN/IS NOT NULL — range-like.
Это признак для ограниченной генерации, не вывод о селективности, полезности или плане.

`CatalogType.arrayTypeOid` передаёт `pg_type.typarray`, а `elementTypeOid` проверяет тип элемента.
Для многоэлементного IN SQL-адаптер повторяет выбор общего типа RHS массива, но сохраняет
исходный тип левой колонки при разрешении видимого `=`. Если PostgreSQL не может использовать
массив, проверяются отдельные сравнения. Для BETWEEN проверяются обе видимые сигнатуры,
для null-tests — только сама физическая колонка. Исходные SQL, NULL, порядок границ/списка
и приведения остаются нетронутыми; SQL проверяется настоящим EXPLAIN, не симуляцией значений.
Входной JSON workload v2 не меняется; новые поля SQL resolution относятся к внутренним DTO.

Исправления quoted-имён/модификаторов из #33/PR #62 остаются отдельной реализацией:
новые формы используют те же `validateColumn`/проверку литералов и `resolveColumn`, а не свой
нормализатор имён. Полный HypoPG → search → HTML/JSON/SQL сценарий новых форм включён в
`JdbcAnalysisSessionIntegrationTest`; обычные real-catalog/EXPLAIN проверки — в
`SqlResolutionIntegrationTest`.

`JSqlParserSqlAnalyzer.resolve(parsed, catalog, context)` разрешает проверенный AST без JDBC
и возвращает настоящий `ResolvedWorkload`. Таблицы сопоставляются по физическим именам из
`CatalogSnapshot`; для unqualified-ссылки обязателен эффективный `searchPath` сессии.
Пустой путь не заменяется догадкой `public`: для такой ссылки это `CATALOG_ERROR`.
`unresolvedTables` даёт `UNKNOWN_TABLE` с ID использующего запроса. Quoted-токены AST
декодируются тем же правилом, что `TableName`; имена доменного каталога повторно не декодируются.

Проверяются SELECT, WHERE, JOIN ON и ORDER BY, а у UPDATE — также SET-колонки. Алиас таблицы скрывает исходное имя;
ON видит только таблицы уже построенной части JOIN. Неизвестные колонки дают `UNKNOWN_COLUMN`,
неоднозначные ссылки — `AMBIGUOUS_COLUMN`. В ORDER BY простое выходное имя/алиас проекции
имеет приоритет над входной колонкой; в WHERE и ON выходные алиасы не видны. Views,
partitioned/foreign/other relations и ссылки на колонки с `isBuiltinType = false` отклоняются
с `UNSUPPORTED_SQL`; `*` проверяет все раскрытые колонки. Списки переименования колонок
при алиасе таблицы не поддерживаются. `PostgresLiteralTypes` разрешает cast по фактическому
каталогу типов и пути поиска, без ручного списка поддержанных встроенных типов. SQL-standard
формы вроде `integer` и `timestamp with time zone` сопоставляются с системными именами по
[грамматике PostgreSQL 16](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/parser/gram.y);
generic/quoted `int4` ищется по search_path и может оказаться пользовательским domain — такой
cast отклоняется. Пробельные разделители составных типов нормализуются только вне кавычек.
Typmod проверяется и внутри `dataType`, и в `argumentsStringList`; допускаются целые константы.
Пользовательские/нескалярные типы и диалектные модификаторы отклоняются.
Типизированные литералы следуют тем же правилам: generic `DATE '…'` и `TIMESTAMPTZ '…'`
учитывают search_path, стандартные `TIME`/`TIMESTAMP` выбирают системный тип.
Обычные строковые литералы остаются `unknown`; `B'…'` и `X'…'` имеют тип `bit`.
Числовой `0x…` получает `int4`, `int8` или `numeric` по диапазону значения.

`PostgresOperatorResolver` проверяет фактическую идентичность сравнения до создания признака
WHERE/JOIN: видимые сигнатуры, типы обоих аргументов, unknown-литералы, неявные преобразования,
точные совпадения, категории/preferred-типы. Порядок выбора соответствует
[правилам PostgreSQL 16](https://www.postgresql.org/docs/16/typeconv-oper.html).
Пользовательский оператор, даже с символом `=` и встроенными аргументами, даёт `UNSUPPORTED_SQL`.
При проверке неявного преобразования domain разворачивается до базового типа, включая
вложенные domains и implicit cast к их базе; для ранжирования точных совпадений сохраняется
объявленный тип аргумента оператора. Поэтому domain в сигнатуре не скрывает пользовательский
оператор для SQL со встроенными типами колонок.
Отсутствующий/неоднозначный оператор также не превращается в признак генератора. Без необходимых
фактов каталога возвращается `CATALOG_ERROR`, а не предположение о встроенной семантике.
EXPLAIN проверяет значения/совместимость и права доступа, но не заменяет проверку поднабора.

Результат сохраняет исходный `WorkloadInput` с общими SQL и 1–3 профилями, текст/ID каждого
запроса и полный набор физических workload-таблиц для #14. `referencedColumns` включает
все ссылки, `projectedColumns` — раскрытую проекцию, а `predicates`/`orderBy` — только
признаки генерации. Повторные условия остаются отдельными записями. Для `5 < column`
признак нормализован в `column > literal`, исходный SQL при этом не меняется.
Алиасы/self-join сохраняют одну физическую `ColumnRef`, но неоднозначность проверяется
по отдельным вхождениям таблицы. Сортировка сохраняет порядок и направления.
Никаких caps 16/32/16 на стадии resolution нет, проверки deadline идут и в CPU-обходах.

Стык с #10 проверяется на всех общих сценариях `CandidateGenerationFixtures` через
`CandidateGenerationContract`: настоящий parse → resolve → `BtreeCandidateEnumerator`.
`SqlResolutionIntegrationTest` дополнительно использует реальный снимок `JdbcCatalogReader`
и EXPLAIN без ANALYZE в одноразовой PostgreSQL-БД, включая таблицу из 100 колонок.
Он включается теми же `INDEXBUDGET_CATALOG_TEST_*`, что тесты каталога, и входит в
`postgres-catalog-integration` CI. Pipeline #14 уже реализован и использует SQL-порт;
полный запуск CLI ожидает остальные production-зависимости, не подменяя их фикстурами.

## Полный shortlist кандидатов (#12)

### Дерево условий и OR (#74)

`ResolvedQuery.condition` — `ResolvedCondition.Predicate(index)` / `And(left,right)` / `Or(left,right)`.
Индекс листа ссылается на плоский полный `predicates`; production resolver сохраняет оба
представления и покрывает каждый индекс ровно один раз. Корень — WHERE, затем ON в порядке
JOIN, объединённые слева через AND. Плоский список не означает, что его элементы совместны.
Для прежних внутренних фикстур `condition = null` означает конъюнкцию, не попытку угадать SQL.
Обычные приоритеты/группировка сохраняются, все ветви валидируются и разрешаются. JOIN-листья
остаются равенствами колонок, OR между ними допустим. NOT/подзапросы/неподдержанные листы отклоняются.

`ConditionLimits` задаёт 256 единиц сложности, 1024 AST-узла условий и глубину 512. Валидатор
использует стек; генератор проверяет размер до выделения матрицы и сверяет покрытие листьев.
`PredicateContexts` строит совместимость листьев через битовые множества: поперечные связи
добавляются только в AND, OR объединяет множества без связей. Время/память ограничены O(P²),
P ≤ 256, без DNF и произведения числа альтернатив. Роли equality/range связаны с конкретными
вхождениями, а ORDER BY безусловен. Caps и рейтинг уникальных query IDs не меняются.

`CandidateVariant.sourceBranches` → `IndexCandidate.sourceBranches` сохраняет множество
`CandidateSourceBranch(queryId, conditionPath)` для OR-workload. Путь листа — `condition/L/R/...`,
сортировки — `order_by`; это объединение источников, а не один совместный DNF-терм. Канонизация
и глобальный merge объединяют/сортируют источники, не включая их в ID/DDL или частотный рейтинг.
Без OR поле пусто для совместимости прежних фикстур. JSON `source_branches` содержит
`query_id` / `condition_path`; HTML показывает их как экранированный текст для выбранных и
всех сохранённых кандидатов. На query передаётся один warning `OR_BRANCH_AWARE_GENERATION`
с фактическими `or_nodes`, `condition_nodes` (узлы разрешённого дерева без скобочных обёрток),
`predicate_leaves`, `dnf_expanded: false`.
Это диагностика стратегии/ограничений, не признак partial и не доказательство полезности индекса.

Два общих сценария `CandidateGenerationFixtures.orAlternatives/mixedOr` проверяют стык
SQL → DTO → перечислитель/канонизатор/shortlist. Unit-тесты включают 2^128 возможных DNF-термов
без разворачивания; реальная PostgreSQL проверяет OR/NULL/joins и 100 условий через полный EXPLAIN.
Полный OR-анализ с HypoPG и HTML/JSON/SQL добавлен в существующий opt-in integration test.

### Локальный и общий отбор

`ProfileAwareCandidateGenerator` реализует `CandidateGenerator` и зарегистрирован как Spring bean.
Он синхронно принимает одну область `BtreeCandidateEnumerator`, канонизирует её через
`BtreeCandidateCanonicalizer`, удаляет точные реальные дубли и оставляет до 32 кандидатов:
до восьми INCLUDE-вариантов, остальные места — обычным индексам; внутри каждой группы
сначала одиночные ключи, затем пары по `bestPairPriority()` и стабильному ID. Между callbacks хранятся
только локальные survivors (не более 1920 записей), а не все события генерации.

После общего объединения по канонической структуре сохраняются уникальные `sourceQueryIds`
и все `generationReasons` (лучший приоритет пары выводится из них). Для каждого профиля
при `frequency` считается сумма нормированных частот по уникальным query IDs; экспериментальный `baseline_cost` использует сумму нормированных частот × сохранённый cost пустого набора по тем же IDs. OR-ветви не умножают вклад SQL. `weight` не распределяет места и не меняет рейтинг внутри профиля.
Общий helper `normalizedFrequencies` используется и при отборе колонок, и при рейтинге
кандидатов: он обходит query IDs в стабильном порядке, проверяет переполнение суммы,
underflow положительной частоты и общий RunContext.
Очереди одиночных/пар сортируются по убыванию рейтинга, пары далее по лучшему приоритету,
затем по ID. Внешний round-robin идёт по profile ID; очереди каждого профиля чередуются
после успешного выбора, начиная с одиночных. Уже выбранные пропускаются без смены очереди;
пустая очередь заменяется другой. Нулевой рейтинг не даёт выбора; `baseline_cost` отмечает кандидатов без положительной поддержки ни одного профиля причиной `ZERO_BASELINE_COST_SUPPORT`, отдельно от `GLOBAL_CANDIDATE_LIMIT`. Результат сохраняет порядок
выбора: начальные до 16 и резерв до 48, полученный продолжением тех же очередей. Повторная сортировка для подготовки не нужна; в поисковый evaluator передаются только подготовленные допустимые кандидаты, до 16.

Pipeline вызывает `CandidateGenerator.generate(resolved, catalog, context, baseline)` с сохранённым полным пустым набором. Совместимый overload по умолчанию вызывает прежний трёхаргументный метод; `ProfileAwareCandidateGenerator` реализует оба, и `baseline_cost` требует полный baseline с точными query IDs и пустыми candidate IDs. Повторного EXPLAIN и профильных метрик в генераторе нет. Для нового порядка точные `BigDecimal`-числители `Σ f_s(q) × cost0(q)` сравниваются без общего знаменателя, overflow/underflow произведения и округления до 9 знаков. Проверки `normalizedFrequencies` по-прежнему обязательны и при `baseline_cost`. Порядок по pair priority / ID, сценарные и INCLUDE-квоты, арность ключей и caps сохраняются. [Правила и примеры рейтинга](candidate-ranking.md).

`CandidateDiagnostics.localScopes` отражает фактические стадии: `enumeratedDefinitions` до
канонизации, `deduplicatedDefinitions` после неё (включая будущие реальные дубли),
`candidatesAfterExistingDeduplication` после реальных дублей и `retainedDefinitions` после
локального лимита. Новое поле nullable для старых/подставных producers; production-генератор
всегда заполняет его. `candidatesBeforeGlobalDeduplication` — сумма локальных survivors,
`candidatesAfterGlobalDeduplication` — общий уникальный пул, `shortlistedCandidates` — число первоначально отобранных кандидатов до проверки бюджетов (прежний смысл поля сохранён).
`ungeneratedDefinitions` остаётся `null`: неперечисленное пространство не пересчитывается.

Отсутствие default B-tree opclass у корректно разрешённой колонки (например, `json` в null-test)
не является ошибкой каталога. На границе канонизации исключается весь вариант, содержащий
такой ключ без явно заданного opclass; остальные варианты, исходный SQL и baseline сохраняются.
`unindexableVariants` / JSON `unindexable_variants` считает фактически перечисленные исключённые
варианты до канонизации; `deduplicatedDefinitions` считает успешно канонизированные структуры.
Decision `NO_BTREE_OPERATOR_CLASS` агрегируется по query/table/column с `affected_variants`
(счётчики по нескольким неиндексируемым колонкам могут пересекаться), warning — по области.
У исключённого варианта нет выдуманных canonical ID, DDL, размера или cost. Отсутствующая
таблица/колонка и другие настоящие ошибки каталога по-прежнему завершают анализ ошибкой.

Локальный лимит даёт одну агрегированную decision/warning на область с query/table и
`candidates_before`/`candidates_after`; не сохраняется копия всех отсечённых определений.
Общий лимит даёт warning и decisions с ID каждого вытесненного кандидата. Диагностика
колонок и точных реальных дублей передаётся без подмены причин. В ограниченную очередь входят начальные 16 и резерв до 48; `GLOBAL_CANDIDATE_LIMIT` относится только к кандидатам вне этой очереди из 64. Попадание в резерв не считается отказом. Отсечения сами по себе не означают partial.

Генератор не вызывает JDBC/evaluator и не назначает size/cost/write units: оценки остаются
`null`. Подготовка идёт по начальным 16 и резерву до 48 до получения 16 индивидуально допустимых кандидатов. После бюджетного отказа или локальной ошибки гипотезы берётся следующий кандидат из той же очереди, без новой генерации. Доступный write budget проверяется до размера общей моделью keys + INCLUDE; всего допускается до 32 вызовов размера, включая ошибки, и действует общий deadline. Неизвестные размеры не подменяются нулём.
Deadline/cancellation общего `RunContext` проверяются между областями, построением рейтингов,
обходом очередей и итерациями выбора. Тесты включают полный parse → resolve → shortlist
широкого SQL, 60 областей (1920 локальных записей), редкий профиль, дубли, приоритеты и 8+8.
Это проверка алгоритма генерации, не сквозного анализа с HypoPG/поиском/экспортом.

`CandidateDiagnostics` сохраняет прежний смысл `shortlistedCandidates`: число первоначально отобранных кандидатов, равное новому `initialShortlistedCandidates`. `reserveCandidates` описывает резерв, а итог подготовки хранится только в новом `preparation.finalShortlistedCandidates`. Расширение JSON v3 аддитивное, смысл прежних полей сохранён. Пределы `reserveCandidateLimit = 48` и `maxSizeAttempts = 32` ограничивают подготовку. `preparation` содержит `examinedCandidates`, `sizeAttempts`, `budgetRejectedCandidates`, `candidateErrors`, `refilledCandidates`, `finalShortlistedCandidates`, `stopReason`; отсутствие стадии обозначается `null`. Пополнение считает только допустимые записи, пришедшие из резерва. Причины подготовки: `SHORTLIST_FULL`, `RESERVE_EXHAUSTED`, `SIZE_LIMIT`, `DEADLINE_EXCEEDED`, `CANCELLED`, `CONNECTION_LOST`, `FAILED`. `SIZE_LIMIT` с warning допускает поиск на подготовленной части, а потеря сессии/deadline прекращает подготовку и использует сохранённый baseline для partial. Локальная ошибка гипотезы с ошибкой cleanup не допускает продолжения.

`CandidatePreparation(metrics).prepare(batch, session, input, context, onPrepared)` возвращает `CandidatePreparationResult(reportBatch, searchBatch)`: полную очередь для отчёта и допустимый список для поиска. Callback публикует сохранённые факты подготовки до следующей операции, чтобы прерывание не теряло уже полученные оценки.

Общий путь `prepareFixedPool` использует наибольшие бюджеты серии и тот же refill до 16; результат — `PreparedCandidatePool(input, reportBatch, candidates)`. `selectFixedPool(pool, input, context)` разрешает только уменьшение storage/write budget при неизменном остальном входе и возвращает `CandidatePreparationResult` без JDBC, новых размеров и refill. `AnalysisPipeline.runWithFixedPool(input, outputDirectory, context, pool)` вызывает `selectFixedPool` до открытия JDBC-сессии и до раннего завершения по нулевым бюджетам. Несовместимый вход даёт `INVALID_INPUT` без сессии. Затем выполняются обычные parse/catalog/resolution/baseline в собственной сессии, а повторные генерация/подготовка пропускаются. Даже при нулевых бюджетах точки сохраняется полный `reportBatch` фиксированного пула. Между точками не передаётся кэш оценок наборов. Orchestration `analyze-series` использует этот стык для общего пула и проверяет наблюдаемые условия baseline/каталога относительно подготовки; [контракт серии](budget-series.md). [Числовой пример и диагностика](candidate-refill.md).

## Модули и стыки

| Интерфейс | Ответственность | Владелец по плану |
| --- | --- | --- |
| `AnalysisRunner` / `AnalysisPipeline` | Последовательность стадий, общий контекст, результат и владение сессией | Марат |
| `SqlAnalyzer` | Разбор, проверка поддержанных конструкций, разрешение всех ссылок | Максим |
| `CandidateGenerator` | Ограниченная генерация, provenance и причины отсечения | Арсений |
| `DdlRenderer` | Общее представление DDL для гипотез и финального SQL, без исполнения | Арсений |
| `AnalysisSession` / `AnalysisSessionFactory` | Каталог, размеры, одна физическая JDBC-сессия, HypoPG и cleanup | Егор |
| `ConfigurationEvaluator` | Полный вектор costs/plans для одного набора индексов | Егор; тестовые реализации возможны отдельно |
| `MetricsCalculator` / `ObjectiveComparator` | Метрики профилей, бюджеты и единое сравнение | Равиль |
| `Optimizer` | Greedy/repair, кэш, оба incumbents и причины остановки | Марат |
| `ReportBuilder` / `ReportExporter` | Отчёт из сохранённых данных и согласованная публикация артефактов | Марат |
| `ReportRenderer` | Автономный HTML из сохранённого отчёта | Арсений |
| `cli` / `application` | Ввод, последовательность этапов, закрытие сессии, экспорт JSON/SQL | Марат |

Модуль расчётов получает costs и размеры; поиск вызывает его интерфейс. В поиске, JDBC-адаптере и HTML не должно появляться второй реализации формул или прежнего скалярного score.

`MetricsCalculatorImpl.calculate` вычисляет профильные метрики и агрегаты `M`/`R` по полным baseline и evaluation, проверяя их точное покрытие workload через `requireQueryCoverage`. Суммирование идёт по отсортированным query IDs и profile IDs. При нулевых baseline отношения и проценты остаются `null`, абсолютные costs и gains сохраняются; `B_mean=0` при положительном `B_s` даёт `NUMERIC_ERROR`. Это расчёт сырых метрик, а не разрешение обновить incumbents. `ObjectiveComparatorImpl` реализует общий порядок и условие жадного шага. Реализация расчётов не означает, что они уже подключены к сквозному анализу CLI.

`MetricsCalculatorImpl.assessBudget` (#41) рассчитывает расходы только переданного набора новых кандидатов. Для каждого индекса запас округляется вверх до суммирования: `n + ceil(n/5)` целочисленно, без преобразования `Long` в `Double`. Отрицательный размер или переполнение размера/суммы дают `NUMERIC_ERROR`; неизвестный размер — `HYPOTHETICAL_INDEX_ERROR` с `candidate_id`. `BudgetAssessment` содержит общие расходы и все нарушенные ограничения (`STORAGE_BUDGET`, `WRITE_BUDGET`, `MAX_INDEXES`); равенство лимиту допустимо. Реальные индексы остаются в baseline и не передаются как новые кандидаты.

Write units рассчитываются по исходным rates таблицы и сумме числа ключей и INCLUDE-колонок, включая все UPDATE, затем суммируются по candidate ID. Суммы rates, умножение на сумму числа ключей и INCLUDE-колонок, сумма набора и сравнение с лимитом выполняются точно в `BigDecimal` над фактически представленными входными значениями `Double`. Округление в `Double` выполняется только для итогового поля `BudgetUsage.writeUnitsPerHour`; допустимость берётся из `BudgetAssessment`, а не из повторного сравнения округлённого расхода. Например, `1e16 + 1 + 1` даёт `10000000000000002` и нарушает лимит `1e16`; даже непредставимая в `Double` сумма `1e16 + 1` нарушает этот лимит. Точная сумма выше `Double.MAX_VALUE` даёт `NUMERIC_ERROR`.

Без профиля значение — `null`, даже у пустого набора; с профилем рассчитывается и при выключенном лимите, у пустого набора равно нулю. Отсутствующий профиль при включённом лимите, пустой профиль, невалидные rates или отсутствие rates таблицы кандидата дают `INVALID_WRITE_PROFILE`; переполнение расчёта — `NUMERIC_ERROR`. Полное покрытие **всех** таблиц workload, лишние и неоднозначные ключи проверяет `ResolvedWorkloadValidator` до baseline: `WorkloadInput` сам по себе не содержит разрешённых таблиц, а набор кандидатов может быть пустым.

Источники расчёта — `estimatedBytes` и `tableWriteRates`; заполненные `budgetedBytes`/`writeUnitsPerHour` кандидата не подменяют их. Pipeline сохраняет рассчитанные значения в копиях кандидатов для поиска и отчёта. `calculate` переносит готовый `BudgetUsage` в `SetMetrics`; потребители не применяют запас повторно. Нулевое место или `maxIndexes=0` обрабатываются pipeline как baseline-only до генерации, нулевой write budget допускает только нулевые write units.

### Допустимость после оценки (#43)

`MetricsCalculator.assessEvaluation(workload, baseline, evaluation, usage)` вызывает общий `calculate` и возвращает `EvaluationAssessment`. Это отдельная от `assessBudget` проверка: бюджет известен до EXPLAIN, а zero-baseline регрессия и ограничения критических SQL определяются по уже полученному полному вектору costs. Домен проверяет готовые профильные метрики, без второго калькулятора и без новых запросов к БД.

- `Accepted(metrics, searchRequired)` означает отсутствие zero-baseline регрессий. При хотя бы одном положительном `B_s` значение `searchRequired` равно `true`.
- `Accepted(metrics, searchRequired = false)` сообщает, что все `B_s` равны нулю. При оценке baseline pipeline возвращает пустой набор с `StopReason.ZERO_BASELINE`, `M/R = null`, не вызывая генерацию, оценку размеров и поиск. Нулевой бюджет места или `max_indexes = 0` также могут завершить запуск раньше со своей причиной.
- `Rejected(metrics, violations)` содержит все профили с `B_s = 0` и `C_s(S) > 0`, в порядке profile ID. Каждая запись — `AnalysisError` с `code = ZERO_BASELINE_REGRESSION` и `profileId`. Абсолютные costs/gains и вычисленные отношения остальных профилей сохраняются в `metrics`; нулевой профиль по-прежнему имеет `ratio/gainPercent = null`.
- Guards критических SQL (#78, `Constraints.queryCostGuards`) проверяются той же `assessEvaluation` через единую реализацию `CostGuards`: для каждого guarded query ID набор допустим при `cost(q,S)/cost(q,empty) <= limit` (равенство проходит); нарушение добавляет `AnalysisError` c `code = CRITICAL_QUERY_REGRESSION`, `queryId` и details `limit`/`observed_ratio`/`baseline_cost`/`selected_cost` после профильных нарушений. При `cost(q,empty) = 0` отношение не вычисляется (`observed_ratio` остаётся неизвестным, не нулём и не бесконечностью), а любой положительный `cost(q,S)` нарушает guard. Неконечное отношение — `NUMERIC_ERROR`. Профильные и guard-нарушения сообщаются вместе.

`Rejected` — нормальный отказ конкретному набору, а не `AnalysisException`, `NUMERIC_ERROR` или основание завершить весь запуск как failed/partial. Поиск сохраняет причину вместе с ID набора, не обновляет ни один incumbent и рассматривает следующий набор. Если все baseline нулевые, но переданная оценка имеет положительные costs, результат остаётся `Rejected`, а не разрешением выбрать её как baseline. Численные ошибки при расчёте сохраняют прежний контракт `AnalysisException(NUMERIC_ERROR)`.

Пример: два профиля одинакового веса используют только `q1` и только `q2` соответственно, с частотой 1. Для baseline costs `(0, 100)` оценка `(0, 50)` принимается с `M = R = 0.5`. Оценка `(1, 1)` отклоняется для первого профиля, хотя её сырые `M = 0.02` и `R = 0.01` выглядят лучше. Для baseline `(0, 0)` его повторное представление в памяти даёт `Accepted(..., false)`; новый EXPLAIN для этого не нужен.

`GreedyOptimizer` ([#17](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/17)) использует следующий порядок бюджетной проверки, полной оценки и проверки допустимости. Фрагмент показывает стык с метриками; выбор следующего набора и лимиты находятся в реализации поиска. `rejectedConfigurations` обозначает сохраняемые поиском причины отказов, `bestRobust` и `bestMean` изначально содержат принятый baseline:

```kotlin
val budget = metrics.assessBudget(configurationCandidates, workload.input)
if (!budget.admissible) continue
val evaluation = evaluator.evaluate(configurationCandidates, workload, context)
when (val assessment = metrics.assessEvaluation(workload.input, baseline, evaluation, budget.usage)) {
    is EvaluationAssessment.Rejected -> {
        rejectedConfigurations[evaluation.candidateIds] = assessment.violations
        continue
    }
    is EvaluationAssessment.Accepted -> {
        val configuration = EvaluatedConfiguration(evaluation, assessment.metrics)
        if (comparator.compare(configuration, bestRobust, Objective.ROBUST) < 0) bestRobust = configuration
        if (comparator.compare(configuration, bestMean, Objective.MEAN) < 0) bestMean = configuration
    }
}
```

До этого цикла потребитель обрабатывает baseline с `searchRequired = false`. `EvaluatedConfiguration` защищает границу incumbents: не принимает rejected метрики и непустой набор при отсутствии необходимости поиска. `improvesPrimaryMetrics` также не допускает rejected метрики. Попытка обойти проверку — ошибка реализации через `require`, а не штатный способ обработать отказ. Раннее завершение подключено в pipeline и в прямом вызове greedy; отклонённая полная оценка сохраняется с причинами, после чего greedy рассматривает следующий набор.

### Поиск greedy и ограниченный repair (#17, #18)

`LimitedRepairOptimizer.optimize` реализует production-стратегию greedy + repair; `GreedyOptimizer.optimize` сохраняет отдельный жадный этап. Pipeline передаёт полный baseline, конечный shortlist с известными размерами, evaluator, единые метрики/компаратор и общий `RunContext`. Сам greedy повторно не оценивает baseline и не начинает новый отсчёт времени. Прямой вызов использует тот же контракт: вызывающий код начинает поисковый бюджет до получения baseline. На входе проверяются границы `Q ≤ 20`, `N ≤ 16`, `K ≤ 5`; нулевое место, `max_indexes = 0` и все нулевые baseline costs завершают этап пустым набором до обращения к размерам кандидатов.

Кандидаты обходятся по стабильным IDs. Сначала исключаются одиночные нарушения бюджета; затем каждый набор `current + candidate` повторно проходит общую бюджетную проверку до evaluator. Только полный вектор с точными query IDs и candidate IDs попадает в сохранённый пул. `assessEvaluation` отклоняет zero-baseline регрессию с профильными причинами; остальные полные оценки обновляют оба incumbents, даже если не продолжают цепочку. Для продвижения используется только `improvesPrimaryMetrics`, а лучший пригодный шаг выбирается полным `compare`. Решение `NO_MARGINAL_IMPROVEMENT` относится к конкретному шагу и не исключает индекс из следующих сочетаний.

`GreedyOptimizer.search(...)` с теми же аргументами возвращает `GreedySearchResult` для отдельной проверки жадного этапа:

| Поле | Содержимое |
| --- | --- |
| `result` | `SearchResult.Completed` жадного этапа: baseline, оба incumbents, выбранный objective и диагностика |
| `current` | Последнее звено жадной цепочки; может отличаться от выбранного incumbent |
| `repairStart` | `result.selected`: фиксированный `S0`, лучший набор выбранного objective во всём greedy-пуле |
| `candidates` | Кандидаты после одиночного бюджетного отсева; нулевая или отрицательная одиночная польза их не удаляет |
| `evaluations` | Полные векторы и планы, включая baseline и отклонённые оценки; ключ — отсортированные candidate IDs |
| `rejectedEvaluations` | Полные оценки, отклонённые после EXPLAIN, с метриками и профильными причинами |

Одиночные результаты для ранжирования repair доступны по ключам длины 1 в `evaluations`. Его наборы нельзя оценивать сложением одиночных эффектов. В greedy конфигурации не повторяются; максимум — `1 + 16 + 15 + 14 + 13 + 12 = 71` полная оценка с baseline. Счётчики оценок/EXPLAIN меняет evaluator; greedy проверяет общий deadline между проходами и публикует `SearchCheckpoint` до следующей операции. Состояние одного запуска не хранится на экземпляре оптимизатора.

`LimitedRepairOptimizer` фиксирует `S0` из общего greedy-пула и строит три очереди соседей `0→2`, `1→1`, `1→2`, до 450 структур до фильтрации. Входящие индексы берутся строго вне `S0`, полные наборы дедуплицируются. Ранжирование использует известную одиночную пользу на худшем positive-baseline профиле `S0` для robust (равенство — по profile ID) либо ожидаемую стоимость для mean, затем бюджетный размер и ID; неизвестные оценки идут после известных по ID. Пара сравнивается по рангам членов, удаляемый ID — последний ключ. Нулевая одиночная польза не исключает пару. Очереди обходятся round-robin; улучшение не меняет `S0` и не запускает рекурсивный обход.

Кэш одного запуска по отсортированным IDs сохраняет только полные векторы и планы; отказы по zero baseline и ошибочные наборы учитываются отдельно. Cache hit бесплатен для EXPLAIN и repair attempts, известные ошибочные наборы не повторяются. До новой repair-оценки резервируется слот из `RunContext.limits.maxRepairAttempts` (8/28/256 для `fast`/`normal`/`deep`), включая неуспешную попытку. Бюджеты и `max_indexes` проверяются до evaluator. Только для `normal` верхняя граница вместе с baseline — 99 оценок / 1980 EXPLAIN при 20 SQL; профили её не умножают.

Неполное покрытие SQL даёт `EXPLAIN_FAILED`, ответ для другого набора — `INTERNAL_ERROR`; такие результаты не обновляют incumbents. Полный optimizer сохраняет локальные ошибки EXPLAIN и продолжает поиск с итоговым `partial/EVALUATION_FAILED`. Ошибка гипотезы с известным candidate ID исключает его с warning; при ошибке cleanup дальнейшая оценка прекращается. Потеря соединения, deadline, отмена и EXPLAIN-лимит останавливают поиск; оба incumbents сохраняются. После исчерпания выбранного repair-лимита `REPAIR_LIMIT` нужен только при наличии допустимых непроверенных соседей. Численные и входные ошибки остаются failed через pipeline. Checkpoint перед следующей операцией позволяет завершить запуск без новых EXPLAIN.

`Completed` полного optimizer означает завершённый предусмотренный проход и содержит `REPAIR` в `completedPhases`, кроме раннего завершения до поиска (нулевые бюджеты/лимит индексов/baseline или отсутствие кандидатов); candidate caps дают warnings. `Completed` отдельного `GreedyOptimizer` отмечает только `GREEDY`. При незавершённой фазе она не отмечается выполненной.

## Оценки и результаты

`EvaluationResult` — только полная оценка конфигурации. Ключи `costByQueryId` и `plansByQueryId` должны совпадать; costs конечны и неотрицательны. Адаптер вызывает `requireQueryCoverage` с ID всего workload перед возвратом результата. Два или три профиля не приводят к повторному EXPLAIN одного SQL.

`candidateIds` отсортированы и уникальны; пустой список обозначает baseline. Планы сохраняются как `ExplainPlan.json`, чтобы домен не зависел от JSON-библиотеки. Это не проверка синтаксиса EXPLAIN JSON: за корректное чтение плана отвечает адаптер.

`IndexCandidate` хранит определение, исходные query ID, причины генерации и необязательные оценки. До измерения размер равен `null`. Без полного профиля записи write units остаются `null`, включая пустой набор: неизвестное значение не заменяется нулём.

`SearchResult` разделён на три варианта:

- `Completed`: полный baseline и оба полностью оценённых incumbents; предусмотренный ограниченный поиск завершён.
- `Partial`: те же пригодные данные плюс непустая причина остановки; неполный cost vector никогда не становится incumbent.
- `Failed`: типизированная ошибка; `selected`, `bestRobust` и `bestMean` отсутствуют. Baseline допускается, если его успели получить до последующей фатальной ошибки.

`selected` вычисляется из `selectedObjective`, поэтому экспорт не может случайно взять другой incumbent. `bestRobust` и `bestMean` должны происходить из одного пула оценённых наборов; это обязанность реализации поиска, а не доказательство, предоставляемое типом. `comparisonScope` равен `EVALUATED_SETS`; глобальный оптимум не обещается.

Конструкторы оценок и результатов защищают внутренние инварианты через `require`. Их `IllegalArgumentException` указывает на ошибку реализации. Ожидаемые сбои входа, БД, лимитов и численных расчётов передаются через `AnalysisException(AnalysisError(...))`, при необходимости с `queryId` и `profileId`. Сообщения и `details` не должны содержать DSN, пароли или токены.

После успешной валидации `WorkloadReader` записывает `RunContext.inputHash`: SHA-256 исходного JSON в UTF-8 с рекурсивной сортировкой ключей объектов и без пробелов между значениями. Порядок массивов, строки SQL и отсутствие необязательных полей сохраняются; DTO с применёнными defaults для hash не используется. Hash фиксируется один раз на запуск и передаётся в отчёт через контекст. CLI-переопределения `analysis_mode` и `candidate_ranking` не меняют hash исходного JSON; эффективные режим и рейтинг записываются в отчёте отдельно.

Wire-схема `report.json` реализована явным отображением в `JsonReportWriter` и описана в [контракте отчёта v3](report-v3.md); sealed `SearchResult` напрямую не сериализуется. `DefaultReportBuilder` добавляет baseline-метрики через общий calculator, нормированные веса, сравнение costs каждого SQL, совпадение наборов и цену устойчивости через `PriceOfRobustness` (тот же доменный helper, что в #44 / PR #52). Сохранённые метрики incumbents не пересчитываются. При failed исходные costs/plans сохраняются, baseline-метрики могут отсутствовать: экспорт не повторяет уже неудавшийся численный расчёт.

### Общая сводка и происхождение (#68)

`AnalysisReport` при создании вычисляет через domain `summary: RecommendationSummary`,
`profileComparisons` и `queryEffects` (по четыре `CostEffect` на ID), а также переносит
`input.provenance` в `workloadProvenance`. Свойства обновляются и при `copy`, не принимают
отдельных несовместимых данных от renderer. `ReportSummary` не имеет evaluator/JDBC.
`CostEffect.calculate` — общий расчёт gain/ratio/процента для `MetricsCalculatorImpl` и
новых показателей сводки; профильные значения и M/R берутся из сохранённых метрик.
При переполнении дополнительного per-query процента экспорт сохраняет абсолютные costs
с `OUT_OF_RANGE`; ошибка профильных расчётов по-прежнему фатальна.

`WorkloadInput.provenance` по умолчанию `null`. `WorkloadReader` читает необязательный
вложенный объект v1, проверяет UUID источника, даты, покрытие query/profile IDs, диапазоны
счётчиков и явную неизвестность. Он не заменяет статистический импорт #66/#67.
Метаданные входят в исходный hash; источник не содержит DSN. Полная аддитивная схема,
единицы и примеры — в [контракте v3](report-v3.md#сводка-и-данные-сравнений-68).
`ReportImportIntegrationTest` проверяет этот стык с фактическим выходом `import-workload`
#67 и обычным `analyze`: манифест → provenance → общая модель → JSON/HTML, без новых
EXPLAIN после закрытия сессии. Запуск — `bash demo/check-import.sh`; реальные примеры
этого запуска сохраняются в `build/report-import-verification/`.

### Цена устойчивости (#44)

`domain.PriceOfRobustness.calculate(result: SearchResult): Double?` — единый чистый расчёт по готовым метрикам: `bestRobust.metrics.meanCost - bestMean.metrics.meanCost`. Для `Completed` и `Partial` оба incumbent должны быть полностью оценены и допустимы, принадлежать одному запуску и одному исследованному пулу с общими workload, baseline и весами профилей. Эти предусловия обеспечивает Optimizer; калькулятор не управляет пулом, не запускает поиск или EXPLAIN и не пересчитывает профильные метрики.

Совпавшие incumbents, включая baseline-only, дают `0.0`. `Failed` возвращает `null`, в том числе при сохранённом baseline: отсутствие сравнения не подменяется нулём. Используется разность абсолютных `C_mean`, без округления, `abs` или `max(0)`, а не разность `M`/`R` или процентов. Отрицательная конечная разность также сохраняется: калькулятор не исправляет предоставленные метрики или выбор incumbents. Неконечный или отрицательный входной `meanCost` даёт `AnalysisException(NUMERIC_ERROR)`, а не неизвестное значение или нулевую цену.

Стык #19/#20: `ReportBuilder` передаёт результат `PriceOfRobustness.calculate(result)` в существующее поле `AnalysisReport.priceOfRobustness` вместе с тем же `SearchResult`; JSON и HTML используют это готовое значение без повторения формулы. `comparison_scope = "evaluated_sets"`, `selected_objective` и метрики обоих incumbents описывают тот же пул и критерий его исследования. HTML показывает совпадение наборов явно; цена не означает разницу глобальных оптимумов. Реализация сборки и экспорта отчёта остаётся в #19, отображения — в #20.

## CLI, диагностика и каталог вывода

`indexbudget analyze --workload <path> --output-dir <path> [--analysis-mode fast|normal|deep] [--candidate-ranking frequency|baseline_cost]` сначала валидирует вход и назначение вывода. Флаг `--analysis-mode` переопределяет валидное поле `analysis_mode` JSON; при отсутствии обоих используется `normal`. Повтор флага, неизвестное значение или неверное поле JSON дают `INVALID_INPUT` (exit 2), в том числе когда ошибку JSON можно было бы скрыть флагом. Флаг `--candidate-ranking` аналогично переопределяет валидный JSON `candidate_ranking`; default — `frequency`, экспериментальное значение — `baseline_cost`. Повтор флага и неверное значение отклоняются. Effective input передаётся runner с выбранными режимом и рейтингом. `AnalysisConfiguration` регистрирует production-реализации метрик, компаратора, `LimitedRepairOptimizer` и фабрику JDBC-сессии. Spring создаёт `AnalysisPipeline` только при наличии всех его зависимостей. Иначе `UnavailableAnalysisRunner` завершает валидный вход с `NOT_IMPLEMENTED` (exit 1), а `details.missing_components` перечисляет отсутствующие компоненты. Этот путь не подключается к БД, не создаёт каталогов и отчётов. Настройки соединения берутся из `INDEXBUDGET_DATABASE_URL`; текущее чтение JSON не зависит от наличия этой переменной.

`OutputDirectoryValidator` принимает новый или пустой каталог. При начальной валидации любое содержимое вызывает `OUTPUT_DIRECTORY_NOT_EMPTY` (exit 2); файл вместо каталога, родитель-файл или висячая ссылка — `INVALID_INPUT` (exit 2); ошибка доступа — `EXPORT_FAILED` (exit 1). Путь с `..` после несуществующего каталога отклоняется как неоднозначный с `INVALID_INPUT`, чтобы создание родителей не перенаправило экспорт в прежний каталог. Для существующих символических ссылок сохраняется семантика файловой системы, путь не нормализуется лексически. Проверка не изменяет файловую систему. `AtomicReportExporter` повторяет её перед публикацией и соблюдает правила временных файлов/run ID из спецификации. Отказ повторной проверки на стадии экспорта, включая `OUTPUT_DIRECTORY_NOT_EMPTY`, преобразуется в `EXPORT_FAILED` (exit 1), сохраняя существующие файлы.

CLI сохраняет весь `AnalysisError` в одной строке stderr формата `CODE: JSON`. Обязательные JSON-поля — `code` и `message`; `query_id`, `profile_id`, `details` присутствуют только при наличии. Для ошибок входных полей `details.field` содержит структурный путь, например `profiles[0].calls_per_hour`; конкретный запрос указывается отдельно в `query_id`. Строки JSON экранируют управляющие символы, исходный SQL и сырые сообщения исключений не выводятся. Для partial CLI печатает каждую сохранённую ошибку из `result.diagnostics.errors` в этом же формате, сохраняя порядок и exit 3; partial без ошибок не получает вымышленной диагностики. Ошибки входа дают код 2, численные ошибки и прочие failed — 1. Подключённый pipeline возвращает 0 для completed и 3 для partial только после успешного экспорта; ошибка публикации даёт `EXPORT_FAILED` и код 1. В stderr также передаются режим, рейтинг shortlist и все шесть эффективных пределов, снимки стадии и настоящих счётчиков через `RunContext.observeProgress`, без процента готовности; итоговая строка содержит статус, EXPLAIN/evaluation/repair attempts, completed evaluations, cache hits и причины остановки, включая completed. Те же значения сохраняются в JSON/HTML.

## Сессия, время и счётчики

`AnalysisSession.databaseVersions` возвращает уже полученный безопасный `DatabaseVersions`, без выполнения SQL или чтения настроек подключения. Pipeline сохраняет его в `RunContext` сразу после открытия сессии, до закрытия. Builder переносит известные версии PostgreSQL/HypoPG в `EnvironmentInfo`; неизвестные значения остаются `null`. Адаптер сессии должен заполнить snapshot при своей инициализации, экспорт не запрашивает версии из БД.

`main` создаёт один `RunContext` до запуска Spring и передаёт его CLI; прямой вызов `IndexBudgetCli.execute` по умолчанию создаёт контекст при входе. Чтение JSON и проверка output-dir входят в тот же общий бюджет. `AnalysisPipeline` открывает одну `AnalysisSession` и закрывает её в `finally` перед сборкой отчёта. Сессия владеет физическим соединением; каждый `evaluate` очищает предыдущие гипотезы, создаёт ровно запрошенный набор и очищает его в `finally`. После ошибки транзакции сначала выполняется rollback. Секреты подключения не проходят через доменные объекты.

`RunContext.selectAnalysisMode(mode)` выбирает один пресет до открытия БД, сохраняя исходную монотонную отметку начала команды. Выбранные `analysisMode` и `limits` передаются всем стадиям через тот же контекст. Режим не меняет финальный пул до 16 кандидатов, greedy или fixed-`S0` repair.

| Режим | commandTimeout | searchTimeout | explainTimeout | connectTimeout | maxExplainAttempts | maxRepairAttempts |
| --- | --- | --- | --- | --- | --- | --- |
| `fast` | 30 с | 20 с | 2 с | 5 с | 500 | 8 |
| `normal` | 90 с | 60 с | 2 с | 5 с | 2000 | 28 |
| `deep` | 300 с | 240 с | 2 с | 5 с | 10000 | 256 |

`normal` сохраняет прежние пределы. Более глубокий пресет не гарантирует улучшение или точную длительность; верхняя оценка 1980 EXPLAIN относится только к `normal`. Сравнение на фиксированном пуле — в [режимах анализа](analysis-modes.md).

`RunContext.startSearch()` вызывается непосредственно перед baseline. До этого действует общий бюджет выбранного режима; затем — минимум оставшегося общего бюджета и его поискового бюджета. Фаза `REPORT` может использовать оставшееся общее время после окончания бюджета поиска. `enterReport()` разрешает перейти к сохранению уже полученного результата даже после исчерпания общего бюджета или отмены: оставшееся время не продлевается, новые операции через `checkDeadline()` по-прежнему запрещены. Монотонные часы можно подменить в тестах без ожидания реального времени.

| Счётчик | Кто изменяет | Когда |
| --- | --- | --- |
| `evaluationAttempts` | Evaluator | До новой оценки набора |
| `explainAttempts` | PostgreSQL-адаптер | До каждого внешнего EXPLAIN, включая неуспешный |
| `completedEvaluations` | Evaluator | После успеха всех SQL и проверки покрытия |
| `repairAttempts` | Optimizer | До новой repair-оценки, включая неуспешную |
| `cacheHits` | Optimizer | При возврате сохранённой полной оценки |

Кэш не вызывает evaluator и не расходует EXPLAIN/repair slots. Лимиты резервируются до внешней операции. `RunContext` не умеет сам отменять JDBC или сторонний парсер: реализация должна применять полученные таймауты, проверять deadline между проходами и выполнять cleanup.

`RunContext.cancel()` потокобезопасно отмечает отмену; последующие проверки возвращают `CANCELLED`. `RunProgress` содержит текущую фазу, завершённые стадии и счётчики; observer вызывается синхронно на потоке анализа при их изменении, но не из `cancel()`.

`AnalysisSession.cancel()` обязан быть идемпотентным и безопасным при вызове из другого потока: отменить активный SQL и освободить/прервать физическое соединение. После истечения deadline новый SQL ради cleanup не запускается. `close()` остаётся безопасным после отмены. Pipeline регистрирует scoped JVM shutdown hook для SIGINT и снимает его при завершении. Hook инициирует отмену и освобождение сессии; сохранение отчёта при завершении JVM не гарантируется. Кооперативная отмена внутри продолжающего работу процесса проходит обычный путь failed/partial и экспорта.

## Orchestration и передача результата (#14)

После валидации pipeline выполняет parse → открытие сессии → каталог → resolution → проверку write profile → baseline → генерацию shortlist и размеры → optimizer → report. До и после внешних проходов проверяется общий deadline; реализации CPU-проходов также проверяют его между итерациями. Фаза `INTROSPECTION` объединяет parse, каталог и resolution. `LimitedRepairOptimizer` отмечает завершённые `GREEDY` и `REPAIR` на том же контексте; прерванная фаза не отмечается выполненной.

Полный baseline оценивается один раз для общего списка SQL. Профильные метрики и расходы пустого набора получает общий `MetricsCalculator`. Нулевой storage budget, `max_indexes = 0` или все нулевые профильные baseline costs завершают анализ пустым набором до генерации и поиска. Подготовка проверяет доступный write budget до измерения размера и рассматривает начальные 16 и резерв до 48 до получения 16 индивидуально допустимых либо остановки. Разрешено до 32 вызовов `estimateSize`, включая ошибки, в пределах общего command/search deadline; `budgetedBytes` и write units по keys + INCLUDE берутся из общего расчёта. Неизвестный размер не подменяется нулём. Перед оценкой непустых наборов Optimizer проверяет допустимость через `assessBudget`, исключает недопустимые одиночные кандидаты с бюджетными причинами и не вызывает evaluator для недопустимой комбинации. Локальная `HYPOTHETICAL_INDEX_ERROR` исключает кандидата только из набора, передаваемого Optimizer. ReportBuilder получает всю ограниченную очередь до 64, включая отклонённые и ещё не рассмотренные записи с определением, DDL, происхождением, неизвестными оценками и причиной/warning в диагностике. Optimizer получает только подготовленные допустимые записи (до 16); локальный отказ гипотезе и бюджетный отказ позволяют взять следующий кандидат из резерва. При прерывании подготовки отчёт сохраняет уже полученные размеры, отклонённые записи и ещё не рассмотренный остаток. Потеря сессии, численная ошибка и лимиты не маскируются под локальную ошибку кандидата.

`Optimizer.optimize(..., onCheckpoint)` передаёт `SearchCheckpoint` с обоими пригодными incumbents и диагностикой после принятия полного результата, до следующей операции. Кэш и обновление incumbents остаются ответственностью поиска. Pipeline сохраняет checkpoint, чтобы при typed ошибке следующего вызова построить partial без переоценки. Без полного baseline результат failed; численные, входные и каталожные ошибки остаются failed даже при наличии сохранённого набора, включая такие ошибки при cleanup. Ошибки cleanup добавляются к диагностике и не заменяют первичную ошибку.

`ReportBuilder.build(input, candidates, result, context)` получает сохранённый baseline, оба incumbents, costs/plans и диагностику, без доступа к сессии; hash берётся из `context.inputHash`. Pipeline проверяет соответствие run ID, hash, входа и результата перед экспортом. `ReportExporter.export(report, outputDirectory, context)` отвечает за финальную проверку каталога, временные файлы и согласованную публикацию. Экспорт использует оставшийся общий бюджет; минимальный partial после исчерпания бюджета поиска или кооперативной отмены не должен безусловно отвергаться через `checkDeadline()`. `DefaultReportBuilder`, `JsonReportWriter` и `AtomicReportExporter` реализуют #19; HTML предоставляется отдельно через `ReportRenderer` (#20). Экспортёр готовит весь комплект в соседнем временном каталоге и публикует одним `ATOMIC_MOVE`, без пофайлового fallback. Неподдерживаемая операция или недостаточные права на родителя дают `EXPORT_FAILED`. При таймауте/отмене сохранение не отвергается проверкой deadline. Регистрация exporter в Spring условна до появления renderer; его отсутствие не подменяется пустой HTML-страницей.

## HTML-рендерер (#20, #69)

`report.HtmlReportRenderer` реализует `ReportRenderer` и зарегистрирован как Spring
компонент. `render(report)` возвращает один автономный HTML из переданного
`AnalysisReport`: без JDBC, чтения окружения, EXPLAIN, файловой публикации и
повторного вычисления метрик. Стиль из `src/main/resources/report/report.css`
встраивается в документ вместе с фиксированным `report.js`; внешних ресурсов нет.
CSP разрешает скрипт только по SHA-256. Он сортирует уже отрисованные строки SQL
и раскрывает сохранённые планы по безопасным порядковым якорям; не вычисляет метрик,
не читает файлы и не выполняет сетевые запросы. Без него работают нативные детали,
графики и серверный порядок таблицы с ухудшениями SQL сверху. Экспортёр #19 должен
передать тот же объект, который использует для JSON и выбранного SQL.

Все динамические SQL, планы, идентификаторы, сообщения и значения атрибутов
экранируются как текст. `AnalysisError`/`AnalysisWarning` должны поступать с уже
безопасными публичными сообщениями: producer не передаёт DSN, credentials или
сырой текст исключения драйвера. Рендерер не читает настройки подключения и не
переписывает SQL регулярными выражениями для маскировки.

Числа форматируются только для отображения; нормированные веса, профильные
costs/выигрыши, R/M, расходы и `priceOfRobustness` берутся из модели. `null`
не превращается в ноль; при нулевом baseline отношения неприменимы. Переключения
выбранного набора в HTML нет: `result.selected` определяет разделы индексов,
профилей и per-query costs; совпавшие incumbents показаны одной карточкой. Сравнение
baseline/final per-query выделяет регрессии без расчёта новой целевой функции.
Объёмы показываются в точных байтах и MiB (1 MiB = 1 048 576 байт).
MiB округляются для отображения до трёх знаков, округлённое значение отмечается
`≈`, положительное значение меньше половины 0,001 MiB — как `< 0,001 MiB`.
Неизвестные размеры остаются «Нет данных». Решения из диагностики генерации и
поиска объединяются без полных дублей: pipeline уже включает первые во вторые;
различия по query ID, этапу и деталям сохраняются.

`./gradlew reportPreview` создаёт локальный `index.html` и варианты в
`build/report-previews/` через `HtmlReportFixtures`. Это отдельный инструмент
визуальной проверки синтетических данных, не реализация экспорта #19.

## Общие тестовые данные

В `src/testFixtures/kotlin/ru/itmo/indexbudget/fixtures/ContractFixtures.kt` находятся:

| Фабрика | Сценарий |
| --- | --- |
| `workload(objective, withWriteProfile)` | Два профиля, `robust`/`mean`, запись включена или отсутствует |
| `catalog()`, `resolvedWorkload()` | Обычная таблица с существующим PK и сохранёнными SQL |
| `wideCatalog()`, `wideQuery()` | 100 колонок, `SELECT *`, признаки только `c1`/`c2` |
| `candidates()`, `unestimatedCandidate()` | Provenance, известные и неизвестные оценки |
| `baselineEvaluation()`, `robustEvaluation()`, `meanEvaluation()` | Один вектор costs/plans на общий список SQL |
| `completedResult(sameIncumbents = false/true)` | Разные или совпавшие лучшие наборы общего пула |
| `partialResult()`, `failedResult()` | Сбой после пригодного результата или при baseline |
| `report(...)` | Данные для разработки отображения всех трёх статусов |

Все costs, планы, run ID, hash и версии окружения в этих фикстурах синтетические. Это заготовки для разработки и тестирования контрактов, не результаты HypoPG и не доказательство качества оптимизатора. Фикстуры отчёта ограничены одним выбранным индексом, чтобы сравнивать два одиночных набора; это не ограничение рабочего CLI. Числовые метрики заданы готовыми значениями: формулы и компаратор по-прежнему реализуются только в задаче #16.

Тесты проверяют невозможность скрыть отсутствующий SQL, нечисловые costs и неверный baseline; сохранение результатов при partial; выбор incumbent по objective; отсутствие вымышленных write units; границы времени и точность счётчиков. Проверки алгоритма, генерации и реальной БД добавляются вместе с соответствующими реализациями.

## JDBC-каталог (#7)

`postgres.JdbcCatalogReader(connection).readCatalog(tables, context)` — реализация
чтения каталога; `JdbcAnalysisSession.readCatalog` делегирует ему на своём соединении.
Reader не открывает и не закрывает connection, не делает commit/rollback,
не запускает ANALYZE/EXPLAIN и не меняет search_path. Reader также можно вызывать отдельно
с переданным JDBC connection. Фабрика сессии зарегистрирована в CLI, но весь pipeline ещё не собран.

`TableName` содержит идентификаторы после обработки SQL-парсером: unquoted имена
уже приведены к нижнему регистру (ASCII A–Z), quoted имена декодированы: внешние
кавычки сняты, удвоенные кавычки заменены одной. Это выполняет
`JSqlParserSqlAnalyzer.parse` при создании TableName; AST сохраняет исходные
токены. SQL resolver должен применять ту же `decodePostgresIdentifier` к токенам
AST перед сравнением с именами доменной модели. Повторно декодировать TableName нельзя. Reader
экранирует каждый компонент отдельно и передаёт имя параметром в `to_regclass`.
`CatalogSnapshot.searchPath` — эффективный `current_schemas(true)` этой сессии
(включая неявные схемы). Таблицы разрешаются PostgreSQL по этому пути; разные
ссылки на одну таблицу дают одну запись. Не найденные имена сохраняются в
`unresolvedTables`; SQL resolver добавляет query ID к ошибке UNKNOWN_TABLE.
`maxIdentifierLength` читается из настроек сервера. Таблицы при чтении каталога, а колонки,
схемы и алиасы при SQL resolution обрезаются до этого предела в UTF-8 байтах без разрыва
символа. Исходный SQL не меняется, имена физического каталога повторно не нормализуются.
Relation без USAGE на схему или без SELECT хотя бы на одну колонку даёт
CATALOG_ERROR. SELECT на всю таблицу не требуется; чтение метаданных всех
колонок не означает право читать их значения. Доступ к конкретным колонкам
используемого SQL проверяется PostgreSQL при планировании (EXPLAIN). Views/partitioned/foreign/other
сохраняют kind: отклоняет их SQL resolver, а не повторный JDBC-запрос.

Колонки идут в физическом порядке attnum, без dropped/system attributes и без
лимита ширины. `isBuiltinType` означает принадлежность типа к pg_catalog;
проверка конкретного поддержанного типа/оператора остаётся в SQL-модуле.
`typeName` сохраняет PostgreSQL format_type, включая typmod. Неизвестная оценка
числа строк (`reltuples < 0`) — null, а не ноль. Статистика считается доступной,
если видна хотя бы одна запись pg_stats для relation; это не обещает наличие
статистики каждой колонки или её актуальность. `warnings` содержит
MISSING_STATISTICS для каждой relation без видимой статистики; pipeline должен
перенести эти warnings в общий отчёт.

`CatalogColumn.typeOid` и `CatalogSnapshot.semantics` передают факты pg_type, видимые бинарные
сравнения pg_operator и implicit cast-рёбра pg_cast на той же выделенной сессии. Факты
типов включают `CatalogType.baseTypeOid` из `pg_type.typbasetype`: преобразования между domain
и базовым типом не требуют записи в pg_cast. Видимость
операторов определяет `pg_operator_is_visible`, включая shadowing одинаковых сигнатур и
исключение временной схемы. Общие факты типов не являются чтением структур посторонних таблиц:
колонки/индексы по-прежнему читаются только для workload. Built-in identity требует pg_catalog
и системного OID (<16384), а не одного совпавшего имени. Запросы новых семантических фактов
квалифицируют и функции, и операторы, чтобы workload search_path не менял их смысл. Поля
каталога опциональны только для фикстур не-SQL модулей; настоящее resolution сравнений
требует полного снимка. В SQL-тестах факты задаются явно через `withSqlSemantics`, без
production fallback. Чтение и CPU-обходы используют общий deadline; EXPLAIN-счётчик не тратится.

Collation/opclass возвращаются как schema-qualified SQL identifiers с quoting.
Default B-tree opclass выбирается сначала по точному типу (в том числе oidvector),
затем среди binary-compatible implicit casts из pg_cast и стандартных
полиморфных совместимостей. При нескольких совместимых типах выбирается
единственный preferred type той же категории; без него — единственный
совместимый вариант. Это сохраняет, например, cidr → inet_ops и varchar →
text_ops. Неоднозначность/отсутствие default даёт null. Для неподдержанных
domain-типов default не подбирается. Алгоритм сверяется с PostgreSQL 16
[GetDefaultOpClass](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/commands/indexcmds.c)
и [IsBinaryCoercible](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/parser/parse_coerce.c).

Индексы сохраняются независимо от метода, predicate, expressions, INCLUDE и
валидности. Физические ключи упорядочены; expression-позиции записываются в
`expressions`, но не превращаются в выдуманные ColumnRef. Такой индекс никогда
не считается точным дублем обычного кандидата. Направления/NULLS имеют смысл
для B-tree. `accessible` консервативно false для indislive=false/indcheckxmin=true;
допустимость дубля также требует valid и ready. Индексы не изменяются.

Общий RunContext проверяется до/после подготовки statement, непосредственно
перед executeQuery, на каждой строке и после чтения. Нулевой остаток не
превращается в положительный timeout. Каталожные функции, включая unnest,
квалифицированы pg_catalog и не зависят от функций в workload search_path.
JDBC queryTimeout ограничен остатком бюджета, округлённым вверх до секунды
(гранулярность JDBC); это не жёсткая миллисекундная гарантия завершения сетевого
вызова. При вызове через сессию `beforeExecute(statement, context)` регистрирует
активный SQL и контроль deadline до окончания чтения результата. SQLException
преобразуется в CATALOG_ERROR, потеря соединения — в DB_CONNECTION_ERROR;
исходные message/cause исключены, SQLSTATE сохраняется только в безопасном формате.

Проверки `JdbcCatalogReaderIntegrationTest` запускаются только при заданном
`INDEXBUDGET_CATALOG_TEST_URL`, на **одноразовой PostgreSQL 16 БД**. Они создают
и удаляют собственную схему/роль, выполняют seed/ANALYZE и намеренно неудачную
CREATE UNIQUE INDEX CONCURRENTLY. Не направлять тесты на общий стенд.
CI job `postgres-catalog-integration` создаёт отдельную БД без секретов стенда.
Обычный `./gradlew test` без этой переменной пропускает integration-класс;
это не подтверждение работы с реальной PostgreSQL.

Локальная проверка с Docker: `bash demo/check-catalog.sh`. Скрипт создаёт свой
временный контейнер PostgreSQL 16 на случайном loopback-порту, ждёт готовности,
запускает unit/integration-тесты каталога через Gradle и удаляет только этот
контейнер. Собственные настройки подключения он задаёт сам; `.env` и пароли
общей/demo-БД не читает. Требуются Docker и JDK 25; зависимости/образ должны
быть доступны для скачивания или уже находиться в кэше.

## Сессия HypoPG, лимиты и отмена (#13, #15)

`JdbcAnalysisSessionFactory.open(context)` читает `INDEXBUDGET_DATABASE_URL` и открывает
непулированное соединение через `PGSimpleDataSource`. Параметры connect/login/socket timeout
назначаются после разбора URL и ограничены `RunContext`; параметры URL не отключают эти
лимиты. Клиент ограничивает ожидание подключения минимумом 5 секунд и остатка
бюджета, проверяет отмену между короткими ожиданиями и физически закрывает соединение,
которое драйвер вернул после прекращения ожидания. Отсутствующий/некорректный JDBC URL и ошибки подключения дают `DB_CONNECTION_ERROR`
без URL, JDBC message или исходного cause. Закрывается и соединение, полученное уже после
исчерпания времени. Создание Spring bean само по себе не открывает БД. Штатный CLI
отключает logger `org.postgresql`: даже предупреждение драйвера о некорректном URL может
содержать credentials. Диагностика подключения формируется адаптером; при использовании
фабрики вне Boot вызывающий код должен настроить логирование драйвера аналогично.

`JdbcAnalysisSession(connection, ddlRenderer)` принимает владение физическим соединением.
Каталог, размер и EXPLAIN используют его, без пула или дополнительных подключений. Контракт
`estimateSize` соответствует `estimateIndex` из issue: возвращается только `estimatedBytes`,
а запас 1.2 и write units вычисляет domain. Pipeline вызывает его только для окончательного
shortlist. OID гипотезы служит локальным аргументом `hypopg_relation_size`, не candidate ID.

Перед оценкой/размером выполняется reset, затем создание гипотез по проверенной структуре
через общий `PostgresDdlRenderer` (сохранённый DDL кандидата не исполняется напрямую).
Схема расширения читается из `pg_extension`, вызовы HypoPG квалифицированы независимо от
workload `search_path`. Выключенный `hypopg.enabled` даёт `DB_CONNECTION_ERROR`; адаптер
не включает его самостоятельно и не меняет `search_path` или настройки планировщика.
`evaluate` возвращает costs/plans по каждому общему SQL в порядке ID только после полного
успеха и cleanup. Берётся `Plan.Total Cost` корня, без суммы дочерних узлов. Невалидная
структура JSON даёт `EXPLAIN_FAILED`, отсутствующий/нечисловой/отрицательный cost,
overflow или underflow — `NUMERIC_ERROR` с query ID.

EXPLAIN и полные оценки учитываются в общем `RunContext`. Счётчик EXPLAIN увеличивается
непосредственно перед execute через `RunContext.withExplainAttempt`, включая неудачные
попытки. Наблюдатель прогресса вызывается после execute, чтобы его работа не задерживала
SQL между резервированием попытки и запуском. Полный набор засчитывается
только после успешной очистки. Профили, чтение каталога, измерение размеров и экспорт
не расходуют этот счётчик. Сохранённый `EvaluationResult` не зависит от открытой сессии.

SQL ограничен серверным `statement_timeout` в миллисекундах, JDBC query timeout и сетевым
timeout; EXPLAIN использует минимум своего лимита и остатка. JDBC timeout округляется
вверх до секунды. Клиентский контроль общего deadline охватывает каталог, настройку
таймаутов, HypoPG, EXPLAIN с чтением результата и rollback. При истечении бюджета
активному statement отправляется cancel, а соединение физически прерывается;
сетевое ожидание самого cancel не задерживает abort. После ошибки явной транзакции cleanup сначала выполняет rollback,
затем reset. Локальная ошибка создания/размера даёт `HYPOTHETICAL_INDEX_ERROR`, ошибка
EXPLAIN — `EXPLAIN_FAILED`, потеря backend — `DB_CONNECTION_ERROR`. Ошибка настройки
таймаутов относится ко всей сессии. Проверяются также вложенные/подавленные JDBC и
сетевые ошибки, поэтому они не становятся кандидатными ошибками. Первичная ошибка
сохраняется, безопасная ошибка cleanup добавляется как suppressed. Pipeline переносит
обе ошибки в отчёт: ошибка очистки после отклонения кандидата прерывает подготовку
и при полном baseline даёт partial, включая случай последнего кандидата. Численные
ошибки и невалидный вход остаются failed. При deadline/отмене
новый SQL для cleanup не запускается: `abort` физически прерывает соединение. `cancel` и
`close` идемпотентны; отмена безопасна из другого потока. Это не гарантия точной длительности
вызова при зависании драйвера/сети. Отдельный EXPLAIN timeout позволяет продолжить
только после успешного rollback/reset; неполный вектор не возвращается и не засчитывается.
При полном baseline pipeline сохраняет последний полный пригодный результат и причины
остановки, включая безопасные ошибки cleanup. Без полного baseline возвращается failed.

`JdbcAnalysisSessionIntegrationTest` включается только с `INDEXBUDGET_HYPOPG_TEST_URL`
и необязательными `INDEXBUDGET_HYPOPG_TEST_USER/PASSWORD`, строго на одноразовой БД.
Тест создаёт расширение, собственные схемы/роли, seed и статистику вне адаптера. Проверяет
настоящий baseline с PK, точный набор гипотез при каждом EXPLAIN, общие costs/plans, размеры,
изоляцию backend, timeout baseline и середины набора, потерю backend, общий лимит
EXPLAIN, deadline без нового cleanup SQL, rollback, повтор после ошибки и отмену. Runner
`bash demo/check-hypopg.sh` создаёт отдельный контейнер; CI job `postgres-hypopg-integration`
использует тот же runner. Unit tests отдельно проверяют повреждённый JSON, численные ошибки,
счётчики и безопасную диагностику; они не заменяют интеграцию с PostgreSQL.

## Ручной набор (план #64)

`WorkloadInput.manualIndexes` — необязательные 1–5 структурированных B-tree/BRIN определений.
`ManualIndexEvaluator.prepare` разрешает их по общему каталогу и canonicalizer до baseline;
`evaluate` после поиска использует ту же AnalysisSession, MetricsCalculator и RunContext.
`AnalysisReport.manualComparison` передаётся экспортёрам отдельно от SearchResult;
он не становится incumbent и не входит в автоматический shortlist/SQL.
Строгий JSON-вход, бюджетные отказы и выходные поля описаны в
[manual-index-comparison.md](manual-index-comparison.md).

## Снимки pg_stat_statements (план #64)

### Импорт #67

`ObservedWorkloadImporter` использует `FrequencyProfiles` #66 и `SqlExample` по
полному ключу. `ImportCatalog` читает текущий каталог в явном per-entry search_path;
`SqlTemplateMatcher` привязывает только параметрические AST-листья и повторно
использует whitelist. Обёртка `ImportWorkloadCommand` проверяет JSON/схему/источник,
владеет JDBC lifetime, сохраняет файлы и запускает существующие input/write validators.
Успешный workload не содержит зависимостей от живого коллектора.

Импортёр формирует существующий `WorkloadInput.provenance: WorkloadProvenance?` (#68),
не вводя альтернативный DTO/renderer. Ссылка на полный манифест и его SHA-256 входят
в `selectionRule`; окна используют фактические Δcalls, не calls/hour. Ручной ввод без
блока совместим. Точные форматы, лимиты, отбор, знаменатели и replay описаны в
[observed-workload-import.md](observed-workload-import.md).

### Сбор снимков #65

`StatisticsCollector.collect(sourceId, limits, context)` владеет отдельным физическим
соединением только на время чтения. Возвращает `StatisticsSnapshot` с областью текущей БД,
всеми доступными counters до ranking, metadata reset/dealloc до/после и явной неполнотой.
`JdbcAnalysisSessionFactory.openConnection` переиспользует ограниченное открытие физического
соединения без инициализации HypoPG; закрытие обязательно для вызывающего кода.
`StatisticsCommand` запускает каждое чтение с отдельным контекстом, публикует снимки независимо
и не удерживает соединение в интервале пары. Формат v1 и CLI описаны в
[statistics-snapshots.md](statistics-snapshots.md). Снимки имеют отдельную версию и не меняют форматы workload и отчёта анализа.

## Покрывающие индексы (#79)

`IndexDefinition.includedColumns` имеет default `emptyList()`; до двух различных физических колонок одной таблицы, без пересечения с ключами. Каталог уже читает `indnkeyatts` и сохраняет INCLUDE отдельно от keys; канонизатор теперь использует payload для точных дублей и ID. Порядок payload нормализуется по имени. Обычные ID/DDL не меняются. Явные `ResolvedQuery.projectedColumns` используются только при `selectsAllColumns == false`; полный `referencedColumns` проверяет покрытие, SQL не изменяется.

Перечислитель добавляет до `min(16, 976 - ordinary_count)` INCLUDE-вариантов; локально до восьми из 32 мест резервируются под них. Остальные места занимают обычные single/pair кандидаты. Начальный shortlist 16 и резерв до 48 продолжают чередовать арность **ключей**, независимо от payload. Поиск получает до 16 подготовленных допустимых кандидатов. Причины ключей и `COVERING_PROJECTION` сохраняются вместе; отдельные счётчики/отсечения доходят до JSON и HTML.

`MetricsCalculatorImpl.assessBudget` считает row changes × (keys + includedColumns), без второй формулы в отчёте/поиске. Оценка размера и costs идёт через существующую выделенную HypoPG-сессию. Формат отчёта — [v3](report-v3.md); вход workload и CLI остаются прежними. Реальный воспроизводимый пример и отрицательный результат — [demo/include](../demo/include/README.md).
Диагностика фиксированного пула: `preparation_scope` равен `run` для обычного анализа и
`common_pool` для бюджетной точки. В последнем случае `preparation` сохраняет работу общей
подготовки при максимальном бюджете. Отдельный `fixed_pool_selection` описывает текущую точку:
`pool_candidates`, `admissible_candidates`, `budget_rejected_candidates`, `size_attempts` (всегда 0).
В обычном анализе `fixed_pool_selection` равен `null`. HTML подписывает общую подготовку и
текущую точку отдельно; общий shortlist точки показывает число допустимых для неё кандидатов.
Значение `initial_shortlisted_candidates` по умолчанию наследует `shortlisted_candidates`,
в том числе для producers, заполняющих только прежнее поле.


## Серия бюджетов (#75)

Команда `analyze-series` читает обычный workload v2 и отдельный запрос серии v1. В запросе фиксируются `budget`, 3–5 различных значений `values` и общий `timeout_seconds` от 1 до 86400 секунд (default 600); CLI `--series-timeout-seconds` имеет приоритет над валидным JSON. Бюджет storage принимает неотрицательный Long, write — конечное неотрицательное число без положительного underflow. При write-серии обязателен полный профиль записи. Общие SQL/профили/objective/режим/рейтинг/второй бюджет не меняются.

Подготовка при максимальном бюджете строит до 16 кандидатов через `prepareFixedPool`. Каждая последующая точка вызывает pipeline с этим пулом, своим RunContext, baseline и выделенной сессией; size/refill/ordinary run cache между точками не переносятся. Допустимость наборов и метрики остаются в общей модели domain. Общий deadline ограничивает всю серию вместе с подготовкой и локальными пределами режима.

Точки публикуют свои обычные report v3 (B-tree-only) / v4 (BRIN) JSON/HTML/SQL в отдельных подкаталогах; сводка содержит готовые результаты и относительные ссылки, не выполняя повторный EXPLAIN. Ошибка/остановка не удаляет ранее опубликованные точки. Условия подготовки и каждой точки сопоставляются по наблюдаемым baseline, каталогу и доступным версиям; обнаруженный drift отмечается явно. Это не гарантия неизменности всей БД и не два поиска в одинаковом пуле оценённых комбинаций. [CLI, точный формат сводки и ограничения](budget-series.md).

`BudgetSeriesWriter` передаёт тот же сериализованный снимок v1 в `HtmlBudgetSeriesRenderer.render(JsonNode)` для `series.html`, а сохранённые `AnalysisReport` точек передаёт дополнительной картой для единого корневого `report.html` рядом с `series.json`. Рендерер форматирует готовые метрики; только координаты графика вычисляются локально. Он не читает файлы точек, не вызывает domain/JDBC и не меняет выбранный набор. Обновление выполняется до подготовки, после точек и в конце; каждый файл заменяется атомарно, три файла — последовательно. При обработанной ошибке старые HTML убираются, точки сохраняются, наружу выходит `EXPORT_FAILED`. Правила графиков, состояний, ссылок и переносимости описаны в [контракте HTML-сводки](budget-series.md#автономная-html-сводка-76); версии JSON и флаги CLI сохраняются.

`SeriesPointRun.report` и `BudgetSeriesPoint.report` — внутренний сохранённый `AnalysisReport?`, полученный после успешного экспорта точки. Он не добавляется к JSON v1. `HtmlReportRenderer.renderContent(report, prefix)` переиспользует полные секции обычного отчёта, задавая изолированные `variant-N-` ID для общей страницы. Отсутствие модели допускается для подставных producer; production `AnalysisPipeline` возвращает её при успешной публикации.

## Методы индексов и BRIN (#114)

`WorkloadInput.indexMethods: Set<IndexMethod>` по умолчанию `{BTREE}`; reader
проверяет непустой уникальный массив `index_methods`, допускает `BTREE`/`BRIN`.
`IndexDefinition.method` сохраняет строковый контракт; BRIN поддерживает одну
колонку и не поддерживает INCLUDE/сортировку/нестандартные opclass.
`CatalogColumn.defaultOperatorClass` остаётся B-tree default для совместимости;
`defaultOperatorClasses["brin"]` содержит независимо разрешённый default BRIN opclass.
`operatorClassFor(method)` — общий доступ для canonicalizer и ручного сравнения.
`ExistingIndex.defaultStorageParameters` фиксирует отсутствие reloptions; BRIN с
явными параметрами не исключает кандидат как точный дубль.

Существующие `BtreeCandidateEnumerator`/`BtreeCandidateCanonicalizer` обслуживают
оба метода, сохраняя имена классов для прежних consumers. BRIN перечисляется по
применимым диапазонным WHERE после column cap; общий bounded scope и shortlist
сохраняются. `PostgresDdlRenderer` использует метод из проверенной структуры;
сессия по-прежнему пересоздаёт DDL, не доверяя полю `candidate.ddl`.

Report v4 требуется при BRIN (включая только ручной BRIN), иначе прежний v3.
`index_methods` передаётся в JSON/HTML/SQL, определение сохраняет `method`;
неприменимые BRIN direction/nulls в JSON равны null. Сводка серии v2 содержит метод,
collation/opclass кандидата; B-tree-only сохраняет v1, точки — v3/v4 соответственно.
Формулы метрик, бюджетов и write proxy общие и не меняются. Полные правила,
версионирование и воспроизводимый пример — [BRIN](brin.md) и §5 спецификации.
