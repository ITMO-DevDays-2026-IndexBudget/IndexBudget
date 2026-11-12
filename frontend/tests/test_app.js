'use strict';

// Поведение браузерного JS без БД и npm-зависимостей. Полный UI проверяется в браузере.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../static/app.js'), 'utf8');
const templates = fs.readdirSync(path.join(__dirname, '../templates')).sort()
  .map(file => JSON.parse(fs.readFileSync(path.join(__dirname, '../templates', file), 'utf8')));
const copy = value => JSON.parse(JSON.stringify(value));
const scenarios = JSON.parse(fs.readFileSync(path.join(__dirname, '../../demo/scenarios/workload.json'), 'utf8'));
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, '../../demo/scenarios/catalog.json'), 'utf8'));
const initialIds = ['records_by_group', 'records_by_value', 'category_join'];
const scenarioTemplate = {...copy(templates[0]), id: '04-scenarios', name: 'Чтение и запись', workload: copy(scenarios),
  examples: catalog.examples.map(item => {
    const query = scenarios.queries.find(query => query.id === item.query_id);
    return {...query, name: item.name, purpose: item.purpose, syntax_hint: item.syntax_hint,
      statement_kind: query.sql.split(' ')[0].toLowerCase(),
      calls_per_hour: Object.fromEntries(scenarios.profiles.map(profile => [profile.id, profile.calls_per_hour[query.id]])),
      cost_guard: scenarios.constraints.query_cost_guards?.[query.id],
      tables: query.id === 'category_join' ? ['public.records', 'public.groups'] : ['public.records']};
  })};
scenarioTemplate.workload.index_methods = ['btree', 'brin', 'hash'];
scenarioTemplate.workload.queries = scenarioTemplate.workload.queries.filter(query => initialIds.includes(query.id));
scenarioTemplate.workload.profiles.forEach(profile => {
  profile.calls_per_hour = Object.fromEntries(Object.entries(profile.calls_per_hour).filter(([id]) => initialIds.includes(id)));
});
templates.push(scenarioTemplate);

function app() {
  const elements = new Map();
  const listeners = new Map();
  const stored = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {value: '', dataset: {},
      querySelectorAll: () => [], scrollIntoView() {}, checkValidity: () => true,
      classList: {toggle() {}}, querySelector: () => ({insertAdjacentHTML() {}})});
    return elements.get(id);
  };
  const context = vm.createContext({console, TextEncoder, Blob, URL, setTimeout, clearTimeout,
    document: {getElementById: element, querySelectorAll: () => [], querySelector: () => null,
      addEventListener: (type, listener) => {
        if (!listeners.has(type)) listeners.set(type, []);
        listeners.get(type).push(listener);
      }},
    localStorage: {setItem: (key, value) => stored.set(key, value)},
    testTemplates: copy(templates)});
  // Не запускаем браузерный bootstrap: все проверяемые функции и обработчики остаются настоящими.
  vm.runInContext(source.slice(0, source.indexOf('const closeHelp = installHelp();')), context);
  vm.runInContext('templates = testTemplates; state = templateState(templates[0]);', context);
  return {context, elements, stored, listeners, element,
    run: code => vm.runInContext(code, context),
    set: (key, value) => { context[key] = value; }};
}

function check(workload) {
  const ui = app();
  ui.set('testWorkload', workload);
  return () => ui.run('validateWorkload(testWorkload)');
}

test('все шаблоны и обе границы частоты допустимы', () => {
  for (const template of templates) assert.doesNotThrow(check(copy(template.workload)));
  for (const value of [0, 1000000, 1000000.0]) {
    const workload = copy(templates[0].workload);
    workload.profiles[0].calls_per_hour[workload.queries[0].id] = value;
    assert.doesNotThrow(check(workload));
  }
});

test('все методы передаются из каждого шаблона в одиночный анализ и серию', () => {
  const ui = app();
  for (const template of templates) {
    ui.set('template', template);
    ui.run('state = templateState(template)');
    for (const mode of ['single', 'series']) {
      ui.element('launch-mode').value = mode;
      ui.element('series-budget').value = 'storage_budget_bytes';
      ui.element('series-values').value = '0, 65536, 67108864';
      ui.element('series-timeout').value = '600';
      assert.deepEqual(Array.from(ui.run('requestSnapshot().workload.index_methods')), ['btree', 'brin', 'hash']);
    }
  }
});

test('черновик без методов получает все методы без потери SQL; явный список сохраняется', () => {
  const ui = app();
  const workload = copy(templates[0].workload);
  delete workload.index_methods;
  workload.queries[0].sql = ' SELECT id FROM public.records WHERE group_id = 7;\n';
  const draft = {state: {template_id: templates[0].id, template_version: templates[0].version, workload}};
  ui.set('draft', draft);
  ui.run('state = restoreDraft(draft, templates).state; persist();');
  assert.deepEqual(Array.from(ui.run('requestSnapshot().workload.index_methods')), ['btree', 'brin', 'hash']);
  assert.equal(ui.run('requestSnapshot().workload.queries[0].sql'), workload.queries[0].sql);
  assert.deepEqual(JSON.parse(ui.stored.values().next().value).state.workload.index_methods, ['btree', 'brin', 'hash']);
  workload.index_methods = ['hash'];
  ui.set('draft', draft);
  assert.deepEqual(Array.from(ui.run('restoreDraft(draft, templates).state.workload.index_methods')), ['hash']);
  assert.deepEqual(templates[0].workload.index_methods, ['btree', 'brin', 'hash']);
});

test('частоты не округляются: дробь, минус, избыток, пустое и нечисловое значение отклоняются', () => {
  for (const value of [-1, .5, 1000001, null, '', '2', false, NaN, Infinity]) {
    const workload = copy(templates[0].workload);
    workload.profiles[0].calls_per_hour[workload.queries[0].id] = value;
    assert.throws(check(workload), /целым числом от 0 до 1 000 000/);
  }
});

test('матрица частот полностью покрывает SQL, профили и использование запросов', () => {
  for (const mutation of ['missing', 'extra', 'empty_profile', 'unused_query', 'no_queries', 'no_profiles', 'many_queries', 'many_profiles']) {
    const workload = copy(templates[0].workload);
    const query = workload.queries[0].id;
    if (mutation === 'missing') delete workload.profiles[0].calls_per_hour[query];
    if (mutation === 'extra') workload.profiles[0].calls_per_hour.unexpected = 1;
    if (mutation === 'empty_profile') Object.keys(workload.profiles[0].calls_per_hour).forEach(id => workload.profiles[0].calls_per_hour[id] = 0);
    if (mutation === 'unused_query') workload.profiles.forEach(profile => profile.calls_per_hour[query] = 0);
    if (mutation === 'no_queries') workload.queries = [];
    if (mutation === 'no_profiles') workload.profiles = [];
    if (mutation === 'many_queries') workload.queries = Array.from({length: 21}, (_, i) => ({id: String(i), sql: 'SELECT id FROM public.records'}));
    if (mutation === 'many_profiles') workload.profiles = Array(4).fill(workload.profiles[0]);
    assert.throws(check(workload), /запрос|профил|частот/i, mutation);
  }
});

test('частоты изменения строк и веса сохраняют дробные значения', () => {
  const workload = copy(templates[0].workload);
  workload.profiles[0].weight = .75;
  workload.table_write_rates = {'public.records': {inserts_per_hour: .125, updates_per_hour: 2000000.5, deletes_per_hour: .25, source: 'manual'}};
  assert.doesNotThrow(check(workload));
});

test('проверка запроса действует и вне вкладки Нагрузка, SQL передаётся побайтно', () => {
  const ui = app();
  const sql = '\nSELECT "id" FROM public.records WHERE group_id = 1;  \n';
  ui.set('testSql', sql);
  const request = ui.run('state.workload.queries[0].sql = testSql; requestSnapshot()');
  assert.equal(request.workload.queries[0].sql, sql);
  assert.deepEqual(Object.keys(request).sort(), ['template_id', 'template_version', 'workload']);
  ui.run('state.schema_sql = "DROP SCHEMA public CASCADE"; state.seed_sql = "SELECT 1";');
  assert.deepEqual(Object.keys(ui.run('requestSnapshot()')).sort(), ['template_id', 'template_version', 'workload']);
  ui.run('state.workload.profiles[0].calls_per_hour[state.workload.queries[0].id] = 0.1; tab = "queries";');
  assert.throws(() => ui.run('requestSnapshot()'), /целым числом/);
});

test('пустое поле частоты остаётся ошибкой после смены вкладки и сериализации черновика', () => {
  const ui = app();
  const input = {dataset: {frequencyProfile: '0', frequencyQuery: '0'}, value: '', valueAsNumber: NaN};
  ui.run('changed = () => {}; updateProfiles = () => {};');
  ui.listeners.get('input')[0]({target: input});
  assert.equal(ui.run('Number.isNaN(state.workload.profiles[0].calls_per_hour[state.workload.queries[0].id])'), true);
  assert.throws(() => ui.run('requestSnapshot()'), /целым числом/);
  ui.run('persist()');
  const saved = JSON.parse(ui.stored.values().next().value);
  assert.equal(saved.state.workload.profiles[0].calls_per_hour[templates[0].workload.queries[0].id], null);
  ui.set('draft', saved);
  ui.run('state = restoreDraft(draft, templates).state;');
  assert.throws(() => ui.run('requestSnapshot()'), /целым числом/);
});

test('старый черновик сохраняет SQL, но не DDL/seed/поддельную схему', () => {
  const ui = app();
  const original = templates[0];
  const workload = copy(original.workload);
  workload.queries[0].sql = ' SELECT id FROM public.records WHERE group_id = 42\n';
  ui.set('draft', {state: {template_id: original.id, template_version: original.version, workload,
    schema_sql: 'DROP SCHEMA public CASCADE', seed_sql: 'SELECT dangerous()',
    catalog: {tables: [{name: 'fake'}]}, catalogSource: 'database', catalogSchema: 'fake', catalogSeed: 'fake'},
    guardsText: '{"records_recent": 1.1}', manualText: ''});
  const restored = ui.run('restoreDraft(draft, templates)');
  assert.equal(restored.state.workload.queries[0].sql, workload.queries[0].sql);
  assert.deepEqual(JSON.parse(JSON.stringify(restored.state.catalog)), original.catalog);
  assert.equal(restored.state.catalogSource, 'template');
  for (const key of ['schema_sql', 'seed_sql', 'catalogSchema', 'catalogSeed']) assert.equal(Object.hasOwn(restored.state, key), false);
  ui.run('state = restoreDraft(draft, templates).state; persist();');
  const persisted = JSON.parse(ui.stored.values().next().value);
  assert.deepEqual(Object.keys(persisted.state).sort(), ['template_id', 'template_version', 'workload']);
  ui.run('draft.state.template_version = "stale"');
  assert.equal(ui.run('restoreDraft(draft, templates)'), null);
});

test('сброс шаблона возвращает исходный workload и доверенную схему', () => {
  const ui = app();
  ui.run('renderAll = () => {}; state.workload.queries[0].sql = "broken"; state.catalog = {tables: []}; guardsText = "broken"; manualText = "broken";');
  ui.listeners.get('click')[0]({target: {closest: () => ({id: 'reset', dataset: {}, disabled: false})}});
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(state.workload)')), templates[0].workload);
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(state.catalog)')), templates[0].catalog);
  assert.equal(ui.run('guardsText + manualText'), '');
});

test('просмотр схемы не создаёт редакторы DDL/seed, вывод экранирован', () => {
  const ui = app();
  ui.run('templates[0].schema_sql += "\\n<script>alert(1)</script>"; renderSchema();');
  const markup = ui.element('editor-content').innerHTML;
  assert.doesNotMatch(markup, /<textarea|id="(?:schema-sql|seed-sql)"/);
  assert.match(markup, /&lt;script&gt;/);
  assert.match(markup, /только просмотр/);
  assert.doesNotMatch(markup, /preview-schema|Проверить схему/);
});

function scenarioApp() {
  const ui = app();
  ui.run('renderAll = () => {}; renderEditor = () => {}; renderSummary = () => {}; loadTemplate("04-scenarios");');
  return ui;
}

test('шаблон сохраняет guard, ручной набор и исходные частоты в точном workload', () => {
  const ui = scenarioApp();
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(materialize())')), scenarioTemplate.workload);
});

test('все девять примеров добавляются с исходным SQL, ID и частотами без замены настроек', () => {
  const ui = scenarioApp();
  ui.run('state.workload.constraints.storage_budget_bytes = 123; state.workload.profiles[0].calls_per_hour.records_by_group = 987;');
  for (const example of scenarioTemplate.examples) {
    ui.set('exampleId', example.id);
    ui.run('addExample(exampleId)');
  }
  const workload = JSON.parse(ui.run('JSON.stringify(materialize())'));
  assert.equal(workload.queries.length, 9);
  for (const query of scenarios.queries) assert.deepEqual(workload.queries.find(item => item.id === query.id), query);
  assert.equal(workload.constraints.storage_budget_bytes, 123);
  assert.equal(workload.profiles[0].calls_per_hour.records_by_group, 987);
  assert.equal(workload.profiles[1].calls_per_hour.delete_recent, 20);
  assert.deepEqual(workload.table_write_rates, scenarios.table_write_rates);
  assert.deepEqual(workload.manual_indexes, scenarios.manual_indexes);
  assert.doesNotThrow(() => ui.run('validateWorkload(materialize())'));
});

test('добавление сохраняет отредактированный SQL с тем же ID, повтор не дублирует пример', () => {
  const ui = scenarioApp();
  ui.run('state.workload.queries[0].sql = "SELECT id FROM public.records WHERE id = 8";');
  const result = ui.run('addExample("records_by_group")');
  assert.equal(result.id, 'records_by_group_2');
  assert.equal(result.renamed, true);
  assert.equal(ui.run('state.workload.queries[0].sql'), 'SELECT id FROM public.records WHERE id = 8');
  assert.equal(ui.run('JSON.parse(guardsText).records_by_group_2'), 1.05);
  assert.equal(ui.run('addExample("records_by_group").alreadyAdded'), true);
  assert.equal(ui.run('state.workload.queries.length'), 4);
});

test('новые профили получают явно обозначенную частоту обычного дня без изменения старых', () => {
  const ui = scenarioApp();
  ui.run('state.workload.profiles.push({id:"custom", weight:1, calls_per_hour:Object.fromEntries(state.workload.queries.map(q=>[q.id,1]))});');
  assert.equal(ui.run('addExample("update_payload").fallback'), true);
  assert.equal(ui.run('state.workload.profiles[2].calls_per_hour.update_payload'), 30);
  assert.equal(ui.run('state.workload.profiles[1].calls_per_hour.update_payload'), 120);
  assert.doesNotThrow(() => ui.run('validateWorkload(materialize())'));
});

test('неподходящий датасет, лимит 20 и сломанный guard JSON не изменяют нагрузку', () => {
  const legacy = app();
  assert.throws(() => legacy.run('addExample("update_payload")'), /датасете/);
  const ui = scenarioApp();
  ui.run('guardsText = "broken";');
  const before = ui.run('JSON.stringify(state.workload)');
  assert.throws(() => ui.run('addExample("update_payload")'), /исправьте JSON/);
  assert.equal(ui.run('JSON.stringify(state.workload)'), before);
  ui.run('guardsText = ""; while (state.workload.queries.length < 20) state.workload.queries.push({id:"custom_"+state.workload.queries.length,sql:"SELECT id FROM public.records"});');
  assert.throws(() => ui.run('addExample("delete_recent")'), /20 запросов/);
  assert.equal(ui.run('state.workload.queries.length'), 20);
});

test('удаление и переименование запроса согласуют guard и каждый профиль', () => {
  const ui = scenarioApp();
  ui.run('queryIndex = 0;');
  ui.listeners.get('change')[0]({target: {id: 'query-id', value: 'renamed_group', dataset: {}}});
  let workload = JSON.parse(ui.run('JSON.stringify(materialize())'));
  assert.equal(workload.constraints.query_cost_guards.renamed_group, 1.05);
  assert.equal(Object.hasOwn(workload.constraints.query_cost_guards, 'records_by_group'), false);
  for (const profile of workload.profiles) assert.equal(Object.hasOwn(profile.calls_per_hour, 'records_by_group'), false);
  ui.listeners.get('click')[0]({target: {closest: () => ({id:'delete-query',dataset:{},disabled:false})}});
  workload = JSON.parse(ui.run('JSON.stringify(materialize())'));
  assert.equal(workload.constraints.query_cost_guards, undefined);
  for (const profile of workload.profiles) assert.equal(Object.hasOwn(profile.calls_per_hour, 'renamed_group'), false);
  assert.equal(ui.run('addExample("records_by_group").id'), 'records_by_group');
  assert.equal(ui.run('materialize().constraints.query_cost_guards.records_by_group'), 1.05);
});

test('удаление JOIN фильтрует rates только в экспорте, повторное добавление возвращает точные значения', () => {
  const ui = scenarioApp();
  ui.run('queryIndex = state.workload.queries.findIndex(q=>q.id==="category_join");');
  ui.listeners.get('click')[0]({target: {closest: () => ({id:'delete-query',dataset:{},disabled:false})}});
  assert.deepEqual(Object.keys(ui.run('materialize().table_write_rates')), ['public.records']);
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(state.workload.table_write_rates)')), scenarios.table_write_rates);
  ui.run('addExample("category_join")');
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(materialize().table_write_rates)')), scenarios.table_write_rates);
  ui.run('state.workload.queries = [{id:"custom",sql:"SELECT id FROM public.records"}];');
  assert.deepEqual(JSON.parse(ui.run('JSON.stringify(materialize().table_write_rates)')), scenarios.table_write_rates);
});

test('библиотека экранирует названия и SQL, показывает смысл, частоты и действия', () => {
  const ui = scenarioApp();
  ui.run('templates.find(t=>t.id==="04-scenarios").examples[0].name = "<img src=x>"; renderExamples();');
  const markup = ui.element('editor-content').innerHTML;
  assert.match(markup, /&lt;img src=x&gt;/);
  assert.doesNotMatch(markup, /<img src=x>/);
  assert.match(markup, /Добавить в нагрузку/);
  assert.match(markup, /Уже в нагрузке/);
  assert.match(markup, /частота обычного дня/);
  assert.match(markup, /SQL и условия/);
  ui.run('state = templateState(templates[0]); renderExamples();');
  assert.match(ui.element('editor-content').innerHTML, /Открыть «Чтение и запись»/);
});

test('новый датасет виден первым, но рендер не переключает прежний черновик', () => {
  const ui = app();
  ui.run('renderTemplates()');
  const markup = ui.element('templates').innerHTML;
  assert.ok(markup.indexOf('data-template="04-scenarios"') < markup.indexOf('data-template="01-records"'));
  assert.equal(ui.run('state.template_id'), '01-records');
});

test('переключение датасета требует явного подтверждения потери изменённой нагрузки', () => {
  const ui = scenarioApp();
  ui.run('state.workload.queries[0].sql = "SELECT id FROM public.records WHERE id=99";');
  const before = ui.run('JSON.stringify(state.workload)');
  let confirmations = 0;
  ui.set('confirm', () => { confirmations++; return false; });
  ui.run('switchTemplate("01-records")');
  assert.equal(confirmations, 1);
  assert.equal(ui.run('state.template_id'), '04-scenarios');
  assert.equal(ui.run('JSON.stringify(state.workload)'), before);
  ui.set('confirm', () => true);
  ui.run('switchTemplate("01-records")');
  assert.equal(ui.run('state.template_id'), '01-records');
});

test('серия сохраняет workload и передаёт 3–5 исходных бюджетов в CLI-контракте', () => {
  const ui = app();
  ui.element('launch-mode').value = 'series';
  ui.element('series-budget').value = 'storage_budget_bytes';
  ui.element('series-values').value = '0, 16777216, 67108864';
  ui.element('series-timeout').value = '600';
  const original = ui.run('JSON.stringify(state.workload)');
  const request = ui.run('requestSnapshot()');
  assert.deepEqual(Array.from(request.series.values), [0,16777216,67108864]);
  assert.equal(request.series.budget, 'storage_budget_bytes');
  assert.equal(ui.run('JSON.stringify(state.workload)'), original);
  ui.run('persist()');
  assert.equal(JSON.parse(ui.stored.values().next().value).seriesUI['launch-mode'], 'series');
});

test('повторы, неверное число и отрицательные бюджеты серии отклоняются', () => {
  const ui = app();
  ui.element('launch-mode').value = 'series';
  ui.element('series-budget').value = 'storage_budget_bytes';
  ui.element('series-timeout').value = '600';
  for (const input of ['1,2', '1,1,2', '0,-1,2', '0,,2', '0,1,2,3,4,5', '0,1.5,2']) {
    ui.element('series-values').value = input;
    assert.throws(() => ui.run('seriesRequest()'));
  }
});

test('положительный и отрицательный underflow серии отвергаются до отправки', () => {
  const ui = app();
  ui.element('launch-mode').value = 'series';
  ui.element('series-budget').value = 'write_budget_units_per_hour';
  ui.element('series-timeout').value = '600';
  for (const text of ['1e-400,1,2', '-1e-400,1,2']) {
    ui.element('series-values').value = text;
    assert.throws(() => ui.run('seriesRequest()'));
  }
});

test('init восстанавливает серию до первого persist и следующая вкладка читает тот же выбор', async () => {
  const ui = app();
  const draft = {state: {template_id: templates[0].id, template_version: templates[0].version, workload: copy(templates[0].workload)},
    guardsText: '', manualText: '', seriesUI: {'launch-mode':'series','series-budget':'write_budget_units_per_hour','series-values':'0, 25, 100','series-timeout':'120'}};
  ui.set('initialDraft', draft);
  ui.run(`localStorage.getItem = () => JSON.stringify(initialDraft);
    api = async p => p === '/api/templates' ? testTemplates : {database:true,cli:true,local_only:true};
    renderAll = () => {}; tour = {maybeStart(){}}; location = {search:''};
    document.querySelector = () => ({});`);
  ui.set('URLSearchParams', URLSearchParams);
  await ui.run('init()');
  const saved = JSON.parse(ui.stored.values().next().value);
  assert.deepEqual(saved.seriesUI, draft.seriesUI);
  const second = app();
  second.set('initialDraft', saved);
  second.set('URLSearchParams', URLSearchParams);
  second.run(`localStorage.getItem = () => JSON.stringify(initialDraft);
    api = async p => p === '/api/templates' ? testTemplates : {database:true,cli:true,local_only:true};
    renderAll = () => {}; tour = {maybeStart(){}}; location = {search:''}; document.querySelector = () => ({});`);
  await second.run('init()');
  assert.deepEqual(JSON.parse(second.stored.values().next().value).seriesUI, draft.seriesUI);
});

function observedWorkload() {
  return {schema_version: 2, table_write_rates: {'public.records': {
    inserts_per_hour: .000125, updates_per_hour: 2000000.5, deletes_per_hour: 0, source: 'observed'}},
    table_write_rates_origin: {kind: 'observed', window: {from: '2026-11-12T10:00:00Z', to: '2026-11-12T12:00:00Z', period_seconds: 7200},
      complete: true, reasons: [], source: {source_id: '<script>source</script>', database_oid: 16384, postgres_version: '16.15'}}};
}

test('импорт наблюдаемой записи сохраняет дроби, окно и SQL-частоты без пересчёта', () => {
  const ui = app(), observed = observedWorkload();
  ui.set('observed', observed);
  const profiles = ui.run('JSON.stringify(state.workload.profiles)');
  const queries = ui.run('JSON.stringify(state.workload.queries)');
  ui.run('importWriteProfile(observed); renderRates(); persist()');
  const actual = ui.run('requestSnapshot()');
  assert.deepEqual(JSON.parse(JSON.stringify(actual.workload.table_write_rates)), observed.table_write_rates);
  assert.deepEqual(JSON.parse(JSON.stringify(actual.workload.table_write_rates_origin)), observed.table_write_rates_origin);
  assert.equal(ui.run('JSON.stringify(state.workload.profiles)'), profiles);
  assert.equal(ui.run('JSON.stringify(state.workload.queries)'), queries);
  assert.equal(actual.write_source, 'observed');
  assert.match(ui.element('write-rates').innerHTML, /0.000125/);
  assert.doesNotMatch(ui.element('write-rates').innerHTML, /data-rate=/);
  assert.match(ui.element('write-origin').innerHTML, /&lt;script&gt;/);
  const draft = JSON.parse(ui.stored.values().next().value);
  ui.set('draft', draft);
  assert.equal(ui.run('state = restoreDraft(draft, templates).state; writeSource()'), 'observed');
});

test('неполное окно можно изучить, но нельзя использовать с лимитом или write-серией', () => {
  const ui = app(), observed = observedWorkload();
  observed.table_write_rates_origin.complete = false;
  observed.table_write_rates_origin.reasons = ['INCOMPLETE_SNAPSHOT'];
  ui.set('observed', observed);
  ui.run('importWriteProfile(observed); state.workload.constraints.write_budget_units_per_hour = 0;');
  assert.throws(() => ui.run('requestSnapshot()'), /Наблюдение неполное/);
  ui.run('state.workload.constraints.write_budget_units_per_hour = null;');
  assert.doesNotThrow(() => ui.run('requestSnapshot()'));
  ui.element('launch-mode').value = 'series';
  ui.element('series-budget').value = 'write_budget_units_per_hour';
  ui.element('series-values').value = '0, 1, 2';
  ui.element('series-timeout').value = '600';
  assert.throws(() => ui.run('requestSnapshot()'), /Наблюдение неполное/);
});

test('смена источника не превращает observed в manual и сохраняет незаполненный выбор', () => {
  const ui = app();
  ui.run('changed = () => {};');
  ui.listeners.get('input')[0]({target: {id: 'write-source', value: 'observed', dataset: {}}});
  assert.equal(ui.run('state.workload.table_write_rates'), undefined);
  assert.throws(() => ui.run('requestSnapshot()'), /Профиль записи не задан/);
  ui.run('persist()');
  ui.set('draft', JSON.parse(ui.stored.values().next().value));
  assert.equal(ui.run('state = restoreDraft(draft, templates).state; writeSource()'), 'observed');
  ui.listeners.get('input')[0]({target: {id: 'write-source', value: 'manual', dataset: {}}});
  assert.equal(ui.run('state.workload.table_write_rates_origin'), undefined);
});

test('добавление примера и удаление JOIN сохраняют весь observed snapshot и выбор write-серии', () => {
  const ui = scenarioApp(), observed = observedWorkload();
  observed.table_write_rates['public.groups'] = {inserts_per_hour: .5, updates_per_hour: .25, deletes_per_hour: 0, source: 'observed'};
  ui.set('observed', observed);
  ui.run('importWriteProfile(observed); queryIndex = state.workload.queries.findIndex(q=>q.id==="category_join");');
  ui.element('launch-mode').value = 'series';
  ui.element('series-budget').value = 'write_budget_units_per_hour';
  ui.element('series-values').value = '0, 10000, 60000';
  ui.element('series-timeout').value = '120';
  ui.listeners.get('click')[0]({target: {closest: () => ({id:'delete-query',dataset:{},disabled:false})}});
  ui.run('addExample("update_payload"); persist();');
  const request = JSON.parse(ui.run('JSON.stringify(requestSnapshot())'));
  assert.equal(request.write_source, 'observed');
  assert.deepEqual(request.workload.table_write_rates, observed.table_write_rates);
  assert.deepEqual(request.workload.table_write_rates_origin, observed.table_write_rates_origin);
  assert.deepEqual(request.series, {schema_version: 1, budget: 'write_budget_units_per_hour', values: [0, 10000, 60000], timeout_seconds: 120});
  assert.equal(ui.run('writeSource()'), 'observed');
  const saved = JSON.parse(ui.stored.values().next().value);
  assert.equal(saved.writeSource, 'observed');
  assert.deepEqual(saved.state.workload.table_write_rates_origin, observed.table_write_rates_origin);
  assert.equal(saved.seriesUI['launch-mode'], 'series');
  ui.run('renderJson()');
  assert.match(ui.element('editor-content').innerHTML, /Наблюдаемый профиль и окно передаются без изменения/);
  assert.doesNotMatch(ui.element('editor-content').innerHTML, /В JSON включаются ручные частоты/);
});

test('добавление примера после очистки observed не подставляет ручные rates или окно', () => {
  const ui = scenarioApp();
  ui.set('observed', observedWorkload());
  ui.run('importWriteProfile(observed); changed = () => {};');
  ui.listeners.get('input')[0]({target: {id:'write-source',value:'manual',dataset:{}}});
  ui.run('addExample("delete_recent")');
  assert.equal(ui.run('writeSource()'), 'manual');
  assert.equal(ui.run('materialize().table_write_rates'), undefined);
  assert.equal(ui.run('materialize().table_write_rates_origin'), undefined);
  assert.throws(() => ui.run('requestSnapshot()'), /Профиль записи не задан/);
});
