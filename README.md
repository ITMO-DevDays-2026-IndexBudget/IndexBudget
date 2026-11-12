# IndexBudget

IndexBudget помогает выбрать индексы PostgreSQL, когда у базы несколько сценариев нагрузки и ограниченный бюджет. Ты задаёшь SQL, частоты и допустимые расходы места и записи, а получаешь набор индексов с объяснением выбора.

## Что можно сделать

- Сравнить обычную нагрузку и пиковый сценарий. Увидеть, какие запросы выигрывают, а какие становятся дороже по оценке PostgreSQL.
- Проверить несколько бюджетов за один запуск. Например, сравнить рекомендации для 16, 32 и 64 MiB.
- Оценить свою идею набора индексов рядом с автоматической рекомендацией.
- Задать частоты вручную или собрать workload из статистики своей БД.
- Открыть отчёт без сервера и передать его коллеге вместе с JSON и SQL.

## Быстрый старт

Нужны **JDK 25**, **Python 3.10+**, **Docker с Compose v2** и Git. При первой сборке нужен интернет для Gradle, зависимостей и Docker-образа. Команды ниже рассчитаны на macOS/Linux.

**1. Забери проект.**

```sh
git clone https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget.git
cd IndexBudget
```

**2. Проверь Java и собери CLI.**

```sh
java -version
python3 --version
./gradlew --no-daemon build installDist
```

Java должна показывать версию 25. Если выбрана другая, задай `JAVA_HOME` на установленную JDK 25 и добавь `$JAVA_HOME/bin` в `PATH`. На Windows используй `gradlew.bat` и CLI launcher `.bat`.

**3. Подними локальную БД демо.** Docker должен быть запущен.

```sh
docker compose -f frontend/compose.yaml up -d --build --wait
```

**4. Запусти веб-интерфейс.** Для этого демо `.env` и пароли не нужны.

```sh
python3 frontend/server.py
```

**5. Открой <http://127.0.0.1:8090>.** Выбери «Чтение и запись» и запусти анализ. Из результата задания открой отчёт.

В демо четыре фиксированных датасета. SQL и частоты можно менять, схема берётся из выбранного датасета. Вкладка «Примеры» поможет собрать нагрузку. Для сравнения бюджетов переключись на серию и задай 3–5 разных значений места или записи.

Чтобы остановить демо, нажми Ctrl+C в терминале сервера, затем выполни:

```sh
docker compose -f frontend/compose.yaml stop
```

Демо использует HTTP-порт `8090` и собственную PostgreSQL на `127.0.0.1:55433`. Данные БД одноразовые: после пересоздания контейнера датасет готовится заново. [Другие порты и настройки](frontend/README.md). [Что проверено в быстром старте](docs/onboarding-verification.md).

## Примеры и свои SQL

Допустим, часто нужен список последних записей одной группы:

```sql
SELECT id, value
FROM public.records
WHERE group_id = 42
ORDER BY created_at DESC
LIMIT 20;
```

Для такого запроса может пригодиться индекс `(group_id, created_at)`. IndexBudget проверяет набор целиком с учётом других SQL и бюджетов. Конкретный индекс и ускорение заранее не обещаются.

### Один запуск через CLI

Для своей БД подготовь PostgreSQL 16 с HypoPG 1.4.2 и роль анализа. [Инструкция для локальной SELECT demo-БД](demo/README.md) описывает данные, права и подключение.

```sh
export INDEXBUDGET_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/indexbudget?user=ANALYSIS_USER&password=LOCAL_PASSWORD'
build/install/indexbudget/bin/indexbudget analyze \
  --workload demo/workload-poc.json \
  --output-dir reports/run-001
```

Замени реквизиты в URL своими. CLI читает эту переменную окружения, но не загружает `.env` автоматически. Не сохраняй пароли в Git. Каждый запуск требует нового или пустого output-каталога.

В [готовом workload](demo/workload-poc.json) три SELECT и два профиля частот. Скопируй его для своих SQL. В каждом профиле `calls_per_hour` задаёт частоты всех запросов, а `weight` — вес сценария. Всего можно передать 1–20 SQL и 1–3 профиля.

Расходы на запись задаются отдельно: `table_write_rates` содержит изменённые строки в час по таблицам. Это ручной (`manual`) профиль или наблюдаемый (`observed`) профиль из совместимой пары снимков. [Формат и расчёт rates](docs/table-write-profile.md), [выбор источника в демо](docs/write-profile-ui.md).

После анализа получишь:

```text
reports/run-001/
  report.html   # вывод, запросы, расходы и ограничения
  report.json   # точные метрики, планы и диагностика
  indexes.sql   # DDL выбранных индексов для отдельной проверки
```

### Серия бюджетов

```sh
build/install/indexbudget/bin/indexbudget analyze-series \
  --workload demo/workload-poc.json \
  --series examples/budget-series-storage.json \
  --output-dir reports/series-001
```

Открой `reports/series-001/report.html`. Серия меняет один бюджет и использует общий пул кандидатов. Для передачи результатов скопируй весь каталог: там лежат JSON и SQL отдельных вариантов. [Серия по бюджету записи и правила сравнения](docs/budget-series.md).

### Нагрузка из статистики

`stats snapshot|pair` собирает статистику SQL из `pg_stat_statements`. `table-stats snapshot|pair` сохраняет счётчики изменений строк. Администратор заранее включает статистику и выдаёт права; сборщики не меняют настройки БД.

Затем `import-workload` собирает workload для обычного анализа. Для импорта нужны снимки, примеры SQL с литералами, контекст схем и бюджеты. [Пошаговый маршрут от снимков до рекомендации](docs/observed-workload-import.md), [сбор SQL-статистики](docs/statistics-snapshots.md), [сбор табличных счётчиков](docs/table-statistics-v1.md).

## Важно знать

- Поддерживаются ограниченный SELECT с WHERE/JOIN/ORDER BY и однотабличные UPDATE/DELETE с обязательным WHERE. INSERT/MERGE, CTE и подзапросы анализатор не принимает. [Точный SQL-поднабор](IndexBudget-spec-v0.2.md#поддержанный-sql-и-каталог).
- Даже EXPLAIN для UPDATE/DELETE требует отдельных прав записи. Администратор настраивает opt-in роль. Анализатор не выдаёт GRANT и проверяет read-only защиту сессии. [Права и ограничения DML](docs/dml-analysis.md).
- Обычный анализ использует HypoPG и EXPLAIN без выполнения DML. Он не строит реальные индексы и не запускает ANALYZE или EXPLAIN ANALYZE.
- Planner cost — оценка PostgreSQL. Write proxy — условная цена обслуживания индексов. Исторические counters описывают прошлую нагрузку. Ни один из этих показателей не заменяет измеренные latency, WAL или TPS.
- По умолчанию поиск использует `robust`: уменьшает худшее отношение стоимости профиля к baseline. `mean` сравнивает ожидаемые стоимости. Поиск ограничен ресурсами и не гарантирует глобальный оптимум или SLA. При остановке пригодный результат может сохраниться со статусом `partial` и причиной.
- По умолчанию предлагаются B-tree. [BRIN](docs/brin.md) и [hash](docs/hash.md) включаются явно и имеют свои ограничения. [Ручной набор](docs/manual-index-comparison.md) оценивается отдельно от автоматического поиска.

Реальную пользу и цену записи проверяй на отдельной test-БД. Для этого есть [физический benchmark и сохранённые результаты](demo/benchmark/README.md), включая отсутствие ускорения и рост расходов записи.

## Стек

Kotlin/JVM · JDK 25 · Spring Boot в режиме CLI · Gradle Wrapper · PostgreSQL 16 · HypoPG 1.4.2 · JSqlParser · Jackson · Python · HTML/CSS/JavaScript · Docker Compose.

## Где искать подробности

- [Справочник CLI](docs/cli-reference.md) — параметры, режимы поиска, ошибки и отчёты.
- [Веб-демо](frontend/README.md) — датасеты, настройки формы и сохранение заданий.
- [Библиотека SQL](demo/scenarios/README.md) — запросы чтения и изменения строк с частотами.
- [Спецификация](IndexBudget-spec-v0.2.md) и [контракты](docs/contracts.md) — форматы и правила расчётов.
- [Эксперимент с наблюдаемыми бюджетами](docs/experiments/observed-budgets-2026-11-12/README.md) — подробные результаты сравнения.
- [Общий стенд](deploy/db/README.md) — обновления БД и сохранение данных.
- [Разработка и ревью](ai/README.md), [AGENTS.md](AGENTS.md) — контекст для участников и агентов.
