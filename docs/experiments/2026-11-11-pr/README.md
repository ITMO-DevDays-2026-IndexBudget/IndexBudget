# Проверка ветки PR #21, 11 ноября 2026

Ветка `codex/issue-21-acceptance` создана от `origin/main`
`362de391a7c41f35ae66857c8339e86faa0ad9ef`. Используются принятые в main
`ProfileAwareCandidateGenerator`, repair, HTML и JDBC; временный генератор
из прежнего рабочего дерева сюда не переносился.

`./gradlew --no-daemon build installDist`: **756 тестов, 755 прошли,
0 ошибок/падений, 1 пропущен**. `RealCostsIntegrationTest` требует отдельной
seed-БД и `INDEXBUDGET_METRICS_TEST_URL`; его текущий CI job сохранён.
Каталог, SQL resolution, JDBC/HypoPG и `MvpAcceptanceIntegrationTest` выполнены
на собственной одноразовой БД. Включены 18 проверок oracle и проверка полного
production wiring. Установленные `--help`/`--version` дали exit 0.
Пять Python-проверок физического DDL прошли при переносе harness.

[verification.json](verification.json) содержит фактические счётчики по suites
и SHA-256 файлов реализации/инструментов, проверенных этой сборкой.
Тестовый контейнер остановлен и удалён через `--rm`; общий стенд не использовался.

[Предыдущие planner/oracle и физические измерения](../2026-11-10/README.md)
сохранены с собственным происхождением. Они не выдаются за повторный эксперимент
этой ветки. Для закрытия #21 остаются удалённый CI и указанная в issue командная
сверка; выводов о реальной скорости записи нет.
