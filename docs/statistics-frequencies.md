# Частоты окон pg_stat_statements (#66)

`FrequencyCalculator` принимает настоящие `StatisticsSnapshot` коллектора #65:
`calculate(listOf(FrequencyWindow("day", 1.0, before, after)), context)`.
Результат — `FrequencyProfiles` для импортёра #67; `FrequencyJson.render` сохраняет
независимый формат v1 `pg_stat_statements_profiles`. Это расчёт над выходом
коллектора без JDBC и исполнения SQL. Workload/report v2 и существующие CLI-команды
не меняются; CLI импорта и выбор примеров литералов относятся к #67.

## Время и идентичность

Ключ: `(source_id, dbid, userid, queryid, toplevel)`. Все окна требуют одинаковых
source UUID, database OID, reader userid, видимости, версий PostgreSQL/расширения
и track. Поддержаны PostgreSQL 16, track=top/all. Source UUID остаётся меткой
оператора, не доказательством идентичности сервера. Роли и уровни вложенности
с одинаковым queryid не объединяются.

Чтение снимка не атомарно. Представительная точка — середина started_at/finished_at,
округлённая вниз до наносекунды. `Δseconds = midpoint(after) − midpoint(before)`;
`calls_per_hour = Δcalls × 3600 / Δseconds`. IDs снимков должны отличаться,
интервалы чтения — быть корректными и не перекрываться, Δseconds — быть положительным.
Это оценка частоты по точкам чтения, не точное время каждого вызова. Сохраняется
диапазон возможной длительности: `after.started − before.finished` …
`after.finished − before.started`. Некорректное время/источник — `INVALID_INPUT`.

1–3 именованных окна дают независимые профили. IDs уникальны; один pair нельзя
передать дважды. Окна могут пересекаться по наблюдаемому времени, но их calls
не складываются. Положительные конечные веса нормируются общей `ProfileWeights`
и не умножают частоты. Из одного накопленного снимка «день/ночь» не восстанавливаются.

## Неполнота

| Ситуация | Результат |
| --- | --- |
| Общий ключ, четыре стабильных epoch, неубывающие counters | OBSERVED: Δcalls, частота, Δrows и историческое время |
| Δcalls=Δrows=Δtime=0 | ZERO: частота/приращения 0, среднее время null |
| Нет начальной/конечной записи | UNKNOWN: NEW_ENTRY/MISSING_ENTRY, все приращения и частота null |
| Нет ending epoch, изменился stats_reset либо dealloc на любой границе | Все записи UNKNOWN с конкретной причиной |
| Уменьшился calls/rows/total_exec_time | UNKNOWN/COUNTER_DECREASE |
| calls не вырос, а rows/time изменились | UNKNOWN/INCONSISTENT_COUNTERS |
| Изменились два доступных текста одного ключа | UNKNOWN/QUERY_TEXT_CHANGED, консервативный отказ |
| Недоступен queryid | Отдельные количества unidentified_before/after; строки не связываются по null/тексту |
| Ключ есть, текст недоступен | Частота может быть пригодной; query=null и text_status сохраняются |
| Неполный снимок при стабильных epoch | Общие известные ключи можно рассчитать, охват остаётся partial |

Рост dealloc не позволяет считать сохранившийся ключ непрерывно наблюдаемым: он мог
быть вытеснен и создан заново. Неизвестные counters не заменяются нулём. Отрицательные
или нечисловые counters и численные переполнения дают NUMERIC_ERROR. Дубликаты ключей,
ошибочная принадлежность БД и metadata — INVALID_INPUT.

`complete` означает отсутствие обнаруженных дыр в доступной области, а не гарантию
полноты всех запросов сервера. **Два снимка PG16 не гарантируют обнаружение адресного
reset**, если counters успели догнать прежние значения; stats_reset описывает общий
сброс. Ограничение всегда записывается в manifest. Семантика источника:
[PostgreSQL 16 pg_stat_statements](https://www.postgresql.org/docs/16/pgstatstatements.html).

## Выход для импортёра

Профиль сохраняет source/snapshot IDs, период/границы чтения, веса, observations,
причины неполноты, before_reasons/after_reasons и количества неидентифицируемых строк.
`observed_calls_per_hour` и `observed_total_exec_time_ms` суммируют **только пригодные**
наблюдения: при partial это не полная нагрузка источника. SQL не ранжируется, не
подрезается и не получает придуманные параметры.

`commonUsableKeys` — пересечение пригодных ключей всех окон для общего списка SQL #67.
Остальные observations остаются в manifest. Неизвестный ключ нельзя дополнить частотой
0. Подтверждённые нули входят в пересечение; импортёр отдельно соблюдает ненулевые
профили/использование SQL и лимиты workload v2.

Исторические `total_exec_time_ms = Δtotal_exec_time`,
`mean_exec_time_ms = Δtotal_exec_time / Δcalls` при положительном Δcalls.
Это прошлое время выполнения, **не planner cost и не прогноз latency с индексами**.
Сначала вычитаются counters, затем выполняется преобразование в Double; деление —
DECIMAL128 с проверкой конечности и потери положительного значения. В JSON queryid,
delta_calls и delta_rows — десятичные строки bigint; неизвестные значения — null.
State/text_status/reasons в profiles v1 — имена enum в UPPER_CASE.

[Синтетический пример](../examples/statistics-profiles-v1.json): calls 100→160,
чтения [00:00:00,00:00:02] и [00:01:00,00:01:02], Δseconds=60, частота=3600/час;
время 150→240 ms, среднее 1.5 ms. DSN не сохраняется; SQL может содержать литералы,
поэтому в Git используются только синтетические примеры.

## Проверка

```sh
./gradlew --no-daemon test --tests '*FrequencyCalculatorTest'
bash demo/check-statistics.sh
```

Первый прогон проверяет численные ожидания и создаёт `build/frequency-example.json`.
Второй использует собственный одноразовый PostgreSQL и проверяет прямой стык:
запись существует до первого снимка, между снимками выполняются ровно семь вызовов.
Без переменных подключения DB-тесты пропускаются. Расчёт проверяет общий RunContext,
не открывает search window и не расходует EXPLAIN.
