# Приёмка MVP v0.2 — issue #21

Источник поведения — [спецификация v0.2](../IndexBudget-spec-v0.2.md), объём —
[#21](https://github.com/ITMO-DevDays-2026-IndexBudget/IndexBudget/issues/21).
Эта карта фиксирует выполненные проверки и оставшиеся условия; она не заменяет
командную приёмку и не закрывает зависимости #3–#20/#22 автоматически.

## Реализация и воспроизведение

Production CLI связывает SQL resolution, каталог, `ProfileAwareCandidateGenerator`,
JDBC/HypoPG, общие метрики/comparator, greedy + fixed-S0 repair и экспорт.
Shortlist применяет пределы 16 колонок → 32 локальных → 16 глобальных до размеров.
Профили участвуют в round-robin по нормированным внутри профиля частотам;
одиночные и пары чередуются, веса профилей не меняют распределение мест.

```sh
./gradlew --no-daemon build installDist
build/install/indexbudget/bin/indexbudget --help
build/install/indexbudget/bin/indexbudget --version
bash demo/check-hypopg.sh
```

Последняя команда создаёт собственную одноразовую PostgreSQL 16.15/HypoPG 1.4.2,
выполняет JDBC и acceptance integration tests, включая согласованность HTML/JSON/SQL,
и удаляет свои ресурсы.
Каталог/SQL integration tests также запускаются в существующем CI job с
`INDEXBUDGET_CATALOG_TEST_URL`/`INDEXBUDGET_CATALOG_TEST_USER`.
Обычный `test` без соответствующих env пропускает тесты БД явно.
`RealCostsIntegrationTest` из актуального `main` использует
`INDEXBUDGET_METRICS_TEST_*`; отдельный runner — `bash demo/check-metrics.sh`.

Таблица ниже сопоставляет критерии с кодом проверок текущей ветки. Она сама по
себе не сообщает о прохождении нового прогона; результаты и происхождение
исторического прогона указаны отдельно.

| Критерий §10 / #21 | Проверка |
| --- | --- |
| JSON v2, веса, write profile, численные ошибки, zero baseline | `WorkloadReaderTest`, `ResolvedWorkloadValidatorTest`, domain tests |
| Поддержанный/отклонённый SQL, физические имена и настоящий каталог | SQL tests, `SqlResolutionIntegrationTest`, `JdbcCatalogReaderIntegrationTest` |
| 100 колонок, проекция, неизменность при добавлении 98 неиспользуемых | `MvpAcceptanceIntegrationTest`, `ProfileAwareCandidateGeneratorTest` |
| Все 100 условий доходят до настоящего EXPLAIN | `MvpAcceptanceIntegrationTest`: сохранённый план содержит каждую колонку |
| Caps 16/32/16, равные рейтинги → 8 одиночных + 8 пар, редкий профиль | `ProfileAwareCandidateGeneratorTest`, `MvpAcceptanceIntegrationTest`; исключённые колонки/кандидаты и фактические счётчики проверяются |
| Взаимодействия, plateau, 0→2/1→1/1→2, кэш, counters и partial | `GreedyOptimizerTest`, `GreedyOptimizerLimitsTest`, `RepairNeighborhoodTest`, `LimitedRepairOptimizerTest`, `LimitedRepairPipelineTest` |
| Изоляция/точный набор/реальные индексы/rollback/timeout/cancel/повтор | `JdbcAnalysisSessionIntegrationTest` и fault-injection tests |
| Отдельное влияние ненулевых storage/write budgets; две таблицы | `MvpAcceptanceIntegrationTest`, planner experiments |
| HTML/JSON/SQL, hash/run ID, escaping, секреты, ошибка экспорта | report/CLI tests, `MvpAcceptanceIntegrationTest` |
| Все production-порты подключены без открытия БД при старте | `ProductionWiringTest`, запуск установленного CLI |
| Малый независимый полный перебор и собственный deadline | `ExhaustiveOracleTest`, отдельный `exhaustiveOracle` |

## Проверочные инструменты

[Инструкция экспериментов](../demo/experiments/README.md) содержит фиксированные
входы/holdout, отдельную БД с tmpfs и команды:

```sh
./gradlew plannerExperiments --args='--workload demo/experiments/workload.json --sensitivity demo/experiments/sensitivity.json --output-dir reports/issue-21'
./gradlew exhaustiveOracle --args='--workload demo/experiments/workload.json --output reports/issue-21/oracle.json --deadline-seconds 120'
python3 -m unittest discover -s demo/experiments -p 'test_*.py'
```

Oracle работает только в test runtime, принимает ≤8 кандидатов и ≤5 индексов,
оценивает наборы целиком общим evaluator и проверяет бюджеты/метрики общей domain
реализацией. Границу 219 наборов / 657 EXPLAIN при трёх SQL проверяет
`ExhaustiveOracleTest` на независимо заданном scripted evaluator. Без полного обхода сертификат оптимума отсутствует.
Его deadline обязателен и включает подготовку; production 90/60 секунд не наследуются.

Физический benchmark запускается отдельной явной командой на БД с закрытым маркером.
Он использует два раунда в прямом/обратном порядке, два прогрева и семь измерений
на SQL в каждом раунде, сохраняет raw EXPLAIN ANALYZE/BUFFERS и размеры, затем
откатывает реальные индексы. Обычный `analyze` таких действий не выполняет.

## Исторический прогон 10–11 ноября 2026

Результаты и точные ограничения прогона находятся в
[docs/experiments/2026-11-10](experiments/2026-11-10/README.md).
БД для интеграции и БД для экспериментов — отдельные одноразовые контейнеры;
общий стенд не использовался. Приведённые там 726 JVM-тестов, planner/oracle и
физические измерения выполнены на прежнем рабочем дереве с незакоммиченными
изменениями. Они сохранены как свидетельство того прогона и не подтверждают
прохождение проверок текущего PR.

Ветка PR создана от актуального `origin/main` (`362de391a7c41f35ae66857c8339e86faa0ad9ef`),
в котором уже есть `ProfileAwareCandidateGenerator`, repair, HTML и JDBC. PR
использует эти реализации; прежний `ScenarioCandidateGenerator` и копии HTML/JDBC
в него не переносятся. Повторная сборка и интеграции этой ветки: [755 пройденных тестов, 1 пропущен](experiments/2026-11-11-pr/README.md).

## Что остаётся для закрытия issue

- Чистый checkout итогового коммита и удалённый CI. Исторический локальный прогон
  из evidence не заменяет проверку ветки PR от актуального `main`; результаты
  новой проверки нужно зафиксировать отдельно.
- Проверить закрытие полных критериев #3–#20 и PoC #22; этот документ не изменяет
  состояния issues и не заменяет проверку владельцами.
- Получить указанную в #21 совместную сверку реальных чисел и стыков всеми пятью
  участниками. Автоматические тесты не являются такой сверкой.
- Write benchmark с восстановлением свежего snapshot нужен для выводов о реальном
  влиянии на запись. В историческом прогоне измерены чтение/размеры; write units остаются
  моделью. Опциональный rollback microbenchmark не восстанавливает WAL/кеши/мёртвые
  версии, поэтому сильных выводов о скорости записи не поддерживает.

Совпадение robust/mean и отсутствие улучшения repair допустимы как наблюдаемый
результат и указываются прямо. Planner cost, время, фактический размер и write proxy
не подменяют друг друга; SLA, глобальный оптимум и универсальное ускорение не заявлены.
