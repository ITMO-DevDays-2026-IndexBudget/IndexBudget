# D3 / #110: реальный baseline/selected benchmark

**Статус: runner подготовлен, полный физический прогон ещё не выполнен.** Наличие
этого каталога не закрывает #110/#112. Приёмка требует сохранённых артефактов настоящего
прогона, сборки bridge и проверки результатов; unit-тесты не заменяют их.

## Проверка прежнего runner

В доступном main benchmark отсутствовал. `demo/experiments/physical.py` в незамёрженном
PR #63 проверен по исходникам: он использует SAVEPOINT/ROLLBACK, прямо оговаривая,
что WAL/dead tuples/физические условия не восстанавливаются. Для #110 это недостаточно.
Его файлы не менялись. Новый runner переиспользует seed/SQL #106, настоящий analyze,
коллектор #102 и единственный WriteProfileCalculator #107. INSERT остаётся только runner.

## Запуск

Нужны Docker Desktop/Compose, JDK 25, Python 3.10+, собранный CLI и зависимости Gradle.
Выполнять локально; адрес стенда/внешний DSN не принимается.

```sh
source .idea/java-env.sh
./gradlew --no-daemon build installDist
python3.13 -m unittest discover -s demo/benchmark/tests -v
python3.13 demo/benchmark/run.py --output build/mixed-benchmark-001
```

Каталог должен отсутствовать. Default: три повтора каждого из read/mixed для каждого
baseline/selected (12 свежих test DB), один последовательный клиент, одна транзакция
на statement, fixed observation window 60 секунд. Можно увеличить repetitions (не менее 3)
и observation-seconds (5–3600); значения выбираются **до** поиска рекомендации.
Физические SELECT с полным выводом могут создать значительные сырые логи: планируйте
несколько сотен MiB свободного места. Точное время и качество результата не обещаются.

Образ PostgreSQL 16.15/HypoPG 1.4.2 строится из demo/postgres. Runner запускает собственный
контейнер с уникальным ownership label, loopback-портом, CPU=2, RAM=2g, autovacuum=off,
parallel workers per gather=0. Trust auth действует только на одноразовой локальной БД.
Пароли не требуются. При cleanup проверяется label, удаляется только записанный контейнер
и уникальный образ. Нет compose down общего стенда, volume deletion или Docker prune.
Оставшиеся после аварийного убийства процесса ресурсы можно найти по сохранённым
commands.json/cleanup.json; не очищайте глобальные ресурсы.

## Последовательность

1. Инициализация точного seed v1 из #106, ANALYZE только как подготовка. Seed DB затем
   неизменна. Fingerprint сохраняет числа строк, checksum упорядоченных records,
   heap bytes и список прежних индексов. Общий workload — demo/scenarios/workload.json.
2. Отдельный физический клон для наблюдения. Снимается настоящий table-stats before;
   выполняются SELECT и известные committed INSERT/UPDATE/DELETE; выдерживается окно
   и задержка публикации; снимается after. HOT не вычитается из UPDATE.
3. `benchmarkObservedWorkload` вызывает именно #107 calculator и заменяет ручные rates
   двух workload-таблиц на observed + полное origin. Read-профили normal/peak, budgets,
   SQL, guards, режим и остальные настройки #106 сохраняются. Никакого ожидания #109.
4. Analyze самостоятельно запускается на другом исходном seed-клоне; его сохранённые
   workload/input hash/run ID/indexes.sql фиксируются. Профиль наблюдения с его db OID
   относится к observational clone, а не скрыто к другой production-БД.
5. Перед **каждым** вариантом и повтором `CREATE DATABASE ... TEMPLATE benchmark_seed`
   копирует исходные физические файлы; fingerprint проверяется до создания новых индексов.
   Baseline сохраняет прежние индексы; selected получает точный сохранённый indexes.sql.
   Число и имена созданных индексов сверяются с selected_indexes. Пустая рекомендация
   допускается и честно даёт тот же набор: бюджет/данные не меняются ради выигрыша.
6. CHECKPOINT после подготовки, затем одинаковый фиксированный read-only warmup всех
   SELECT из #106, без DML. Измерения — ещё один проход SELECT (read) либо тех же SELECT
   + INSERT + исходные UPDATE/DELETE (mixed). Внутри повтора порядок SQL фиксирован.
   Baseline/selected чередуются: B/S, S/B, B/S. DML выполняется только один раз на свежем
   варианте, поэтому DELETE не превращается в повторяемый no-op. Нулевые affected rows
   любого вида записи считаются провалом прогона.
7. Каждый завершённый statement — фактический call; соответствующие rows из command tag
   либо CSV результата. Нет весов script sampling и нет обещаний calls/hour. Наблюдаемые
   rates берутся из настоящих counters/окна #107, не вычисляются из этих script weights.

## Измерения и файлы

- `before/`, `after/`: исходные реальные снимки #102 и журналы capture;
- `workload.json`: точный вход observed analyze; `recommendation/`: report JSON/HTML/SQL;
- `identity.json`: run ID/input hash, selected IDs и SHA-256 indexes.sql;
- `environment.json`: версии, seed hash, настройки, лимиты ресурсов/клиентов/транзакций;
- `operations.json`: точные SQL каждой смеси, INSERT отдельно от анализатора;
- `*-N-baseline/selected/*.log.gz`: сырые psql CSV/command tags/timing/stderr;
- `samples.json`, `trials.json`: отдельные операции/повторы, rows, psql ms, wall seconds,
  WAL bytes и физические размеры после измерений;
- `summary.json`: calls, rows, TPS, rows/sec, median/min/max/stdev latency по виду операции,
  WAL/завершённый call и WAL/row; ошибки успешного прогона равны нулю;
- `failure.json`/trial error.json: неуспех, timeout, сохранённые сырые логи и ранее
  завершённые операции. Неполный прогон не помечается completed;
- `commands.json`: выполненные команды без секретов, включая очистку; `cleanup.json`.
- `commands/*.log.gz`: stdout/stderr подготовки, SQL-проверок и cleanup по номеру команды.

Latency `psql_ms` включает выполнение и обработку/передачу результатов psql. Wall time
отдельно включает запуск docker exec и соединение. TPS и rows/sec каждого вида операции
используют **всё измеряемое окно trial**, включая пробы WAL и сохранение логов; это
наблюдаемый throughput этого инструментированного одноклиентного runner. Размер группы
samples мал, не является надёжной оценкой production p99. Латентности чтения и каждого
типа записи не смешиваются в один показатель. Сырые повторы сохраняют разброс.

WAL — разность pg_current_wal_insert_lsn до/после statement в **целом собственном
кластере**; создание индексов/подготовка/прогрев вне этой области. Background WAL может
попасть в интервал, это не точная атрибуция индексу. Недоступный WAL — null, не 0.
Нормированные WAL/call и WAL/row рассчитаны по завершённым операциям/фактическим rows.
Ресурсы/pg settings одинаковы, но OS cache не сбрасывается; physical clone не доказывает
одинаковое состояние всех кэшей. Это warm-cache эксперимент, не гарантия cold-start/SLA.

Planner cost/write proxy остаются в исходном отчёте рекомендации; измеренная latency,
WAL и реальные bytes — в benchmark artifacts. Отсутствие ускорения, пустой набор и рост
цены записи не фильтруются. После реального прогона сохраните его артефакты, полный
commit SHA и оцените ограничения перед закрытием #110. Не коммитьте секреты или
произвольные DSN вместе с результатами.
