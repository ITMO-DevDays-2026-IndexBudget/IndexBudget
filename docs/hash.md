# Одноколоночный hash (#115)

Явное включение в workload v2: `"index_methods": ["btree", "hash"]` либо `["hash"]`.
Допускается и смесь с BRIN. Без поля остаётся только B-tree. Порядок массива не
задаёт приоритет; дубли, null, пустой массив и неизвестный метод отклоняются.
[Полный пример](../examples/workload-v2-hash.json),
[нормативные правила](../IndexBudget-spec-v0.2.md#явно-включаемый-hash-115).

Генерация только по простому скалярному WHERE `column = literal`, включая обратную
запись. IN/IS NULL/диапазон/ORDER BY/JOIN и сравнение двух колонок hash не создают.
Применимый лист AND/OR сохраняет собственное происхождение. Одна колонка,
default collation/opclass, без INCLUDE, направлений, выражений и настройки fillfactor.
Ручной `{ "schema":"hash_demo", "table":"events", "method":"hash",
"keys":[{"column":"sequence"}] }` независим от автоматического выбора методов;
даже явные `direction: "asc"` / `nulls: "last"` запрещены.

Каталог, ID, DDL, удаление точных реальных дублей, подготовка и оценка используют
общую реализацию #114. Отсутствующий opclass — `NO_HASH_OPERATOR_CLASS`; источник —
`HASH_EQUALITY`. Реальный hash с reloptions не считается точным дублем определения
с параметрами по умолчанию. B-tree IDs и default не меняются. Все методы вместе
проходят caps 16 колонок до пар, 976 событий, 32 локальных / 16 подготовленных,
резерв 48 и 32 size-попытки; отдельной квоты на метод нет.

Обычный анализ не создаёт физические индексы, не меняет планировщик и не выполняет
ANALYZE/EXPLAIN ANALYZE. Полезность смешанного набора измеряется полным вектором
costs, не суммой одиночных выигрышей. Размер с запасом и write budget общие.
Write proxy по числу колонок **не измеряет** разницу реальной цены записи методов.
Hash не гарантирует преимущество перед B-tree.

## Артефакты и совместимость

При разрешённом или ручном hash используются report v4 и сводка серии v2, в том
числе при пустом наборе, failed/partial. Структура и единицы method-aware форматов
не меняются, расширяется enum методов; неизвестные методы consumers должны
отклонять. B-tree-only остаётся report v3 / сводка v1. Workload v2 и запрос серии
v1 сохраняются; старый строгий reader не принимает hash. В JSON direction/nulls
hash равны null, DDL не содержит сортировки. JSON/HTML/SQL сохраняют один run ID,
input hash, метод и выбранный набор. [Отчёт](report-v3.md), [серии](budget-series.md).

## Воспроизводимая проверка

```sh
bash demo/check-hypopg.sh
```

Нужны JDK 25 и Docker. Существующий скрипт создаёт собственный одноразовый
PostgreSQL 16.15/HypoPG 1.4.2 без общей `.env` или адреса стенда. Hash integration
test готовит `hash_demo.events`: 200 000 строк с integer sequence, varchar(160)
COLLATE C и json без hash opclass. Данные и VACUUM ANALYZE готовятся до анализа.

Проверяются baseline, B-tree, hash и смешанный набор с полными costs/размерами,
наличие пользы равенства и отсутствие снижения cost чтения всех строк, opclass и
collation (также varchar), точные дубли/reloptions, cleanup после ошибочного opclass
и повторный baseline, ручной набор, storage/write-серии из трёх точек, отсутствие
refill/size-вызовов в точках, версии/согласованность артефактов и закрытие сессий
до экспорта. Физические индексы создаются только отдельным шагом тестовой фикстуры
и удаляются до pipeline. Стенд не затрагивается.

Результаты: `build/hash-verification/run-*/measurements.json`, analysis и обе серии.
CI публикует artifact `hash-verification`. Конкретные costs и выбор метода зависят
от версии/статистики; до реального прогона значения не заявляются. Проверка не
измеряет runtime, TPS, WAL или физический размер и не обещает SLA.

[PostgreSQL: hash](https://www.postgresql.org/docs/16/hash-intro.html),
[HypoPG: поддерживаемые методы](https://hypopg.readthedocs.io/en/rel1_stable/usage.html#supported-access-methods).
