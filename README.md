# IndexBudget

IndexBudget помогает выбрать индексы PostgreSQL, когда у базы несколько сценариев нагрузки и ограниченный бюджет.

## Что можно сделать

- Сравнить обычную нагрузку и пиковый сценарий. Увидеть, какие запросы выигрывают, а какие становятся дороже.
- Проверить несколько бюджетов за один запуск. Например, сравнить рекомендации для 16, 32 и 64 MiB.
- Задать частоты вручную или собрать из статистики БД.
- Получить отчёт в формате JSON и SQL.

## Как работает IndexBudget

На входе — SQL-запросы, их частоты и ограничения на место и обслуживание индексов при записи. Профили описывают разные сценарии нагрузки: например, обычный день и часы пик.

1. Анализатор разбирает SQL, сверяет таблицы и колонки с каталогом PostgreSQL и получает исходные планы с учётом уже существующих индексов.
2. По условиям фильтрации, соединения и сортировки формирует ограниченный пул кандидатов.
3. Через HypoPG и EXPLAIN оценивает комбинации индексов целиком. Стоимости запросов рассчитываются один раз для каждого набора, затем частоты профилей применяются в памяти.
4. Ищет набор в пределах бюджетов. Критерий `robust` учитывает худший сценарий относительно исходной стоимости, `mean` — ожидаемую стоимость с учётом весов сценариев.
5. Сохраняет HTML-отчёт с объяснением выбора, JSON с метриками и диагностикой и SQL для отдельной проверки рекомендаций.

Реальные индексы при анализе не создаются. Результат показывает оценку планировщика

Ядро проекта — CLI на Kotlin. Веб-демо вызывает тот же CLI на фиксированных датасетах.

### Один запуск через CLI

Для сборки нужна **JDK 25**. Из корня репозитория выполни:

```sh
./gradlew --no-daemon build installDist
```

Для своей БД подготовь PostgreSQL 16 с HypoPG 1.4.2 и роль анализа.

```sh
export INDEXBUDGET_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/indexbudget?user=ANALYSIS_USER&password=LOCAL_PASSWORD'
build/install/indexbudget/bin/indexbudget analyze \
  --workload demo/workload-poc.json \
  --output-dir reports/run-001
```

[Пример workload](demo/workload-poc.json)

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

Открой `reports/series-001/report.html`. Серия меняет один бюджет и использует общий пул кандидатов.

### Нагрузка из статистики

`stats snapshot|pair` собирает статистику SQL из `pg_stat_statements`. `table-stats snapshot|pair` сохраняет счётчики изменений строк. 

## Важно знать

- Поддерживаются ограниченный SELECT с WHERE/JOIN/ORDER BY и однотабличные UPDATE/DELETE с обязательным WHERE. INSERT/MERGE, CTE и подзапросы анализатор не принимает.
- Даже EXPLAIN для UPDATE/DELETE требует отдельных прав записи. Администратор настраивает opt-in роль.
- Обычный анализ использует HypoPG и EXPLAIN без выполнения DML. Он не строит реальные индексы и не запускает ANALYZE или EXPLAIN ANALYZE.
- Planner cost — оценка PostgreSQL. Write proxy — условная цена обслуживания индексов. 
- По умолчанию поиск использует `robust`: уменьшает худшее отношение стоимости профиля к baseline. `mean` сравнивает ожидаемые стоимости.
- По умолчанию предлагаются B-tree. [BRIN](docs/brin.md) и [hash](docs/hash.md) включаются явно и имеют свои ограничения.

## Стек

Kotlin · JDK 25 · Spring Boot в режиме CLI · Gradle · PostgreSQL 16 · HypoPG 1.4.2 · JSqlParser · Jackson · Python · HTML/CSS/JavaScript · Docker Compose.

## Где искать подробности

- [Видео-демо](https://drive.google.com/file/d/1D0EKOpNNejvG25OFSbLzzRFqAC68K3g2/view?usp=sharing),
- [Презентация](https://docs.google.com/presentation/d/1unqj-g8eLOVvheZ9P7w41Rto1fYL-PbOnv1PCFhgLuE/edit?slide=id.p12#slide=id.p12)
