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
    document: {getElementById: element, querySelectorAll: () => [],
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

test('все три шаблона и обе границы частоты допустимы', () => {
  for (const template of templates) assert.doesNotThrow(check(copy(template.workload)));
  for (const value of [0, 1000000, 1000000.0]) {
    const workload = copy(templates[0].workload);
    workload.profiles[0].calls_per_hour[workload.queries[0].id] = value;
    assert.doesNotThrow(check(workload));
  }
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
});
