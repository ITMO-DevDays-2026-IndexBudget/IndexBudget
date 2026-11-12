'use strict';

const $ = id => document.getElementById(id);
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const clone = value => JSON.parse(JSON.stringify(value));
const format = value => new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 2}).format(value);
const draftKey = 'indexbudget.frontend.v1';
const maxCallsPerHour = 1000000;
const defaultIndexMethods = ['btree', 'brin', 'hash'];
let templates = [], state, tab = 'queries', queryIndex = 0, busy = false, health = {};
let guardsText = '', manualText = '', activeJob = null, saveTimer;

const helpTexts = {
  storage: ['Бюджет места', 'Лимит для новых индексов. К оценке размера добавляется запас 20%. Нулевой бюджет оставляет базу без новых индексов.'],
  write: ['Бюджет записи', 'Расход = изменённые строки × число колонок индекса, включая INCLUDE. Выключенный лимит не ограничивает запись. Значение 0 включает нулевой бюджет.'],
  objective: ['Robust или mean?', 'Robust выбирает набор по худшему отношению стоимости сценария к исходной. Mean — по отношению средних стоимостей с учётом весов.'],
  mode: ['Глубина анализа', 'Быстрый режим подходит для первого запуска. Глубокий даёт больше времени и попыток, но может выбрать тот же набор. При остановке по лимиту сохраняется пригодный частичный результат.'],
  profiles: ['Частота и вес', 'Частота: от 0 до 1 000 000 вызовов SQL в час, целое число. Это вход для расчёта, а не команда выполнить столько запросов. Веса 9 и 1 задают доли сценариев 90% и 10%. В robust участвуют оба сценария.'],
};

function helpButton(key) {
  return `<button type="button" class="help-trigger" data-help="${key}" aria-label="Подсказка: ${helpTexts[key][0]}" aria-expanded="false">?</button>`;
}

function installHelp() {
  for (const [field, key] of [['storage','storage'],['write-enabled','write'],['objective','objective'],['mode','mode']]) {
    const label = document.querySelector(`label[for="${field}"]`);
    const row = document.createElement('div');
    row.className = 'help-label';
    label.before(row); row.append(label); row.insertAdjacentHTML('beforeend', helpButton(key));
  }
  const bubble = document.createElement('div');
  bubble.id = 'context-help'; bubble.className = 'help-bubble'; bubble.role = 'tooltip'; bubble.hidden = true;
  document.body.append(bubble);
  let anchor = null, pinned = false, timer;
  function close() {
    clearTimeout(timer);
    if (anchor) { anchor.removeAttribute('aria-describedby'); anchor.setAttribute('aria-expanded', 'false'); }
    anchor = null; pinned = false; bubble.hidden = true;
  }
  function place() {
    if (!anchor) return;
    const r = anchor.getBoundingClientRect();
    if (!anchor.isConnected || r.bottom < 0 || r.top > innerHeight) { close(); return; }
    const b = bubble.getBoundingClientRect();
    bubble.style.left = `${Math.max(10, Math.min(r.left - 12, innerWidth - b.width - 10))}px`;
    bubble.style.top = `${Math.max(10, r.bottom + b.height + 10 <= innerHeight ? r.bottom + 8 : r.top - b.height - 8)}px`;
  }
  function open(button, pin = false) {
    close(); anchor = button; pinned = pin;
    const [title, text] = helpTexts[button.dataset.help];
    bubble.innerHTML = `<strong>${escape(title)}</strong><p>${escape(text)}</p>`;
    bubble.hidden = false;
    anchor.setAttribute('aria-describedby', bubble.id); anchor.setAttribute('aria-expanded', 'true');
    place();
  }
  function leave() {
    clearTimeout(timer);
    if (!pinned) timer = setTimeout(() => {
      if (document.activeElement !== anchor && !bubble.matches(':hover') && !anchor?.matches(':hover')) close();
    }, 160);
  }
  document.addEventListener('pointerover', e => {
    if (e.pointerType === 'touch') return;
    const button = e.target.closest('[data-help]');
    if (button && !button.matches(':disabled') && !pinned) {
      clearTimeout(timer); timer = setTimeout(() => open(button), 250);
    } else if (bubble.contains(e.target)) clearTimeout(timer);
  });
  document.addEventListener('pointerout', e => {
    if (e.target.closest('[data-help]') || bubble.contains(e.target)) leave();
  });
  document.addEventListener('focusin', e => {
    const button = e.target.closest('[data-help]');
    if (button) open(button);
    else if (!pinned) close();
  });
  document.addEventListener('focusout', e => { if (e.target === anchor) leave(); });
  document.addEventListener('click', e => {
    const button = e.target.closest('[data-help]');
    if (button && !button.matches(':disabled')) {
      if (anchor === button && pinned) close(); else open(button, true);
    } else if (!bubble.contains(e.target)) close();
  });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && anchor) { close(); e.preventDefault(); } });
  document.addEventListener('scroll', place, true);
  window.addEventListener('resize', place);
  return close;
}

function installTour() {
  const seenKey = 'indexbudget.tour.v1';
  const steps = [
    {target:'#templates', title:'Выбери демо-базу', text:'Датасет заполнит форму готовыми SQL, частотами и бюджетами. Их можно изменить. Схема и данные остаются фиксированными.'},
    {target:'.editor-panel', tab:'examples', title:'Добавь пример запроса', text:'Во вкладке «Примеры» есть поиск по группе, диапазон дат и JOIN. Выбери запрос по описанию. После добавления его SQL и частоты можно изменить.'},
    {target:'.editor-panel', tab:'queries', title:'Проверь SQL', text:'Добавь свой SQL или измени готовый. Во вкладке JSON можно посмотреть и скачать точный вход анализа.'},
    {target:'.editor-panel', tab:'profiles', title:'Задай частоты и веса', text:'Для каждого сценария укажи частоты SQL в час и его вес. Частоты принимаются целыми числами от 0 до 1 000 000. Анализ использует их в расчёте, а не выполняет столько запросов.'},
    {target:'.schema-panel', title:'Посмотри схему', text:'На карте видны таблицы, колонки, внешние ключи и существующие индексы датасета. Подпись показывает источник схемы: шаблон или PostgreSQL.'},
    {target:'.budget-panel', title:'Один запуск или серия бюджетов', text:'Запусти один анализ или сравни 3–5 бюджетов. В серии SQL, частоты и исходный пул индексов общие. Место задаётся в байтах, запись в условных ед./ч. Результаты попадут в один отчёт.'},
    {target:'.strategy-grid', title:'Выбери цель и глубину', text:'Robust учитывает худший сценарий, mean — средний с учётом весов. Глубина задаёт ресурсы поиска; больше времени не гарантирует лучший результат.'},
    {target:'.advanced', advanced:true, title:'Дополнительные настройки', text:'Здесь можно загрузить профиль записи из CLI, задать предел ухудшения запросов и сравнить ручной набор индексов. Для первого запуска подойдут настройки датасета.'},
    {target:'.launch-bar', title:'Запусти анализ', text:'Сервер подготовит базу и вызовет CLI. Отчёт откроется автоматически. Если поиск остановится по лимиту, в отчёте останутся пригодный результат и причина остановки.'}
  ];
  const layer = document.createElement('div');
  layer.className = 'tour-layer'; layer.hidden = true;
  layer.innerHTML = `<div class="tour-spotlight" aria-hidden="true"></div><section class="tour-card" role="dialog" aria-modal="true" aria-labelledby="tour-title" aria-describedby="tour-text"><div class="tour-heading"><span id="tour-count"></span><button type="button" class="tour-skip" data-tour="skip">Пропустить</button></div><div class="tour-meter" aria-hidden="true"><span></span></div><h2 id="tour-title" tabindex="-1"></h2><p id="tour-text"></p><div class="tour-actions"><button type="button" class="quiet-button" data-tour="back">← Назад</button><button type="button" class="primary" data-tour="next">Далее →</button></div><span class="tour-keyboard">Esc — закрыть · ← → — переключить шаг</span></section>`;
  document.body.append(layer);
  const card = layer.querySelector('.tour-card'), spotlight = layer.querySelector('.tour-spotlight');
  const next = layer.querySelector('[data-tour="next"]'), back = layer.querySelector('[data-tour="back"]');
  let index = -1, target, previous, inertNodes = [], frame;

  function place() {
    if (index < 0 || !target?.isConnected) return;
    const r = target.getBoundingClientRect(), c = card.getBoundingClientRect(), gap = 16;
    const width = document.documentElement.clientWidth, height = innerHeight;
    let x, y;
    if (r.right + gap + c.width <= width - 12) {
      x = r.right + gap; y = Math.max(12, Math.min(r.top, height - c.height - 12));
    } else if (r.left - gap - c.width >= 12) {
      x = r.left - gap - c.width; y = Math.max(12, Math.min(r.top, height - c.height - 12));
    } else {
      x = Math.max(12, Math.min(r.left, width - c.width - 12));
      y = r.bottom + gap + c.height <= height - 12 ? r.bottom + gap
        : r.top - gap - c.height >= 12 ? r.top - gap - c.height : height - c.height - 12;
    }
    card.style.left = `${x}px`; card.style.top = `${Math.max(12,y)}px`;
    const left = Math.max(6,r.left-6), top = Math.max(6,r.top-6), right = Math.min(width-6,r.right+6);
    let bottom = Math.min(height-6,r.bottom+6);
    // На узком экране карточка располагается под видимой частью большого блока.
    if (x < right && x + c.width > left && y > top && y < bottom) bottom = y - 10;
    Object.assign(spotlight.style, {left:`${left}px`,top:`${top}px`,width:`${Math.max(0,right-left)}px`,height:`${Math.max(0,bottom-top)}px`});
  }

  function refreshPosition() {
    cancelAnimationFrame(frame); frame = requestAnimationFrame(place);
  }

  function show(newIndex) {
    index = newIndex;
    const step = steps[index];
    if (step.tab) { tab = step.tab; renderEditor(); }
    document.querySelector('.advanced').open = !!step.advanced;
    target = document.querySelector(step.target);
    $('tour-count').textContent = `${index + 1} / ${steps.length} · Знакомство с IndexBudget`;
    $('tour-title').textContent = step.title; $('tour-text').textContent = step.text;
    layer.querySelector('.tour-meter span').style.width = `${(index+1)/steps.length*100}%`;
    back.disabled = index === 0;
    next.textContent = index === steps.length-1 ? 'Начать работу ✓' : 'Далее →';
    target.scrollIntoView({block:'start',behavior:'instant'});
    window.scrollBy({top:-28,behavior:'instant'});
    place();
    $('tour-title').focus({preventScroll:true});
  }

  function finish() {
    if (index < 0) return;
    index = -1; layer.hidden = true; cancelAnimationFrame(frame);
    inertNodes.forEach(([node, value]) => { node.inert = value; });
    tab = previous.tab; queryIndex = previous.queryIndex; renderEditor();
    document.querySelector('.advanced').open = previous.advanced;
    window.scrollTo({top:previous.scroll,behavior:'instant'});
    const focus = previous.focus?.isConnected ? previous.focus : $('start-tour');
    focus?.focus({preventScroll:true});
    try { localStorage.setItem(seenKey, 'done'); } catch {}
  }

  function start() {
    if (!state || busy || index >= 0) return;
    previous = {tab, queryIndex, advanced:document.querySelector('.advanced').open, scroll:scrollY, focus:document.activeElement};
    closeHelp();
    inertNodes = [...document.querySelectorAll('body > header, body > main, body > .skip')].map(node => [node,node.inert]);
    inertNodes.forEach(([node]) => { node.inert = true; });
    layer.hidden = false; show(0);
  }

  layer.addEventListener('click', e => {
    const action = e.target.closest('[data-tour]')?.dataset.tour;
    if (action === 'skip') finish();
    if (action === 'back' && index > 0) show(index-1);
    if (action === 'next') index === steps.length-1 ? finish() : show(index+1);
  });
  document.addEventListener('keydown', e => {
    if (index < 0) return;
    if (['Escape','ArrowLeft','ArrowRight','Tab'].includes(e.key)) {
      e.preventDefault(); e.stopImmediatePropagation();
      if (e.key === 'Escape') finish();
      if (e.key === 'ArrowLeft' && index > 0) show(index-1);
      if (e.key === 'ArrowRight') index === steps.length-1 ? finish() : show(index+1);
      if (e.key === 'Tab') {
        const buttons = [...card.querySelectorAll('button:not(:disabled)')];
        const current = buttons.indexOf(document.activeElement);
        const position = current < 0 ? (e.shiftKey ? buttons.length-1 : 0) : (current+(e.shiftKey?-1:1)+buttons.length)%buttons.length;
        buttons[position].focus();
      }
    }
  }, true);
  document.addEventListener('scroll', refreshPosition, true);
  window.addEventListener('resize', refreshPosition);
  $('start-tour').addEventListener('click', start);
  return {finish, maybeStart() {
    try { if (!localStorage.getItem(seenKey)) start(); } catch { /* Без хранилища тур доступен вручную. */ }
  }};
}

async function api(url, body) {
  const response = await fetch(url, body === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `Ошибка HTTP ${response.status}`);
  return data;
}

function error(message) {
  $('error').hidden = !message;
  $('error').textContent = message || '';
  if (message) $('error').scrollIntoView({block: 'nearest', behavior: 'smooth'});
}

function persist() {
  try {
    localStorage.setItem(draftKey, JSON.stringify({state: {
      template_id: state.template_id, template_version: state.template_version, workload: state.workload
    }, guardsText, manualText, writeSource: writeSource(), seriesUI: seriesSettings()}));
    $('saved').textContent = 'Черновик сохранён в этом браузере';
  } catch {
    $('saved').textContent = 'Браузер не позволяет сохранить черновик';
  }
}

function changed() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(persist, 180);
  renderSummary();
}

function templateState(template) {
  return {template_id: template.id, template_version: template.version,
    workload: demoWorkload(template.workload), catalog: clone(template.catalog), catalogSource: 'template'};
}

function demoWorkload(workload) {
  const result = clone(workload);
  // Старые черновики не задавали методы. Явный выбор пользователя сохраняем.
  if (!Object.hasOwn(result, 'index_methods')) result.index_methods = [...defaultIndexMethods];
  return result;
}

function restoreDraft(draft, availableTemplates) {
  const saved = draft?.state;
  const template = availableTemplates.find(t => t.id === saved?.template_id && t.version === saved?.template_version);
  const w = saved?.workload;
  // Старые черновики содержали DDL, seed и каталог: восстанавливаем только ввод нагрузки.
  // Ошибочные числовые значения оставляем для исправления, не округляем и не заменяем нулём.
  if (!template || !w || w.schema_version !== 2 || !w.constraints || typeof w.constraints !== 'object'
      || !Array.isArray(w.queries) || !w.queries.length || w.queries.length > 20
      || !w.queries.every(q => q && typeof q.id === 'string' && typeof q.sql === 'string')
      || !Array.isArray(w.profiles) || !w.profiles.length || w.profiles.length > 3
      || !w.profiles.every(p => p && typeof p.id === 'string' && p.calls_per_hour
        && typeof p.calls_per_hour === 'object' && !Array.isArray(p.calls_per_hour))
      || (w.table_write_rates && (typeof w.table_write_rates !== 'object'
        || !Object.values(w.table_write_rates).every(r => r && typeof r === 'object')))) return null;
  return {state: {...templateState(template), workload: demoWorkload(w), writeSource: draft.writeSource === 'observed' || w.table_write_rates_origin ? 'observed' : 'manual'},
    guardsText: typeof draft.guardsText === 'string' ? draft.guardsText : '',
    manualText: typeof draft.manualText === 'string' ? draft.manualText : ''};
}

function loadTemplate(id) {
  const t = templates.find(t => t.id === id);
  state = templateState(t);
  guardsText = t.workload.constraints.query_cost_guards ? JSON.stringify(t.workload.constraints.query_cost_guards, null, 2) : '';
  manualText = t.workload.manual_indexes ? JSON.stringify(t.workload.manual_indexes, null, 2) : '';
  queryIndex = 0;
  error(''); renderAll(); persist();
}

function switchTemplate(id) {
  if (id === state.template_id) return;
  const current = templates.find(t => t.id === state.template_id);
  let modified = true;
  try { modified = JSON.stringify(materialize()) !== JSON.stringify(current.workload); } catch {}
  if (modified && !confirm('Перейти к другому датасету? Изменённые SQL, частоты, профиль записи и бюджеты будут заменены исходной нагрузкой выбранного датасета.')) return;
  loadTemplate(id);
}

function examplesTemplate() {
  return templates.find(t => Array.isArray(t.examples) && t.examples.length);
}

function templateExamples(template) {
  if (template.examples?.length) return template.examples;
  return template.workload.queries.map(query => ({
    ...query,
    name: query.id,
    statement_kind: query.sql.trim().split(/\s+/, 1)[0].toLowerCase(),
    calls_per_hour: Object.fromEntries(template.workload.profiles.map(profile => [profile.id, profile.calls_per_hour[query.id]])),
    cost_guard: template.workload.constraints.query_cost_guards?.[query.id],
  }));
}

function queryExample(query) {
  return templates.find(t => t.id === state.template_id)?.examples?.find(example => example.sql === query.sql);
}

function selectedExampleTables(workload) {
  const examples = templates.find(t => t.id === state.template_id)?.examples || [];
  const matched = workload.queries.map(query => examples.find(example => example.sql === query.sql));
  return matched.length && matched.every(example => Array.isArray(example?.tables))
    ? [...new Set(matched.flatMap(example => example.tables))] : null;
}

function manualExampleTables(workload) {
  if (writeSource() !== 'manual' || workload.table_write_rates_origin
      || Object.values(workload.table_write_rates || {}).some(rate => (rate?.source ?? 'manual') !== 'manual')) return null;
  return selectedExampleTables(workload);
}

function addExample(id) {
  const template = templates.find(t => t.id === state.template_id);
  const example = template && templateExamples(template).find(item => item.id === id);
  if (!example) throw new Error('Пример недоступен в выбранном датасете.');
  const w = state.workload;
  const existing = w.queries.findIndex(query => query.sql === example.sql);
  if (existing !== -1) return {id: w.queries[existing].id, index: existing, alreadyAdded: true};
  if (w.queries.length >= 20) throw new Error('В нагрузке уже 20 запросов. Удали один запрос, чтобы добавить пример.');
  let guards = {};
  if (guardsText.trim()) {
    try { guards = JSON.parse(guardsText); } catch { throw new Error('Ограничения запросов: исправьте JSON в дополнительных параметрах перед добавлением примера.'); }
    if (!guards || typeof guards !== 'object' || Array.isArray(guards)) throw new Error('Ограничения запросов: ожидается объект JSON.');
  }
  let queryId = example.id, suffix = 2;
  while (w.queries.some(query => query.id === queryId)) queryId = `${example.id}_${suffix++}`;
  const frequencies = w.profiles.map(profile => ({profile,
    value: Object.hasOwn(example.calls_per_hour, profile.id) ? example.calls_per_hour[profile.id]
      : (example.calls_per_hour.normal ?? Object.values(example.calls_per_hour)[0]),
    fallback: !Object.hasOwn(example.calls_per_hour, profile.id)}));
  if (!frequencies.every(({value}) => Number.isInteger(value) && value >= 0 && value <= maxCallsPerHour)
      || !frequencies.some(({value}) => value > 0)) throw new Error('У примера нет допустимых исходных частот для текущих профилей.');
  w.queries.push({id: queryId, sql: example.sql});
  frequencies.forEach(({profile, value}) => { profile.calls_per_hour[queryId] = value; });
  if (example.cost_guard != null) {
    guards[queryId] = example.cost_guard;
    guardsText = JSON.stringify(guards, null, 2);
    $('guards').value = guardsText;
  }
  return {id: queryId, index: w.queries.length - 1, alreadyAdded: false,
    renamed: queryId !== example.id, fallback: frequencies.some(item => item.fallback), guard: example.cost_guard};
}

function templateIcon(kind) {
  const paths = kind === 'sand'
    ? '<rect x="2" y="3" width="8" height="7" rx="1"/><rect x="14" y="14" width="8" height="7" rx="1"/><path d="M6 10v7h8M10 6h8v8"/>'
    : kind === 'lavender' ? '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 9v12M15 9v12M3 15h18"/>'
    : '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 4 16 4 16 0V5M4 12v7c0 4 16 4 16 0v-7"/>';
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true">${paths}</svg>`;
}

function renderTemplates() {
  const library = examplesTemplate();
  const ordered = library ? [library, ...templates.filter(template => template !== library)] : templates;
  $('templates').innerHTML = ordered.map(t => `<button type="button" class="template-card ${state.template_id === t.id ? 'selected' : ''}" data-template="${escape(t.id)}" aria-pressed="${state.template_id === t.id}">
    <div class="template-top"><span class="template-icon ${escape(t.accent)}">${templateIcon(t.accent)}</span><span class="template-tag">${escape(t.tag)}</span></div>
    <h3>${escape(t.name)}</h3><p>${escape(t.description)}</p><div class="template-bottom"><span>${t.catalog.tables.length} ${tableCountLabel(t.catalog.tables.length)}</span></div>${state.template_id === t.id ? '<span class="selected-check" aria-hidden="true">✓</span>' : ''}</button>`).join('');
}

function tableCountLabel(count) {
  if (count % 100 >= 11 && count % 100 <= 14) return 'таблиц';
  if (count % 10 === 1) return 'таблица';
  return count % 10 >= 2 && count % 10 <= 4 ? 'таблицы' : 'таблиц';
}

function renderAll() {
  renderTemplates(); renderEditor(); renderControls(); renderDiagram(); renderSummary();
}

function renderControls() {
  const w = state.workload, c = w.constraints;
  $('storage').value = c.storage_budget_bytes / 1048576;
  $('storage-range').value = Math.min(256, c.storage_budget_bytes / 1048576);
  $('max-indexes').value = c.max_indexes;
  $('write-enabled').checked = c.write_budget_units_per_hour != null;
  $('write-budget').disabled = c.write_budget_units_per_hour == null;
  $('write-budget').value = c.write_budget_units_per_hour ?? 10000;
  $('objective').value = w.objective || 'robust';
  $('mode').value = w.analysis_mode || 'normal';
  $('ranking').value = w.candidate_ranking || 'frequency';
  $('guards').value = guardsText; $('manual').value = manualText;
  renderRates();
}

function writeSource() {
  return state.writeSource || (state.workload.table_write_rates_origin || Object.values(state.workload.table_write_rates || {}).some(r => r.source === 'observed') ? 'observed' : 'manual');
}

function validateWriteProfile(w, required = false, source = null) {
  const rates = w.table_write_rates, origin = w.table_write_rates_origin;
  const observed = source === 'observed' || !!origin || Object.values(rates || {}).some(r => r?.source === 'observed');
  const fix = 'Загрузи полный workload.json из CLI или выбери ручной профиль и заполни строки/час.';
  if (!rates || !Object.keys(rates).length) {
    if (required || observed || rates) throw new Error(`Профиль записи не задан. ${fix}`);
    return;
  }
  if (typeof rates !== 'object' || Array.isArray(rates)) throw new Error('Неверный профиль записи.');
  for (const rate of Object.values(rates)) {
    if (!rate || ['inserts_per_hour','updates_per_hour','deletes_per_hour'].some(k => typeof rate[k] !== 'number' || !Number.isFinite(rate[k]) || rate[k] < 0)) throw new Error(`Неизвестная или неверная интенсивность строк. ${fix}`);
    if ((rate.source ?? 'manual') !== (observed ? 'observed' : 'manual')) throw new Error('В одном профиле нельзя смешивать ручные значения и rates из наблюдения.');
  }
  if (source === 'manual' && observed) throw new Error('Наблюдаемый профиль не является ручным.');
  if (observed) {
    if (origin?.kind !== 'observed' || typeof origin.complete !== 'boolean' || !Array.isArray(origin.reasons)
        || !origin.reasons.every(r => typeof r === 'string') || !origin.window?.from || !origin.window?.to
        || typeof origin.window.period_seconds !== 'number' || !Number.isFinite(origin.window.period_seconds) || origin.window.period_seconds <= 0) throw new Error(`Не хватает корректных сведений об источнике или окне наблюдения. ${fix}`);
    if (required && !origin.complete) throw new Error(`Наблюдение неполное: ${origin.reasons.join(', ') || 'причина не указана'}. ${fix}`);
  }
}

function importWriteProfile(value) {
  if (!value || value.schema_version !== 2) throw new Error('Загрузи workload.json v2 из import-workload.');
  validateWriteProfile(value, false, 'observed');
  // Copy the producer output; do not calculate rates or import SQL-call frequencies.
  state.workload.table_write_rates = clone(value.table_write_rates);
  state.workload.table_write_rates_origin = clone(value.table_write_rates_origin);
  state.writeSource = 'observed';
}

function renderRates() {
  const w = state.workload, observed = writeSource() === 'observed', origin = w.table_write_rates_origin;
  $('write-source').value = writeSource();
  $('observed-upload').hidden = !observed;
  $('write-origin').innerHTML = observed ? origin ? `<p><strong>${origin.complete ? 'Наблюдение полное' : 'Наблюдение неполное'}</strong> · ${escape(origin.window?.from)} — ${escape(origin.window?.to)} · ${escape(origin.window?.period_seconds)} с</p><p class="field-note">Источник: ${escape(origin.source?.source_id || 'не сохранён')}; БД OID: ${escape(origin.source?.database_oid ?? 'неизвестен')}; PostgreSQL ${escape(origin.source?.postgres_version || 'неизвестен')}. ${escape((origin.reasons || []).join('; '))}</p><p class="field-note">В PG16 часть сбросов счётчиков отдельной таблицы нельзя обнаружить. HOT сохраняется для диагностики и не вычитается из модели затрат индекса.</p>` : '<p class="field-note">Загрузи workload.json с наблюдаемым профилем. Ручные значения не подставляются автоматически.</p>' : '<p class="field-note">Интенсивность записи задана вручную. Окна наблюдения нет.</p>';
  const rows = Object.entries(w.table_write_rates || {});
  $('write-rates').innerHTML = `${rows.length ? '<div class="rate-row rate-head"><span>Таблица</span><span>INSERT</span><span>UPDATE</span><span>DELETE</span><span></span></div>' : '<p class="field-note">Профиль записи не задан. При включённом бюджете нужны данные для всех таблиц запросов.</p>'}
    ${rows.map(([name, rate], i) => `<div class="rate-row"><span>${escape(name)}</span>${['inserts_per_hour','updates_per_hour','deletes_per_hour'].map(k => observed ? `<span class="observed-rate">${escape(rate[k] ?? 'Неизвестно')}</span>` : `<input type="number" min="0" step="any" value="${escape(rate[k])}" data-rate="${i}" data-rate-key="${k}" aria-label="${escape(name)} ${k}">`).join('')}${observed ? '<span></span>' : `<button type="button" data-remove-rate="${i}" aria-label="Удалить интенсивность записи ${escape(name)}">×</button>`}</div>`).join('')}
    ${observed ? '' : '<div class="rate-actions"><button type="button" class="quiet-button" id="add-rate">+ Таблица</button><button type="button" class="text-button" id="clear-rates">Очистить профиль записи</button></div>'}`;
}

function renderEditor() {
  closeHelp();
  $('query-count').textContent = state.workload.queries.length;
  document.querySelectorAll('[role="tab"][data-tab]').forEach(b => {
    b.setAttribute('aria-selected', b.dataset.tab === tab);
    b.tabIndex = b.dataset.tab === tab ? 0 : -1;
  });
  $('editor-content').setAttribute('aria-labelledby', `tab-${tab}`);
  ({queries: renderQueries, examples: renderExamples, profiles: renderProfiles, schema: renderSchema, json: renderJson})[tab]();
}

function renderQueries() {
  const queries = state.workload.queries;
  queryIndex = Math.min(queryIndex, queries.length - 1);
  const q = queries[queryIndex];
  const selectedExample = queryExample(q);
  $('editor-content').innerHTML = `<div class="query-library-link"><span>Выбери готовый сценарий или напишите свой SQL.</span><button type="button" class="text-button" data-tab="examples">Выбрать пример</button></div><div class="editor-toolbar"><div class="query-tabs">${queries.map((q,i) => `<button type="button" class="query-tab ${i===queryIndex ? 'active' : ''}" data-query="${i}" title="${escape(q.id)}" aria-pressed="${i===queryIndex}"><span>Q${String(i+1).padStart(2,'0')}</span> ${escape(queryExample(q)?.name || q.id.replace(/^(records_|orders_|wide_)/,''))}</button>`).join('')}</div><button type="button" id="add-query" class="quiet-button" ${queries.length>=20?'disabled':''} aria-label="Добавить запрос">+ SQL</button></div>
    ${selectedExample ? `<p class="query-purpose">${escape(selectedExample.purpose)}</p>` : ''}
    <div class="editor-meta"><label for="query-id">ID</label><input id="query-id" value="${escape(q.id)}" required><button type="button" id="delete-query" class="delete-button" ${queries.length<=1?'disabled':''}>Удалить запрос</button></div>
    <div class="code-editor"><div class="line-numbers" aria-hidden="true" id="line-numbers"></div><textarea id="sql-editor" spellcheck="false" aria-label="SQL запроса ${escape(q.id)}" required></textarea></div>`;
  // HTML-парсер удаляет первый перевод строки внутри textarea; значение задаём без разбора HTML.
  $('sql-editor').value = q.sql;
  lineNumbers();
  $('sql-editor').addEventListener('scroll', () => $('line-numbers').scrollTop = $('sql-editor').scrollTop);
}

function renderExamples() {
  const template = templates.find(t => t.id === state.template_id);
  const examples = templateExamples(template);
  const limit = state.workload.queries.length >= 20;
  const profileNames = {normal: 'Обычный день', peak: 'Пик'};
  $('editor-content').innerHTML = `<div class="examples-content"><h3>Примеры запросов · ${escape(template.name)}</h3>
    <p class="examples-frequency-note">Для нового профиля добавляется исходная частота примера; её можно изменить во вкладке «Нагрузка».</p>
    ${limit ? '<p class="examples-limit" role="status">В нагрузке 20 запросов. Удали один, чтобы добавить новый пример.</p>' : ''}
    <p id="example-status" class="example-status" role="status" aria-live="polite"></p>
    <div class="example-list">${examples.map(example => {
      const included = state.workload.queries.some(query => query.sql === example.sql);
      const frequencies = Object.entries(example.calls_per_hour).map(([id, calls]) =>
        `${escape(profileNames[id] || id)}: <strong>${format(calls)}</strong>`).join(' · ');
      return `<article class="example-item" data-example-id="${escape(example.id)}"><div class="example-heading"><h4>${escape(example.name)}</h4><span class="example-kind">${escape(example.statement_kind)}</span></div>${example.purpose ? `<p class="example-purpose">${escape(example.purpose)}</p>` : ''}<p class="example-frequencies">${frequencies} вызовов/ч</p><details class="example-sql"><summary>SQL и условия</summary><pre tabindex="0" aria-label="SQL примера ${escape(example.name)}">${escape(example.sql)}</pre>${example.syntax_hint ? `<p>${escape(example.syntax_hint)}</p>` : ''}${example.cost_guard != null ? `<p>При добавлении включается предел ухудшения planner cost: ${format((example.cost_guard - 1) * 100)}%. Настройка доступна в дополнительных параметрах.</p>` : ''}</details><div class="example-actions"><button type="button" class="quiet-button add-example" data-add-example="${escape(example.id)}" ${included || limit ? 'disabled' : ''} aria-label="${included ? 'Уже в нагрузке' : 'Добавить в нагрузку'}: ${escape(example.name)}">${included ? 'Уже в нагрузке' : 'Добавить в нагрузку'}</button></div></article>`;
    }).join('')}</div></div>`;
}

function lineNumbers() {
  $('line-numbers').textContent = Array.from({length: Math.max(8, $('sql-editor').value.split('\n').length)}, (_,i) => i+1).join('\n');
}

function renderProfiles() {
  const {queries, profiles} = state.workload;
  $('editor-content').innerHTML = `<div class="profile-content"><div class="subheading"><h4>Частоты и веса</h4>${helpButton('profiles')}</div><div class="profile-table-wrap"><table class="profile-table"><thead><tr><th>Запрос / профиль</th>${profiles.map((p,i) => `<th><div class="profile-name"><input value="${escape(p.id)}" data-profile-id="${i}" aria-label="ID профиля ${i+1}" required><button type="button" data-delete-profile="${i}" ${profiles.length===1?'disabled':''} aria-label="Удалить профиль ${escape(p.id)}">×</button></div></th>`).join('')}</tr></thead><tbody>
    <tr><td>Вес сценария</td>${profiles.map((p,i) => `<td><input type="number" min="0" step="any" value="${escape(p.weight)}" data-weight="${i}" aria-label="Вес ${escape(p.id)}" required><div class="profile-share" data-share="${i}"></div></td>`).join('')}</tr>
    ${queries.map((q,qi) => `<tr><td>${escape(q.id)}</td>${profiles.map((p,pi) => `<td><input type="number" min="0" max="${maxCallsPerHour}" step="1" value="${escape(p.calls_per_hour[q.id])}" data-frequency-profile="${pi}" data-frequency-query="${qi}" aria-label="Частота ${escape(q.id)} в ${escape(p.id)}" required></td>`).join('')}</tr>`).join('')}</tbody><tfoot><tr><td>Всего вызовов/ч</td>${profiles.map((p,i)=>`<td data-total="${i}"></td>`).join('')}</tr></tfoot></table></div><div class="profile-actions"><button type="button" id="add-profile" class="quiet-button" ${profiles.length>=3?'disabled':''}>+ Профиль нагрузки</button></div></div>`;
  updateProfiles();
}

function updateProfiles() {
  const profiles = state.workload.profiles, sum = profiles.reduce((s,p) => s + p.weight, 0);
  document.querySelectorAll('[data-share]').forEach(e => e.textContent = sum > 0 ? format(profiles[+e.dataset.share].weight/sum*100) + '% от времени' : 'Задай веса');
  document.querySelectorAll('[data-total]').forEach(e => {
    const values = Object.values(profiles[+e.dataset.total].calls_per_hour);
    e.textContent = values.every(v => Number.isInteger(v) && v >= 0 && v <= maxCallsPerHour)
      ? format(values.reduce((a,b) => a+b, 0)) : 'Проверь частоты';
  });
}

function renderSchema() {
  const template = templates.find(t => t.id === state.template_id);
  $('editor-content').innerHTML = `<div class="schema-content"><h4 id="schema-sql-label">Схема и существующие индексы</h4><pre class="code" tabindex="0" aria-labelledby="schema-sql-label">${escape(template.schema_sql)}</pre></div>`;
}

function materialize() {
  const w = clone(state.workload);
  for (const [text, key, container, array] of [[guardsText,'query_cost_guards',w.constraints,false],[manualText,'manual_indexes',w,true]]) {
    if (text.trim()) {
      let value;
      try { value = JSON.parse(text); } catch { throw new Error(`${array ? 'Ручные индексы' : 'Ограничения запросов'}: некорректный JSON.`); }
      if (!value || typeof value !== 'object' || Array.isArray(value) !== array) throw new Error(`${key}: ожидается ${array ? 'массив' : 'объект'} JSON.`);
      container[key] = value;
    } else delete container[key];
  }
  const tables = manualExampleTables(w);
  if (tables && w.table_write_rates) {
    // Оригинальные rates остаются в черновике: повторное добавление JOIN возвращает их в экспорт.
    w.table_write_rates = Object.fromEntries(Object.entries(w.table_write_rates).filter(([table]) => tables.includes(table)));
  }
  return w;
}

function renderJson() {
  let text, valid = true;
  try { text = JSON.stringify(materialize(), null, 2); } catch (e) { text = e.message; valid = false; }
  $('editor-content').innerHTML = `<div class="json-content"><div class="json-toolbar"><button type="button" id="download-json" class="quiet-button" ${valid?'':'disabled'}>↓ Скачать JSON</button><span class="hint">${valid ? format(new TextEncoder().encode(text).length) + ' байт' : 'Исправь дополнительные параметры'}</span></div><pre>${escape(text)}</pre></div>`;
}

function renderDiagram() {
  const catalog = state.catalog;
  $('schema-source').textContent = state.catalogSource === 'database' ? '● Схема из PostgreSQL' : '● Схема выбранного датасета';
  const column = c => `<div class="column-row"><b>${c.primary?'PK':catalog.relations.some(r=>r.label.includes(`(${c.name})`))?'FK':'·'}</b><span>${escape(c.name)}</span><span class="column-type">${escape(c.type.replace('timestamp without time zone','timestamp'))}</span></div>`;
  $('diagram').innerHTML = catalog.tables.map((t,i) => `<article class="db-table"><div class="db-table-head"><strong>${escape(t.name.replace(/^public\./,''))}</strong><span>${format(Math.max(0,t.rows))} строк</span></div><div class="db-table-body">${t.columns.slice(0,7).map(column).join('')}</div>${t.columns.length>7?`<details><summary>Ещё ${t.columns.length-7} колонок</summary>${t.columns.slice(7).map(column).join('')}</details>`:''}${t.indexes.length?`<details><summary>${t.indexes.length} существующих инд.</summary>${t.indexes.map(x=>`<p style="overflow-wrap:anywhere;margin-top:8px">${escape(x)}</p>`).join('')}</details>`:''}</article>${i < catalog.tables.length-1 && catalog.relations.length ? '<div class="relation">Связь по внешнему ключу</div>':''}`).join('') + (catalog.tables.length ? '' : '<p class="hint">В схеме пока нет таблиц</p>');
  $('schema-stats').innerHTML = `<span>${catalog.tables.length} табл. · ${catalog.tables.reduce((n,t)=>n+t.columns.length,0)} колонок</span><span>${catalog.relations.length} связей</span>`;
  if (catalog.relations.length) {
    const details = document.createElement('details'); details.className = 'field-note';
    const summary = document.createElement('summary'); summary.textContent = 'Внешние ключи'; details.append(summary);
    catalog.relations.forEach(r => { const p = document.createElement('p'); p.textContent = `${r.from} → ${r.to}: ${r.label}`; p.style.overflowWrap = 'anywhere'; details.append(p); });
    $('diagram').append(details);
  }
}

function renderSummary() {
  const w = state.workload, t = templates.find(t=>t.id===state.template_id);
  $('summary-title').textContent = t.name;
  const items = [['SQL-запросов',w.queries.length],['Профилей нагрузки',w.profiles.length],['Методы индексов',Array.isArray(w.index_methods) ? w.index_methods.map(m => ({btree:'B-tree',brin:'BRIN',hash:'Hash'}[m] || m)).join(', ') : 'Некорректный список'],['Бюджет места',format(w.constraints.storage_budget_bytes/1048576)+' MiB'],['Цель',w.objective==='mean'?'Средний результат':'Худший сценарий'],['Источник записи',writeSource()==='observed' ? (w.table_write_rates_origin?.complete ? 'Наблюдаемый · полный' : 'Наблюдаемый · требует проверки') : (w.table_write_rates ? 'Ручной' : 'Не задан')],['Лимит записи',w.constraints.write_budget_units_per_hour == null ? 'Выключен' : String(w.constraints.write_budget_units_per_hour)+' ед./ч']];
  $('summary').innerHTML = items.map(([k,v])=>`<div><dt>${escape(k)}</dt><dd>${escape(v)}</dd></div>`).join('');
}

function validateWorkload(workload) {
  const {queries, profiles} = workload;
  if (!Array.isArray(queries) || queries.length < 1 || queries.length > 20) throw new Error('Нужно от 1 до 20 запросов.');
  const ids = queries.map(q => q.id);
  if (ids.some(id => typeof id !== 'string' || !id.trim()) || new Set(ids).size !== ids.length) throw new Error('ID запросов должны быть непустыми и различаться.');
  for (const query of queries) {
    if (typeof query.sql !== 'string' || !query.sql.trim()) throw new Error(`${query.id}: введите SQL.`);
    if (new TextEncoder().encode(query.sql).length > 65536) throw new Error(`${query.id}: SQL превышает 64 KiB.`);
  }
  if (!Array.isArray(profiles) || profiles.length < 1 || profiles.length > 3) throw new Error('Нужно от 1 до 3 профилей нагрузки.');
  const profileIds = profiles.map(p => p.id);
  if (profileIds.some(id => typeof id !== 'string' || !id.trim()) || new Set(profileIds).size !== profileIds.length) throw new Error('ID профилей должны быть непустыми и различаться.');
  for (const profile of profiles) {
    if (!Number.isFinite(profile.weight) || profile.weight <= 0) throw new Error(`${profile.id}: вес должен быть положительным.`);
    const calls = profile.calls_per_hour;
    if (!calls || typeof calls !== 'object' || Array.isArray(calls) || Object.keys(calls).length !== ids.length || ids.some(id => !Object.hasOwn(calls, id))) throw new Error(`${profile.id}: задайте частоты для всех запросов.`);
    for (const id of ids) {
      if (!Number.isInteger(calls[id]) || calls[id] < 0 || calls[id] > maxCallsPerHour) throw new Error(`${profile.id}, ${id}: частота должна быть целым числом от 0 до 1 000 000 вызовов/ч.`);
    }
    if (!ids.some(id => calls[id] > 0)) throw new Error(`${profile.id}: хотя бы одна частота должна быть больше нуля.`);
  }
  if (ids.some(id => !profiles.some(p => p.calls_per_hour[id] > 0))) throw new Error('Каждый запрос должен использоваться хотя бы в одном профиле.');
}

function seriesSettings() {
  return Object.fromEntries(['launch-mode','series-budget','series-values','series-timeout'].map(id => [id, $(id).value]));
}
function updateSeriesSettings() {
  const enabled = $('launch-mode').value === 'series';
  $('series-settings').hidden = !enabled;
  for (const id of ['series-budget','series-values','series-timeout']) $(id).disabled = !enabled;
}
function seriesRequest() {
  if ($('launch-mode').value !== 'series') return null;
  const parts = $('series-values').value.split(',').map(v => v.trim());
  const values = parts.map(v => {
    if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(v)) return NaN;
    const value = Number(v);
    const nonzero = /[1-9]/.test(v.split(/e/i)[0]);
    if ((value === 0 && nonzero) || (v.startsWith('-') && nonzero)) return NaN;
    return value;
  });
  if (values.length < 3 || values.length > 5 || values.some(v => !Number.isFinite(v) || v < 0) || new Set(values).size !== values.length) throw new Error('Задай 3–5 различных неотрицательных бюджетов.');
  const budget = $('series-budget').value;
  if (budget === 'storage_budget_bytes' && values.some(v => !Number.isSafeInteger(v))) throw new Error('Для места задайте точные целые байты.');
  const timeout = Number($('series-timeout').value);
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 86400) throw new Error('Предел серии: 1–86400 секунд.');
  return {schema_version: 1, budget, values, timeout_seconds: timeout};
}

function requestSnapshot() {
  // Проверяем всю матрицу и SQL, даже если их вкладка сейчас не открыта.
  const workload = materialize();
  validateWorkload(workload);
  const invalid = [...$('workspace').querySelectorAll('input,textarea,select')].find(e=>!e.disabled && !e.checkValidity());
  if (invalid) { invalid.reportValidity(); throw new Error('Проверь выделенное поле.'); }
  const series = seriesRequest();
  validateWriteProfile(workload, workload.constraints.write_budget_units_per_hour != null || series?.budget === 'write_budget_units_per_hour', writeSource());
  return {template_id: state.template_id, template_version: state.template_version, workload, ...(writeSource() === 'observed' ? {write_source: 'observed'} : {}), ...(series ? {series} : {})};
}

function setBusy(value) {
  if (value) tour.finish();
  $('start-tour').disabled = value;
  closeHelp();
  busy = value; $('workspace').disabled = value;
  $('analyze').disabled = value;
  $('analyze').innerHTML = value ? 'Анализ выполняется…' : 'Запустить анализ <span aria-hidden="true">↗</span>';
  $('launch-dot').classList.toggle('pulse', value);
}

// A UI easter egg only. The CLI remains responsible for SQL validation.
function startsWithDrop(sql) {
  if (typeof sql !== 'string') return false;
  let index = 0;
  const word = () => {
    for (;;) {
      while (/\s/.test(sql[index] || '') && index < sql.length) index++;
      if (sql.startsWith('--', index)) {
        const end = sql.slice(index + 2).search(/[\r\n]/);
        index = end < 0 ? sql.length : index + 2 + end + 1;
      } else if (sql.startsWith('/*', index)) {
        let depth = 1; index += 2;
        while (index < sql.length && depth) {
          if (sql.startsWith('/*', index)) { depth++; index += 2; }
          else if (sql.startsWith('*/', index)) { depth--; index += 2; }
          else index++;
        }
        if (depth) return null;
      } else break;
    }
    const match = /^[a-z_][a-z_0-9$]*/i.exec(sql.slice(index));
    if (!match) return null;
    index += match[0].length;
    return match[0].toUpperCase();
  };
  return word() === 'DROP';
}

async function launch(kind) {
  if (busy) return;
  let request;
  try { request = requestSnapshot(); } catch(e) { error(e.message); return; }
  if (request.workload.queries.some(query => startsWithDrop(query.sql))) {
    persist();
    window.location.assign('/drop-table.jpg');
    return;
  }
  error(''); persist(); setBusy(true);
  try {
    const result = await api(kind === 'schema' ? '/api/schema-previews' : '/api/runs', request);
    activeJob = result.run_id;
    history.replaceState(null,'', `/?run=${encodeURIComponent(activeJob)}`);
    await poll(activeJob);
  } catch(e) { setBusy(false); error(e.message); }
}

function showProgress(job) {
  const labels = {queued:'В очереди',preparing:'Подготовка базы',analyzing:'Анализ нагрузки',publishing:'Публикация отчёта',completed:'Готово',partial:'Получен частичный результат',failed:'Запуск остановлен'};
  const details = {queued:`Позиция в очереди: ${job.queue_position || 1}. База используется одним запуском за раз.`,preparing:'Создаём схему, загружаем данные и собираем статистику.',analyzing:'CLI оценивает наборы гипотетических индексов через EXPLAIN.',publishing:'Сохраняем HTML, JSON и SQL в отдельный каталог.',completed:job.kind==='schema'?'Диаграмма обновлена из каталога PostgreSQL.':'Открываем готовый отчёт.',partial:'Открываем отчёт с причинами остановки.',failed:'Введённые настройки сохранены. Исправь ошибку и повтори запуск.'};
  $('run-title').textContent = labels[job.status];
  $('run-description').textContent = details[job.status] + (job.points ? ' ' + job.points.map(p => `Вариант ${p.ordinal}: ${p.budget_value} · ${p.status}`).join('; ') : '');
  $('run-progress').hidden = false;
  $('run-progress').textContent = `${labels[job.status]} · ${job.run_id}${job.started_at ? ' · '+ Math.max(0, Math.floor(Date.now()/1000-job.started_at))+' с' : ''}`;
}

async function poll(id) {
  let failures = 0;
  while (activeJob === id) {
    let job;
    try { job = await api(`/api/runs/${encodeURIComponent(id)}`); failures = 0; }
    catch(e) {
      failures++;
      if (failures >= 5) { error('Не удалось получить состояние запуска. Обнови страницу, чтобы продолжить наблюдение. Повторный анализ не запустится.'); return; }
      await new Promise(resolve=>setTimeout(resolve,2000)); continue;
    }
    showProgress(job);
    if (['completed','partial','failed'].includes(job.status)) {
      activeJob = null; setBusy(false);
      history.replaceState(null,'','/');
      if (job.catalog) {
        state.catalog = job.catalog; state.catalogSource = 'database';
        renderDiagram(); persist();
      }
      if (job.status === 'failed') error(job.error || 'Не удалось завершить запуск.');
      else if (job.report_url) location.assign(job.report_url);
      return;
    }
    await new Promise(resolve=>setTimeout(resolve,1200));
  }
}

document.addEventListener('click', e => {
  const button = e.target.closest('button');
  if (!button || button.disabled || (busy && button.id !== 'analyze')) return;
  const d = button.dataset, w = state?.workload;
  if (!state) return;
  if (d.template) { switchTemplate(d.template); return; }
  if (d.tab) { tab=d.tab; renderEditor(); return; }
  if (d.query !== undefined) { queryIndex=+d.query; renderEditor(); return; }
  if (d.addExample !== undefined) {
    try {
      const result = addExample(d.addExample);
      queryIndex = result.index;
      error(''); renderEditor(); changed(); persist();
      $('example-status').textContent = result.alreadyAdded ? 'Этот SQL уже есть в нагрузке.'
        : `Добавлен запрос «${templateExamples(templates.find(t => t.id === state.template_id)).find(item => item.id === d.addExample).name}».${result.renamed ? ` ID ${result.id}: ранее изменённый запрос сохранён.` : ''}${result.fallback ? ' Для ваших профилей использована исходная частота примера.' : ''} Открой «Запросы», чтобы изменить SQL.`;
      document.querySelector(`[data-add-example="${d.addExample}"]`)?.closest('article')?.querySelector('summary')?.focus();
    } catch (e) { error(e.message); }
    return;
  }
  if (d.step) { w.constraints.max_indexes=Math.max(0,Math.min(5,w.constraints.max_indexes + +d.step)); $('max-indexes').value=w.constraints.max_indexes; changed(); return; }
  if (d.deleteProfile !== undefined) { w.profiles.splice(+d.deleteProfile,1); renderEditor(); changed(); return; }
  if (d.removeRate !== undefined) { delete w.table_write_rates[Object.keys(w.table_write_rates)[+d.removeRate]]; if (!Object.keys(w.table_write_rates).length) delete w.table_write_rates; renderRates(); changed(); return; }
  switch(button.id) {
    case 'reset': loadTemplate(state.template_id); break;
    case 'add-query': {
      let n=w.queries.length+1; while(w.queries.some(q=>q.id===`query_${n}`)) n++;
      const id=`query_${n}`; w.queries.push({id,sql:w.queries[queryIndex].sql});
      w.profiles.forEach(p=>p.calls_per_hour[id]=100); queryIndex=w.queries.length-1; renderEditor(); changed(); break;
    }
    case 'delete-query': {
      const id=w.queries[queryIndex].id; w.queries.splice(queryIndex,1);
      w.profiles.forEach(p=>delete p.calls_per_hour[id]);
      if (guardsText.trim()) { try { const g=JSON.parse(guardsText); delete g[id]; guardsText=Object.keys(g).length?JSON.stringify(g,null,2):''; $('guards').value=guardsText; } catch {} }
      queryIndex=Math.max(0,queryIndex-1); renderEditor(); changed(); break;
    }
    case 'add-profile': {
      let n=w.profiles.length+1; while(w.profiles.some(p=>p.id===`profile_${n}`)) n++;
      w.profiles.push({id:`profile_${n}`,weight:1,calls_per_hour:Object.fromEntries(w.queries.map(q=>[q.id,100]))}); renderEditor(); changed(); break;
    }
    case 'add-rate': {
      const available=state.catalog.tables.map(t=>t.name).filter(n=>!w.table_write_rates?.[n]);
      const name=prompt('Точное имя таблицы: schema.table',available[0] || 'public.table_name');
      if (name?.trim()) { w.table_write_rates ||= {}; w.table_write_rates[name.trim()] ||= {inserts_per_hour:0,updates_per_hour:0,deletes_per_hour:0,source:'manual'}; renderRates(); changed(); } break;
    }
    case 'clear-rates': delete w.table_write_rates; renderRates(); changed(); break;
    case 'download-json': {
      const blob=new Blob([JSON.stringify(materialize(),null,2)+'\n'],{type:'application/json'}), url=URL.createObjectURL(blob);
      const a=document.createElement('a'); a.href=url; a.download='workload.json'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); break;
    }
    case 'analyze': launch('analysis'); break;
  }
});

document.addEventListener('input', e => {
  if (!state || busy) return;
  const el=e.target,d=el.dataset,w=state.workload;
  if (d.weight!==undefined) { w.profiles[+d.weight].weight=el.valueAsNumber; updateProfiles(); }
  if (d.frequencyProfile!==undefined) { w.profiles[+d.frequencyProfile].calls_per_hour[w.queries[+d.frequencyQuery].id]=el.valueAsNumber; updateProfiles(); }
  if (d.rate!==undefined) w.table_write_rates[Object.keys(w.table_write_rates)[+d.rate]][d.rateKey]=el.valueAsNumber;
  switch(el.id) {
    case 'write-source': state.writeSource=el.value; delete w.table_write_rates; delete w.table_write_rates_origin; renderRates(); if(tab==='json') renderJson(); break;
    case 'sql-editor': w.queries[queryIndex].sql=el.value; lineNumbers(); break;
    case 'launch-mode': updateSeriesSettings(); break;
    case 'series-budget': case 'series-values': case 'series-timeout': break;
    case 'storage': w.constraints.storage_budget_bytes=Math.round(el.valueAsNumber*1048576); $('storage-range').value=Math.min(256,el.valueAsNumber); break;
    case 'storage-range': w.constraints.storage_budget_bytes=el.valueAsNumber*1048576; $('storage').value=el.value; break;
    case 'max-indexes': w.constraints.max_indexes=el.valueAsNumber; break;
    case 'write-budget': w.constraints.write_budget_units_per_hour=el.valueAsNumber; break;
    case 'write-enabled': w.constraints.write_budget_units_per_hour=el.checked?+$('write-budget').value:null; $('write-budget').disabled=!el.checked; break;
    case 'objective': w.objective=el.value; break;
    case 'mode': w.analysis_mode=el.value; break;
    case 'ranking': w.candidate_ranking=el.value; break;
    case 'guards': guardsText=el.value; if(tab==='json') renderJson(); break;
    case 'manual': manualText=el.value; if(tab==='json') renderJson(); break;
  }
  changed();
});

document.addEventListener('change', async e => {
  if (!state || busy) return;
  const el=e.target,w=state.workload;
  if(el.id==='write-profile-file') {
    const file = el.files?.[0], targetState = state;
    if (!file) return;
    try {
      if (file.size > 1024*1024) throw new Error('Файл превышает 1 MiB.');
      const value = JSON.parse(await file.text());
      if (busy || state !== targetState || writeSource() !== 'observed') return;
      importWriteProfile(value); renderRates(); if(tab==='json') renderJson(); changed(); error('');
    } catch(e) { error(e.message); }
    el.value = '';
  }
  if(el.id==='query-id') {
    const old=w.queries[queryIndex].id, id=el.value.trim();
    if(!id || w.queries.some((q,i)=>i!==queryIndex&&q.id===id)) { error('ID запроса должен быть непустым и уникальным.'); el.value=old; return; }
    w.queries[queryIndex].id=id;
    w.profiles.forEach(p=>{ const value=p.calls_per_hour[old]; delete p.calls_per_hour[old]; p.calls_per_hour[id]=value; });
    if (guardsText.trim()) { try { const g=JSON.parse(guardsText); if(Object.hasOwn(g,old)){g[id]=g[old]; if(id!==old) delete g[old]; guardsText=JSON.stringify(g,null,2); $('guards').value=guardsText;} } catch {} }
    renderEditor(); error(''); changed();
  }
  if(el.dataset.profileId!==undefined) {
    const index=+el.dataset.profileId, id=el.value.trim();
    if(!id || w.profiles.some((p,i)=>i!==index&&p.id===id)) { error('ID профиля должен быть непустым и уникальным.'); el.value=w.profiles[index].id; return; }
    w.profiles[index].id=id; changed(); error('');
  }
});

document.addEventListener('keydown',e=>{
  if(e.target.matches('[role="tab"]') && ['ArrowLeft','ArrowRight','Home','End'].includes(e.key)) {
    const buttons=[...document.querySelectorAll('[role="tab"][data-tab]')], i=buttons.indexOf(e.target);
    const next=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowRight'?1:-1)+buttons.length)%buttons.length;
    e.preventDefault(); buttons[next].click(); buttons[next].focus();
  }
  if(e.target.tagName==='TEXTAREA' && e.key==='Tab') {
    e.preventDefault(); const t=e.target,start=t.selectionStart,end=t.selectionEnd;
    t.setRangeText('  ',start,end,'end'); t.dispatchEvent(new Event('input',{bubbles:true}));
  }
});

async function init() {
  try {
    templates=await api('/api/templates');
    let draft;
    try { draft=JSON.parse(localStorage.getItem(draftKey)); } catch {}
    if (draft?.seriesUI) for (const id of ['launch-mode','series-budget','series-values','series-timeout']) if (typeof draft.seriesUI[id] === 'string') $(id).value = draft.seriesUI[id];
    updateSeriesSettings();
    const restored = restoreDraft(draft, templates);
    if (restored) {
      ({state, guardsText, manualText} = restored); renderAll(); persist();
    } else loadTemplate(examplesTemplate()?.id || templates[0].id);
    $('workspace').disabled=false; $('analyze').disabled=false; $('start-tour').disabled=false;
    const id=new URLSearchParams(location.search).get('run');
    if(id) { activeJob=id; setBusy(true); poll(id); }
    else tour.maybeStart();
    health=await api('/api/health');
    $('connection').className='connection '+(health.database&&health.cli?'ready':'offline');
    $('connection').hidden=!!(health.database&&health.cli);
    $('connection').textContent=health.database&&health.cli
    ? 'PostgreSQL подключён · CLI готов · результаты сохраняются на сервере'
      : 'Редактор доступен. Для анализа '+[!health.database?'запустите demo-БД (инструкция в frontend/README.md)':'',!health.cli?'соберите CLI: ./gradlew installDist':''].filter(Boolean).join(' и ')+'.';
    document.querySelector('.local-badge').innerHTML = `<i></i> ${health.local_only ? 'Локальная среда' : 'Демонстрационный стенд'}`;
  } catch(e) { error(e.message); $('connection').hidden=false; $('connection').textContent='Не удалось подключиться к локальному серверу.'; }
}

const closeHelp = installHelp();
const tour = installTour();
window.addEventListener('beforeunload',()=>{if(state)persist();});
init();
