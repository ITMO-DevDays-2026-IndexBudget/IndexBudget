# Ограниченные UPDATE/DELETE (#103)

Один workload v2 может содержать SELECT, UPDATE и DELETE в общем списке SQL и общих
профилях. Для DML допустима одна обычная постоянная таблица с алиасом и обязательным
WHERE: те же сравнения, AND/OR, IN, BETWEEN и null-tests, что у SELECT.
UPDATE содержит 1–256 одиночных SET-присваиваний встроенных скалярных литералов,
NULL или поддержанных casts. Имена SET разрешаются через каталог отдельно от WHERE;
повторы, квалифицированные SET-колонки, tuple SET, DEFAULT, функции и выражения запрещены.
Нет INSERT/MERGE, FROM/USING, RETURNING, CTE и подзапросов. Лимиты 64 KiB SQL,
256 единиц сложности WHERE и 1–20 общих запросов остаются прежними.

`ResolvedQuery.statementKind` различает SELECT/UPDATE/DELETE. `modifiedColumns`
не является `projectedColumns`: SET не создаёт ключи или INCLUDE. DML-кандидаты
получаются только из WHERE; SELECT продолжает создавать ограниченный INCLUDE.
Совместимость типов SET использует implicit и assignment casts; assignment-only casts
не расширяют разрешение операторов сравнения. Диапазоны значений/typmods проверяет
исходный EXPLAIN, не самодельный исполнитель SQL. EXPLAIN не гарантирует успешное
исполнение будущего DML, проверку constraints/триггеров или соответствие generic plan.

## Безопасность и роли

JDBC отправляет **исходный DML** как `EXPLAIN (FORMAT JSON) <SQL>` без ANALYZE.
Внутренняя SELECT-shaped область разрешения имён не рендерится и не исполняется
вместо него. Перед смешанной оценкой сессия выставляет серверный
`default_transaction_read_only=on` и проверяет `transaction_read_only=on`.
Неподтверждённая защита прерывает соединение. Автоматический commit для DML не нужен:
исходный statement не исполняется; read-only дополнительно запрещает случайный
UPDATE/DELETE/EXPLAIN ANALYZE постоянной таблицы. Temporary DML targets исключены,
поскольку read-only допускает изменения временных таблиц.

Обычная demo SELECT-only роль сохраняется без изменений. PostgreSQL проверяет права
оригинального statement даже для EXPLAIN. Для DML администратор **отдельно и явно**
настраивает opt-in роль (не владельца, не superuser): CONNECT/USAGE/SELECT только
нужных таблиц/колонок, UPDATE выбранных SET-колонок и/или DELETE целевых таблиц,
доступ к установленному HypoPG. Рекомендуется `default_transaction_read_only=on`
также на роли. Runtime не создаёт роль и не выдаёт GRANT; 42501 возвращается как
EXPLAIN_FAILED с query ID и советом настроить отдельную роль.

Read-only — защита конкретной сессии, **не** отзыв выданных DML-прав: владелец
учётных данных может открыть другое соединение и отключить настройку. Используйте
доверенную demo/test БД с доверенными типами/casts/операторами, без вредоносных
planner hooks и выражений: планирование может вычислять immutable expressions.
Не выдавайте привилегированную роль недоверенному пользователю и не считайте
EXPLAIN песочницей для произвольного SQL.

HypoPG reset, rollback при ошибке и abort при отмене/deadline остаются общими.
Неполный вектор не обновляет результат; baseline, cache, guards, бюджеты, R/M и
partial используют общий список SELECT/DML. Реальные индексы не создаются.

## Интерпретация и импорт

ModifyTable cost отражает поиск/подготовку строк, а не стоимость фактической записи,
WAL, блокировок, триггеров или сопровождения индексов. Поэтому снижение cost не
обещает ускорения UPDATE/DELETE. В JSON v3 у SQL добавлены `statement_kind` и
`cost_interpretation` (`planner_cost` / `row_search_and_preparation`); при отсутствии
AST-метаданных, например ранней ошибке, они null. HTML явно поясняет границы DML.
Число вызовов не равно числу изменённых строк: `table_write_rates` задаётся отдельно.

Импорт pg_stat_statements допускает restricted DML templates: $n в SET/WHERE
подставляются только из явного literal-примера, с проверкой повторных параметров,
всего AST и разрешения колонок. Это представительский, не generic plan. Форма
запроса, SET-цели и statement kind не меняются; причины исключения записываются
в манифест. Формат workload остаётся v2. Пример: [смешанный workload](../examples/workload-v2-dml.json).

Проверки с реальной БД используют только одноразовый PostgreSQL 16 + HypoPG 1.4.2:
`bash demo/check-hypopg.sh`. Тесты контролируют исходные EXPLAIN, полный вектор,
неизменность строк, серверный запрет записи, SELECT-only права, ошибки и cleanup.
Без Docker их запуск не считается выполненным; unit/mocks не доказывают эти свойства.

Основания: [EXPLAIN](https://www.postgresql.org/docs/16/sql-explain.html),
[ModifyTable cost](https://www.postgresql.org/docs/16/using-explain.html),
[read-only transaction](https://www.postgresql.org/docs/16/sql-set-transaction.html).
