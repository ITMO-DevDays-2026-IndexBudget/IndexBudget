# Снимки pg_stat_statements v1

Независимая команда плана #64: сбор снимков не меняет форматы workload и отчётов
или поведение обычного `analyze`. Снимок — исходные наблюдения, а не готовый workload: нормализованный
SQL может содержать `$1`, неподдержанные конструкции и не иметь подходящих значений параметров.
Коллектор не подставляет литералы и не выполняет наблюдаемые запросы.

## Запуск

Подключение задаётся прежней переменной `INDEXBUDGET_DATABASE_URL`; она не записывается
в файлы и ошибки. `source-id` — назначенный оператором UUID без имени сервера и DSN:
сохраняй его для одной БД, используй другой для другой БД/клона/пересозданного источника.
UUID — метка доверенной конфигурации, а не автоматически проверенная идентичность сервера.

```sh
build/install/indexbudget/bin/indexbudget stats --help
build/install/indexbudget/bin/indexbudget stats snapshot --source-id d0d60c19-eb9d-42ee-9e01-935560eb7e20 --output-dir snapshots/before
build/install/indexbudget/bin/indexbudget stats pair --source-id d0d60c19-eb9d-42ee-9e01-935560eb7e20 --output-dir snapshots/pair --interval-seconds 60
```

Каталог должен отсутствовать. Каждый снимок публикуется отдельно как `snapshot-1.json`
и, для пары, `snapshot-2.json`. `capture.json` содержит состояние всей команды и ссылки
на успешно сохранённые файлы. Сбой второго чтения/прерывание ожидания не удаляет первый
снимок. После аварийного завершения процесса manifest может отсутствовать; опубликованный
снимок остаётся самостоятельным документом. Временные файлы публикуются атомарным
перемещением на той же файловой системе; неподдерживаемая операция даёт ошибку.

Интервал 1–86400 секунд отсчитывается монотонными часами **после завершения первого
снимка**, а не между началами. Во время ожидания нет открытого JDBC-соединения.
Каждое чтение имеет новый RunContext, не начинает search window и не тратит EXPLAIN.

| Параметр | По умолчанию | Допустимо |
| --- | --- | --- |
| `--timeout-seconds` | 30 | 1–300 на один снимок, включая соединение |
| `--max-rows` | 10000 | 1–100000 |
| `--max-bytes` | 8388608 | 32768–67108864, включая JSON metadata |
| `--max-query-bytes` | 65536 | 1–1048576 |

Нет сортировки по стоимости, calls или top-N. Читается вся доступная область текущей
БД, включая все роли и оба значения `toplevel`. Порядок строк не является контрактом.
Лимит строк проверяется чтением дополнительной строки. JDBC читает курсором с fetch size
128; для output budget учитывается размер экранированного UTF-8 JSON. 16 KiB бюджета
зарезервированы для ограниченной metadata. SQL сверх max-query-bytes не передаётся
клиенту: сохраняются counters и `text_status=size_limit`, сам текст — null, без обрезки.
Лимиты клиента не гарантируют такой же объём памяти внутри расширения PostgreSQL.
Перед каждым ResultSet.next() проверяется отмена/deadline и обновляется socket timeout
по текущему остатку. Deadline guard действует до закрытия всего курсора и физически
прерывает соединение при отмене/исчерпании времени, включая заблокированный FETCH.
SQL/socket timeouts ограничиваются остатком; задержки драйвера и диска не являются SLA.

## Формат

`schema_version: 1`, `kind: pg_stat_statements_snapshot`. Корень содержит:

- `snapshot_id`, `source_id`: UUID;
- `started_at`, `finished_at`: UTC ISO-8601, границы чтения, включая подключение;
- `postgres_version`, `extension_version`;
- `scope`: database_oid, reader_userid, all_query_texts_visible, rows=current_database_all_users,
  ranking=none, track (настройка pg_stat_statements.track);
- `before`, `after`: stats_reset (UTC ISO-8601), dealloc; after=null, если не удалось дочитать;
- `status`: completed/partial, `reasons`, `row_count`, `unavailable_text_count`, `limits`;
- `entries`: dbid, userid, queryid, toplevel, query, text_status, calls, total_exec_time, rows.

`queryid` — **десятичная строка bigint** либо null: JS не должен терять точность.
`total_exec_time` измеряется в миллисекундах. Calls/rows — накопительные counters, не частоты.
Для связывания записей нужны source_id, dbid, userid, queryid и toplevel; одного queryid
недостаточно. Записи с недоступным queryid нельзя объединять по null или тексту-заглушке.

`text_status`: available, restricted (не виден queryid), unavailable (расширение потеряло
текст), size_limit. Недоступные из-за прав тексты и IDs остаются null, counters сохраняются.
`completed` означает полный проход по доступным counters, а не наличие текста каждой записи.
`partial` возникает при ROW_LIMIT, BYTE_LIMIT, QUERY_TEXT_LIMIT, DEADLINE_EXCEEDED,
CANCELLED, READ_FAILED, STATISTICS_PERMISSION_DENIED, STATS_RESET_DURING_READ или
DEALLOC_DURING_READ. Такие снимки нельзя выдавать за полный baseline наблюдаемой нагрузки.

Manifest `capture.json` имеет свою schema_version=1, kind=pg_stat_statements_capture,
status=completed/partial/failed, error (без сырых исключений), interval_seconds,
conditions_match и массив snapshots с path/snapshot_id/status. `conditions_match` сравнивает
источник/область/версии и reset/dealloc на обеих границах двух полных снимков; отсутствие
второго снимка даёт null. Это необходимая проверка metadata, **не гарантия неизменности**
данных или отсутствия выборочного reset: расширение не предоставляет атомарный снимок
всех counters. Дельты нужно дополнительно проверять на уменьшение counters и смену ключей.

SQL-тексты могут содержать пользовательские литералы: снимки не следует коммитить как
обычные fixtures. Коллектор не добавляет настройки подключения и не публикует сообщения
драйвера. Коды выхода: 0 completed, 3 partial, 2 невалидные параметры, 1 failed.

## Подготовка PostgreSQL 16

Администратор отдельно включает pg_stat_statements в `shared_preload_libraries`,
перезапускает PostgreSQL при необходимости и выполняет `CREATE EXTENSION pg_stat_statements`
в нужной БД. Нужен включённый compute_query_id (`auto` или `on`). Коллектор сам не выполняет
эти действия, не вызывает reset и не изменяет настройки. Для полной видимости чужих текстов
администратор может выдать `pg_read_all_stats`; без неё доступные counters всё равно снимаются.
SELECT на views и USAGE на схему расширения должны быть доступны роли.

Общую demo/stand-конфигурацию этот PR не меняет. Изменения стенда выполняются только по
[deploy/db/README.md](../deploy/db/README.md). Источник семантики полей и прав —
[PostgreSQL 16 pg_stat_statements](https://www.postgresql.org/docs/16/pgstatstatements.html).

## Проверка

```sh
./gradlew --no-daemon build installDist
bash demo/check-statistics.sh
```

Второй скрипт создаёт отдельный PostgreSQL 16.15 с preload, случайным локальным портом,
и удаляет только свой контейнер. Tests проверяют реальные изменения counters, скрытые
тексты, отсутствие расширения, row cap и закрытие connections. Unit tests проверяют
byte cap/JSON escaping, reset во время чтения, deadline в prepareStatement, обезличивание
ошибок и сохранение первого снимка при прерывании пары.

[Пример snapshot v1](../examples/statistics-snapshot-v1.json) содержит **синтетические**
OID/queryid/counters для демонстрации формата, не результат измерения PostgreSQL.

CI job `postgres-statistics-integration` вызывает `demo/check-statistics.sh` на
одноразовом runner и сохраняет XML/HTML результаты. Скрипт дополнительно проверяет,
что PostgreSQL integration suite действительно выполнен и не содержит skipped-тестов.
В domain `QueryTextStatus` и `SnapshotReason` — enum; wire-значения JSON v1 сохранены
(статусы lower-case, причины upper-case). Restricted-записи не расходуют лимит текста:
длина заглушки не является длиной недоступного SQL.
