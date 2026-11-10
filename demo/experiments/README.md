# Эксперименты приёмки #21

Этот набор запускается отдельно от `analyze`. Он сохраняет исходные входы,
общий shortlist, версии, реальные costs/планы, времена поиска, счётчики,
полные JSON/HTML/SQL каждого запуска и сводку `summary.json`.
Результаты не подменяются ожидаемыми ответами: совпавшие наборы, отсутствие
улучшения repair и `partial` остаются видны.

## Одноразовая БД

Нужны JDK 25, Docker/Compose v2 и Python 3. Команды выполняются из корня проекта.
Этот Compose использует отдельное имя `indexbudget-experiments`, loopback-порт
55434 и **tmpfs** вместо общего volume. Остановка/пересоздание удаляет его данные.
Не применяйте эти команды к общей БД из `deploy/db`.

```sh
sh demo/init-env.sh
docker compose --env-file .env -f demo/experiments/compose.yaml up --build --detach --wait --wait-timeout 180
```

Версии и seed совпадают с [основным demo](../README.md): PostgreSQL 16.15,
HypoPG 1.4.2, seed v1. Дополнительный закрытый маркер разрешает физические
измерения только на этой БД. Пароли находятся в игнорируемом `.env`.

Для planner-прогона подключите роль `indexbudget_reader` к loopback-порту 55434:

```sh
export INDEXBUDGET_DATABASE_URL='jdbc:postgresql://127.0.0.1:55434/indexbudget?user=indexbudget_reader&password=<LOCAL_READER_PASSWORD>'
./gradlew plannerExperiments --args='--workload demo/experiments/workload.json --sensitivity demo/experiments/sensitivity.json --output-dir reports/issue-21'
```

Каталог результата должен быть новым. Его можно перенести в Git после проверки
на секреты; DSN не является частью артефактов. Поиск выполняется настоящими
production-компонентами и HypoPG. Каждый запуск получает обычные лимиты
90/60 секунд, 2000 EXPLAIN, 28 repair attempts; они также записаны в отчёты.

## Раздельные сравнения

| Вопрос | Сопоставляемые каталоги | Что фиксируется |
| --- | --- | --- |
| Критерий | `robust-repair`, `mean-repair` | Общие SQL, shortlist, бюджеты, стратегия и лимиты |
| Стратегия | `robust-greedy`, `robust-repair` | Один objective, SQL, shortlist, бюджеты и лимиты |
| Место | `robust-repair`, `storage-tight` | Только storage budget: 64 MiB → 1 MiB |
| Запись | `robust-repair`, `write-tight` | Только write budget: 40000 → 1000 units/hour |
| Крайние бюджеты | `write-disabled`, `write-zero`, `baseline-only`, `write-profile-absent` | Отключённый лимит с полным профилем, нулевой лимит, max_indexes=0, отсутствие профиля |

Первый производственный shortlist замораживается по стабильным IDs, затем
каждый запуск независимо получает baseline и размеры в своей сессии. В сводке
есть проверка равенства baseline; если она ложна, сравнение нельзя считать
контролируемым до выяснения изменения данных/статистики. У budget variants
shortlist тоже общий, бюджет не запускает генератор заново.

`workload.json` содержит три SQL, два профиля, две таблицы и различные rates:
4100 row changes/hour для records, 41 для groups. `max_indexes=1` создаёт
конкуренцию рекомендаций. Фиксированные ненулевые tighter budgets показывают
изменение допустимости; изменение самой рекомендации проверяется полями
`storage_changed_recommendation` и `write_changed_recommendation`. Значение
`false` является непройденным демонстрационным критерием, а не успехом.

Дополнительные частоты заранее закреплены в `sensitivity.json`. Они **не**
участвуют в выборе кандидатов/наборов: общая `MetricsCalculator` применяет их
после поиска к сохранённым полным costs. Сводка сохраняет SHA-256 обоих входов.
Не подбирайте holdout после просмотра результата.

Для малого эталона используйте отдельную Gradle-задачу `exhaustiveOracle`:

```sh
./gradlew exhaustiveOracle --args='--workload demo/experiments/workload.json --output reports/issue-21/oracle.json --deadline-seconds 120'
```

В данном входе только три одиночных кандидата; полный перебор проверяет то же
пространство. Сверьте IDs с `frozen_candidate_ids` сводки. Для другого входа
сначала явно задайте пространство ≤8 кандидатов; никакого молчаливого усечения.
Эталон действителен только при полном завершении и только внутри этого пула.

## Фактические размеры и времена

Следующая команда **явно создаёт физические индексы и выполняет запросы**,
поэтому существует отдельно от planner-прогона. Она обращается только к
`indexbudget-experiments`, проверяет закрытый маркер БД, строит DDL из
канонических определений финального набора и откатывает его транзакцией.

```sh
python3 demo/experiments/physical.py --confirm-disposable \
  --env-file .env \
  --reports reports/issue-21/robust-repair/report.json reports/issue-21/mean-repair/report.json reports/issue-21/robust-greedy/report.json \
  --output reports/issue-21/physical.json
```

В каждом варианте два прогревочных и семь измеряемых повторов каждого SQL.
Есть два раунда: baseline → заданный порядок вариантов, затем обратный порядок.
Сохраняются каждый `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, фактический
`pg_relation_size`, median/min/max/stdev Execution Time в ms, порядок и версии.
Настройки планировщика не меняются. Время содержит overhead инструментации;
это замер с прогретым кешем, а не обещание пользовательской задержки.

Для отдельного write microbenchmark добавьте `--write-benchmark`. Это INSERT,
UPDATE и DELETE batches по records, UPDATE по groups; каждый sample находится
в SAVEPOINT и откатывается до следующего. Строки восстанавливаются, но WAL,
кеши и физические мёртвые версии PostgreSQL откатом не восстанавливаются.
Такой microbenchmark фиксирует сырые наблюдения и не даёт основания заявлять
production write slowdown. Для сильного вывода нужен отдельный прогон с
восстановлением snapshot/свежей БД перед каждым вариантом и представительными
операциями записи. Write units из отчёта не являются этим измерением.

```sh
docker compose --env-file .env -f demo/experiments/compose.yaml down
```

Сводки настоящих прогонов и их ограничения добавляются в репозиторий отдельно.
Наличие этого harness само по себе не означает прохождения приёмки или сверки
расчётов всеми участниками команды.
