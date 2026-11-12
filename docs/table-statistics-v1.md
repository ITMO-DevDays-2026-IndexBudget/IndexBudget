# B1 / #102: снимки счётчиков записи таблиц

Независимый read-only вход для #107. Команды:

```sh
indexbudget table-stats snapshot --source-id 4ada107c-d989-4e80-9836-7fc1d14182cf --output-dir snapshots/tables-before
indexbudget table-stats pair --source-id 4ada107c-d989-4e80-9836-7fc1d14182cf --output-dir snapshots/tables-pair --interval-seconds 60
indexbudget table-stats --help
```

Подключение: `INDEXBUDGET_DATABASE_URL`. UUID назначает оператор одной конкретной БД;
для клона/пересозданной БД назначается новый UUID. Это доверенная метка источника,
а не автоматически проверенная идентичность кластера. Файлы не содержат DSN, паролей,
сетевого адреса или текстов исключений драйвера. Физические имена таблиц сохраняются.

Каталог вывода должен отсутствовать. Команда публикует `snapshot-1.json`, для пары также
`snapshot-2.json`, и `capture.json` с путями, статусами и диагностикой сопоставимости.
Файлы публикуются по одному через соседний временный файл и атомарное перемещение.
Ошибка/отмена второго чтения не удаляет первый снимок. При аварийном завершении manifest
может отсутствовать; уже опубликованные снимки самостоятельны.

Интервал 1–86400 секунд начинается после первого чтения и измеряется монотонными часами.
Во время ожидания соединение закрыто. У каждого чтения новый RunContext, без search window.

## Область и ограничения

Читаются текущие обычные (`relkind=r`) пользовательские таблицы текущей БД, включая
обычные partition leaves. Partitioned parents, foreign tables, views, системные/временные
`pg_*` схемы исключены явно. Полный каталог соединяется LEFT JOIN с
`pg_catalog.pg_stat_user_tables`, чтобы отсутствие записи статистики не скрывало таблицу.
Чтение не требует SELECT на прикладную таблицу: публично доступные counters снимаются
и при `select_access=false`. Это поле описывает права на данные таблицы, а не наличие
прав на статистику. Отказ чтения metadata/views диагностируется отдельно.

Лимиты на снимок:

| Опция | Default | Границы |
| --- | --- | --- |
| `--max-rows` | 10000 | 1–100000 |
| `--max-bytes` | 8388608 | 32768–67108864 |
| `--timeout-seconds` | 30 | 1–300 |

JSON budget включает metadata и UTF-8/escaping; 16 KiB резервируются под metadata.
SQL читает не более max_rows+1 строк, упорядоченных по OID, для явного обнаружения row cap.
Autocommit даёт свежий статистический cache каждой metadata-операции. pgJDBC может загрузить
весь ограниченный SQL result до клиентского byte cap: max_rows ограничивает этот дополнительный
объём; max_bytes ограничивает публикуемый файл. Это не обещание точного объёма памяти сервера.

Переиспользован `JdbcSnapshotReader`: deadline до/после execute/next, свежий socket timeout,
абсолютный deadline guard на всё чтение, физическое прерывание зависшего соединения.
Коллектор закрывает физическое соединение на всех путях. Не выполняются SET, GRANT,
ANALYZE, reset/clear snapshot, DDL или запросы прикладного workload. Запись на диске и
задержки сторонних вызовов не являются SLA точного времени возврата.

## Snapshot JSON v1

`kind=pg_table_statistics_snapshot`, `schema_version=1`. Это отдельный формат, не snapshot
pg_stat_statements и не workload v2. Корневые поля:

- snapshot_id, source_id (UUID), database_oid, reader_userid, postgres_version;
- track_counts, scope=current_database_ordinary_user_tables;
- started_at/finished_at (UTC ISO-8601), postmaster_started_at;
- database_stats_reset_before/after (UTC ISO-8601 либо null);
- reset_detection=best_effort_pg16;
- status=completed/partial, reasons (enum wire codes), limits, row_count, entries.

Каждая entry: relation_oid, schema, name, select_access и nullable bigint counters
n_tup_ins/n_tup_upd/n_tup_del/n_tup_hot_upd. HOT updates сохраняются отдельно и **не
вычитаются** из n_tup_upd. Результат — накопительные counters, не рассчитанные rates.
Идентичность записи: source_id + database_oid + relation_oid; имя не заменяет OID.
Числа bigint потребители должны читать без потери точности. Query IDs отсутствуют.

При track_counts=off все counters равны null: возможные старые значения не выдаются за
актуальные измерения. Нет строки статистики — counters также null, а не нули. Reasons:
TRACK_COUNTS_OFF, COUNTERS_UNAVAILABLE, ROW_LIMIT, BYTE_LIMIT, DEADLINE_EXCEEDED,
CANCELLED, READ_FAILED, RESET_DETECTED. Null stats_reset сам по себе допустим: на свежей
БД это не доказательство ошибки и не доказательство отсутствия сбросов.
При неизвестном after после ошибки/таймаута снимок partial. До получения source metadata
ошибка завершает команду failed; после metadata можно сохранить неполный prefix.

## Capture JSON v1 и условия сравнения

`kind=pg_table_statistics_capture`, schema_version=1; status, error, interval_seconds,
conditions_match, reset_detection, snapshots (relative path/snapshot_id/status), diagnostics.
Коды diagnostics: SOURCE_OR_CONDITIONS_CHANGED, RESET_METADATA_CHANGED,
INCOMPLETE_SNAPSHOTS, RELATION_DISAPPEARED, RELATION_APPEARED, RELATION_RENAMED,
COUNTERS_DECREASED. У relation-диагностик есть relation_oid. Неполный снимок не даёт
оснований заявить, что отсутствующая запись означает удаление таблицы. При изменении
источника записи по OID не связываются. Изменение условий/диагностика пары даёт partial.

Exit 0 — полные чтения и отсутствие обнаруженных различий условий; 3 — partial; 2 —
невалидный CLI-вход; 1 — failed. Совпадение conditions_match не является гарантией
стабильности БД. Адресный reset может остаться незамеченным: PG16 не предоставляет
отдельного timestamp сброса каждой таблицы. Сброс с последующим ростом выше прежнего
значения невозможно надёжно определить по двум снимкам. Изменение database stats_reset,
перезапуск сервера и уменьшение counters дают наблюдаемые сигналы, но не полное покрытие.

Статистика публикуется с задержкой: ещё выполняющаяся транзакция/писатель может не
успеть обновить shared counters. Обычная минимальная периодичность публикации порядка
секунды; короткое окно не гарантирует точных rates. track_counts — наблюдаемая настройка
коллектора; другие backend могут менять её у себя. Коллектор не меняет её за них.

Семантика источника: [PostgreSQL 16 cumulative statistics](https://www.postgresql.org/docs/16/monitoring-stats.html).
Пример пары — [table-statistics-pair-v1.json](../examples/table-statistics-pair-v1.json):
синтетические данные формата, не физический эксперимент. #107 должен проверить
идентичность, полноту, интервал и диагностику до преобразования counters в профиль записи.

## Воспроизводимая проверка

```sh
./gradlew --no-daemon build installDist
bash demo/check-table-statistics.sh
```

Скрипт создаёт отдельный PostgreSQL 16.15 без расширений и удаляет только свой контейнер.
CI job postgres-table-statistics-integration запускает тот же скрипт и проверяет отсутствие
skipped/failures в PostgreSQL suite. Тестовый писатель вызывает pg_stat_force_next_flush
для воспроизводимой публикации известной нагрузки; production collector этого не делает.
Reset/track_counts=off/DDL в tests — только явная подготовка одноразовой БД, не работа CLI.

Общий `SnapshotCaptureRunner` обслуживает query/table команды: владеет отменой,
раздельными RunContext, ожиданием и атомарной публикацией. Специфичные parse/manifest
и диагностика остаются в командах. Общие тесты проверяют отмену во время ожидания,
ошибку второго чтения/публикации, сохранение первого снимка и очистку staging.
Дополнительные проверки: успешная маршрутизация через IndexBudgetCli, совместимая
пара с растущими counters, фактический ненулевой HOT и database reset между двумя
чтениями metadata одного collect() из второго PostgreSQL-соединения.
