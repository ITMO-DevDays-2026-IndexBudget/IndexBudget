# Demo DB — уровень 1, задача #4

Общий стенд для SQL, каталога, генератора и HypoPG-адаптера. PostgreSQL
**16.15** (образ `postgres:16.15-bookworm`), HypoPG **1.4.2**, seed **v1**.
Версии закреплены тегами; системные build-пакеты устанавливаются из apt.
Первой сборке нужен интернет. Нужен Python 3 для генерации локального .env. JDK и сборка CLI для этого стенда не нужны.

## Запуск

Из корня репозитория `IndexBudget`, при работающем Docker Engine и Compose v2:

```sh
sh demo/init-env.sh
docker compose up --build --detach --wait --wait-timeout 180
sh demo/check.sh
```

Healthcheck проверяет TCP-подключение ролью анализа и маркер **завершённого**
seed, а не только наличие процесса PostgreSQL. Smoke check выводит версии,
планы и costs до/после гипотетического индекса, оценочный размер и проверяет
cleanup, отсутствие новых физических индексов и восстановление baseline.
Повторный `sh demo/check.sh` должен проходить на той же БД.

Если БД создана ранней версией seed и проверка падает с
`permission denied for view hypopg_list_indexes`, выдайте недостающее право
без удаления данных (новые БД уже получают его при seed):

```sh
docker compose exec -T postgres psql -X -U demo_admin -d indexbudget \
  -v ON_ERROR_STOP=1 \
  -c 'GRANT SELECT ON public.hypopg_list_indexes TO indexbudget_reader;'
sh demo/check.sh
sh demo/check.sh
```

```sh
docker compose logs postgres
docker compose ps
docker compose stop
docker compose start --wait
```

Обычный перезапуск сохраняет volume и не повторяет seed. Init-скрипты выполняются
только на пустом volume. Если seed изменился или первичная инициализация оборвалась,
пересоздайте **только этот одноразовый demo-стенд** (команда удалит его данные):

```sh
docker compose down --volumes
sh demo/init-env.sh
docker compose up --build --detach --wait --wait-timeout 180
sh demo/check.sh
```

## Подключение команды

С хоста: `127.0.0.1:55432`, БД `indexbudget`, пользователь
`indexbudget_reader`. Пароль находится в локальном `.env`, который создаётся
командой `sh demo/init-env.sh` со случайными значениями и исключён из Git.
Порт опубликован только на loopback. Не публикуйте содержимое `.env`.

```sh
export INDEXBUDGET_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/indexbudget?user=indexbudget_reader&password=<PASSWORD_FROM_LOCAL_ENV>'
```

Если порт занят, задайте `INDEXBUDGET_DEMO_PORT=55433` перед `docker compose up`
и поменяйте порт в JDBC URL. Из контейнера в той же Compose-сети адрес —
`postgres:5432`. `.env.example` — шаблон; CLI не загружает `.env` автоматически.

Интерактивная сессия без установки psql на хосте:

```sh
docker compose exec postgres sh -c 'export PGPASSWORD="$INDEXBUDGET_READER_PASSWORD"; exec psql -X -h 127.0.0.1 -U indexbudget_reader -d indexbudget'
```

Роль анализа не superuser, не владеет таблицами, имеет SELECT и доступ
к HypoPG, но не CREATE в public, не запись в таблицы и не TEMP в этой БД.
Владелец `demo_admin` используется entrypoint только для подготовки стенда.
Новые подключения наследуют обычные настройки планировщика; они не меняются
для получения красивого плана.

Compose позволяет задать `INDEXBUDGET_ADMIN_PASSWORD` и
`INDEXBUDGET_READER_PASSWORD` перед первым созданием БД. Seed получает пароль
reader из окружения контейнера. Для существующего volume изменение переменной
не меняет пароль PostgreSQL-роли. Smoke check берёт reader-пароль из окружения контейнера; не сохраняйте
реальные пароли в git.

Общий WSL-стенд, миграции и сохранение данных при обновлении описаны в
[deploy/db](../deploy/db/README.md).
Локальный `docker compose down --volumes` из этого README **не относится**
к общей БД. На общем стенде используется отдельный external volume.

## Данные и входы для интеграции

| Таблица | Строки | Назначение |
| --- | ---: | --- |
| `public.records` | 200 000 | PoC, выбор по group_id/value/created_at; существующий PK по id |
| `public.groups` | 1 000 | Второй write rate и последующие JOIN-сценарии; PK по id |
| `public.wide_records` | 10 000 | Ровно 100 integer-колонок c1…c100, без индексов |

Данные вычисляются из generate_series без random() и текущего времени.
Все три таблицы проходят ANALYZE при seed. Содержимое данных воспроизводимо;
точные planner costs могут отличаться из-за выборки статистики и окружения.
В runtime команда анализа ANALYZE не выполняет.

- `demo/workload-poc.json`: одна таблица, три SELECT, два профиля, полный
  ручной write profile и оба бюджета. Для сравнения objective скопируйте файл
  и замените `robust` на `mean`, сохраняя остальные параметры.
- `demo/workload-wide.json`: SELECT * с признаками c1/c2 и отдельный SQL со
  100 условиями. Это вход для будущей проверки AST/каталога/candidate caps.
- Уже существующие `examples/workload-v2*.json` используют эту же records.

Данные и SQL готовы для подключения модулей. Автогенерация ≤8 кандидатов,
бюджетные рекомендации, оба objective и отчёты относятся к сквозному PoC #22;
этот стенд сам по себе не подтверждает их готовность. CLI `analyze` пока
остаётся каркасом. Проверка через psql не заменяет будущий JDBC evaluator #13.

## Проверка HypoPG вручную

Выполните весь блок в **одной** psql-сессии:

```sql
SELECT hypopg_reset();
EXPLAIN (FORMAT JSON)
SELECT id, value FROM public.records
WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20;

SELECT * FROM hypopg_create_index(
  'CREATE INDEX ON public.records (group_id, created_at DESC)');
SELECT index_name, hypopg_relation_size(indexrelid) AS estimated_bytes
FROM hypopg_list_indexes;

EXPLAIN (FORMAT JSON)
SELECT id, value FROM public.records
WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20;
SELECT hypopg_reset();
SELECT count(*) FROM hypopg_list_indexes;
```

Гипотезы принадлежат соединению и работают в EXPLAIN без ANALYZE;
см. [документацию HypoPG](https://hypopg.readthedocs.io/en/rel1_stable/usage.html).
Cost — оценка планировщика, размер — оценка HypoPG, не фактическое время/место.
Smoke check не требует заданного процента выигрыша. Физические измерения и
write benchmark выполняются отдельно после готовности поиска.

## Автоматическая проверка

Workflow `Demo DB` собирает образ, ждёт seed и запускает smoke check дважды.
При ошибке сохраняет логи; тестовый volume удаляется только на CI runner.
Это проверка #4; cleanup после JDBC-ошибок, cancellation и isolation
адаптера будут проверяться в #13/#15.
