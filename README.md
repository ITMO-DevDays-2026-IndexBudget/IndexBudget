# IndexBudget

IndexBudget подбирает дополнительные индексы PostgreSQL под несколько сценариев
нагрузки и ограничения места и записи. Передайте SQL, частоты и бюджеты — получите
лучший найденный допустимый набор и объяснение компромиссов. Рекомендация учитывает
существующие индексы и оценивает каждый новый набор целиком через HypoPG.

Например, для запросов с фильтром по группе и сортировкой по дате результат может
содержать индекс `(group_id, created_at)`. Это иллюстрация, а не обещание выбора или
ускорения. Реальный запуск сохраняет:

- **report.html** — автономный отчёт: набор, профили, расходы, регрессии и ограничения;
- **report.json** — точные метрики, планы, происхождение и диагностику;
- **indexes.sql** — DDL выбранного набора для отдельной проверки и применения.

Обычный анализ не выполняет UPDATE/DELETE, физический CREATE INDEX, ANALYZE или
EXPLAIN ANALYZE. При неуспешном запуске успешный SQL рекомендаций не публикуется.

## Быстрый запуск: локальное демо

Нужны **JDK 25**, **Python 3.10+**, **Docker Engine с Compose v2** и Git. Gradle
устанавливается Wrapper; первая сборка требует доступа к Gradle distributions,
Plugin Portal, Maven Central, Docker Hub, GitHub и apt-репозиториям образа.
Порты по умолчанию: HTTP `8090`, собственная БД демо `127.0.0.1:55433`.

Из чистого checkout:

```sh
git clone https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget.git
cd IndexBudget
java -version
python3 --version
docker compose version
./gradlew --no-daemon build installDist
docker compose -f frontend/compose.yaml up -d --build --wait
python3 frontend/server.py
```

Проверьте, что Java показывает 25. Если нужно, задайте `JAVA_HOME` на установленную
JDK 25 и добавьте `$JAVA_HOME/bin` в `PATH` до сборки. На Windows используйте
`gradlew.bat` и launcher `.bat`; shell-команды примеров рассчитаны на macOS/Linux.
Файлы `.idea/` и локальные настройки автора не требуются.

Откройте <http://127.0.0.1:8090>, оставьте датасет «Чтение и запись» и запустите анализ.
Откройте отчёт из результата задания. Для серии выберите 3–5 разных бюджетов места
либо записи: единый отчёт сравнит варианты. Процент готовности не выдумывается;
интерфейс показывает стадии и состояния точек. Пригодные результаты сохраняются
при частичной ошибке. Полные настройки и восстановление job — [в инструкции демо](frontend/README.md).

Остановка: Ctrl+C в терминале сервера, затем
`docker compose -f frontend/compose.yaml stop`. БД frontend одноразовая (tmpfs):
после пересоздания данные готовятся заново. Общий стенд и его volume не затрагиваются.
[Статус проверки этого пути](docs/onboarding-verification.md).

## Примеры и свои SQL

Четыре фиксированных датасета, их схема и seed принадлежат демо; SQL можно редактировать,
но создать свою схему через форму нельзя. Вкладка «Примеры» использует
[общую библиотеку SQL и частот](demo/scenarios/README.md). Для собственной БД используйте CLI:
подготовьте PostgreSQL 16 с HypoPG 1.4.2, доступную статистику и отдельную роль анализа.
[Локальная SELECT demo-БД и подключение](demo/README.md); [права DML](docs/dml-analysis.md).

```sh
export INDEXBUDGET_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/indexbudget?user=ANALYSIS_USER&password=LOCAL_PASSWORD'
build/install/indexbudget/bin/indexbudget analyze \
  --workload demo/workload-poc.json --output-dir reports/run-001
```

В URL замените пользователя/пароль локальными реквизитами. Не коммитьте их.
CLI не читает `.env` автоматически; output-каталог должен быть новым или пустым.
В [workload-poc.json](demo/workload-poc.json) три SELECT, два профиля, ручные rates и бюджеты.
Для своих SQL скопируйте его и согласованно измените queries, calls_per_hour и таблицы записи.

Workload **JSON v2** содержит 1–20 SQL и 1–3 профиля. `calls_per_hour` — частоты
SQL, `weight` — относительный вес сценария. Одна и та же оценка SQL обслуживает все
профили. `objective: robust` (default) минимизирует худшее отношение стоимости профиля
к baseline; `mean` — отношение ожидаемых стоимостей. Оба лучших набора сохраняются
из одного исследованного пула. Совпадение наборов допустимо.

`storage_budget_bytes` задаёт место с запасом; `write_budget_units_per_hour` — условную
цену обслуживания новых индексов, явный `null` отключает этот лимит. `table_write_rates`
задаёт изменённые строки/час для всех используемых таблиц, отдельно INSERT/UPDATE/DELETE:
это **не** частоты вызовов SQL. Профиль может быть manual или observed; для observed
нужны полное происхождение и совместимое окно наблюдения. Неполный профиль не превращается
в нули. [Формат, формулы и импорт записи](docs/table-write-profile.md),
[источник записи в демо и HTML](docs/write-profile-ui.md).

Серия меняет один бюджет, сохраняя workload и общий пул кандидатов:

```sh
build/install/indexbudget/bin/indexbudget analyze-series \
  --workload demo/workload-poc.json --series examples/budget-series-storage.json \
  --output-dir reports/series-001
```

Откройте `reports/series-001/report.html`; переносите весь каталог для доступа к JSON/SQL
точек. [Write-серия, формат, partial и сопоставимость](docs/budget-series.md).
Режимы `fast|normal|deep` и рейтинг меняют разрешённую работу поиска:
[ресурсные пределы](docs/analysis-modes.md), [справочник CLI](docs/cli-reference.md).

## От статистики до workload

Для реальной нагрузки используйте `stats snapshot|pair` (pg_stat_statements) и
`table-stats snapshot|pair` (изменения строк). Сначала администратор включает нужное
расширение/статистику и выдаёт права: сборщик не делает это автоматически.

```sh
build/install/indexbudget/bin/indexbudget stats pair \
  --source-id 09775435-44bf-4abd-97b0-7740c01d2d2b --interval-seconds 60 --output-dir captures/read
build/install/indexbudget/bin/indexbudget table-stats pair \
  --source-id 09775435-44bf-4abd-97b0-7740c01d2d2b --interval-seconds 60 --output-dir captures/write
```

UUID в примере замените стабильным идентификатором своего источника. Эти две команды
снимают последовательные, а не синхронные окна; при совместном импорте подготовьте
совместимые окна по правилам контракта. Для импорта нужны реальные снимки, 1–3 окна,
SQL-примеры с литералами вместо `$n`, контекст схем и явные бюджеты. Синтетический
[комплект конфигурации](examples/observed-import/import-config.json) показывает формат;
его database/source/query IDs нельзя выдавать за IDs вашей БД.

```sh
build/install/indexbudget/bin/indexbudget import-workload \
  --config import-config.json --output-dir imported
build/install/indexbudget/bin/indexbudget analyze \
  --workload imported/workload.json --output-dir reports/imported-001
```

Подготовьте `import-config.json` по [инструкции импорта](docs/observed-workload-import.md).
[Снимки SQL: права, лимиты и тексты](docs/statistics-snapshots.md),
[снимки таблиц и ограничения reset PG16](docs/table-statistics-v1.md).
Исторические counters и время наблюдения описывают прошлую нагрузку, не доказывают
эффект будущего индекса. Ожидание окна не расходует deadline поиска.

## Поддержанные SQL и ограничения

Поддерживается ограниченный SELECT с WHERE/JOIN/ORDER BY, AND/OR, IN, BETWEEN и
null-tests, а также однотабличные UPDATE/DELETE с обязательным WHERE и ограниченными
SET-литералами. INSERT/MERGE, CTE, подзапросы и произвольные выражения не поддерживаются.
Точный поднабор и лимиты: [спецификация](IndexBudget-spec-v0.2.md#поддержанный-sql-и-каталог),
[DML и права](docs/dml-analysis.md), [примеры предикатов](examples/workload-v2-predicates.json).

Даже EXPLAIN DML требует UPDATE соответствующих колонок и/или DELETE, помимо прав
чтения. Администратор отдельно настраивает opt-in роль; обычная SELECT-only роль
не расширяется. Runtime не выполняет GRANT и проверяет read-only защиту сессии.
DML planner cost оценивает поиск/подготовку строк, не фактическую запись или триггеры.

По умолчанию кандидаты B-tree, включая ограниченный INCLUDE. Явно доступны
[BRIN для диапазонов](docs/brin.md) и [hash для равенства](docs/hash.md) в своих границах.
Другие типы не заявляются готовыми. [Ручной набор](docs/manual-index-comparison.md)
оценивается отдельно и не переписывает best_robust/best_mean автоматического поиска.

Поиск ограничен пулом и ресурсами, не гарантирует глобальный оптимум или SLA.
Planner cost — оценка PostgreSQL; HypoPG size — оценка размера; write proxy — условная
модель, а не WAL или миллисекунды. Реальные latency/TPS/WAL/bytes измеряются только
[отдельным физическим benchmark](demo/benchmark/README.md) на одноразовой test-БД.
Результаты, включая отсутствие ускорения и рост цены записи, — по ссылкам в методике.

## Подробные документы

| Задача | Документ |
|---|---|
| Все параметры, ошибки, отчёты и устройство CLI | [Справочник](docs/cli-reference.md) |
| Демо, фиксированные датасеты, одиночный/серийный запуск | [Frontend](frontend/README.md) |
| SQL, частоты и budgets общего сценария | [Examples](demo/scenarios/README.md) |
| Требования, форматы и границы MVP | [Спецификация](IndexBudget-spec-v0.2.md), [контракты](docs/contracts.md) |
| Измерения robust/mean и наблюдаемых бюджетов | [Эксперимент #109](docs/experiments/observed-budgets-2026-11-12/README.md) |
| Общий стенд, миграции и сохранение данных | [Deploy DB](deploy/db/README.md) |
| Разработка, архитектура и ревью | [AGENTS](AGENTS.md), [ai](ai/README.md) |
