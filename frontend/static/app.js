'use strict';

const $ = id => document.getElementById(id);
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const clone = value => JSON.parse(JSON.stringify(value));
const format = value => new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 2}).format(value);
const draftKey = 'indexbudget.frontend.v1';
let templates = [], state, tab = 'queries', queryIndex = 0, busy = false, health = {};
let guardsText = '', manualText = '', activeJob = null, saveTimer;

const helpTexts = {
  storage: ['Бюджет места', 'Лимит только для новых индексов: оценка размера учитывается с запасом 20%. При нуле получите исходный план без новых индексов.'],
  write: ['Бюджет записи', 'Условные единицы: изменения строк × число колонок индекса, включая INCLUDE. Выключено — без ограничения; ноль — нулевой бюджет, а не отключение.'],
  objective: ['Robust или mean?', 'Robust выбирает набор по худшему отношению стоимости сценария к исходной. Mean — по отношению средних стоимостей с учётом весов.'],
  mode: ['Глубина анализа', 'Быстрый режим — для первого эксперимента; глубокий даёт поиску больше времени и попыток, но не обещает лучший набор. При достижении лимита пригодный результат сохраняется как частичный.'],
  profiles: ['Частота и вес', 'Частота — сколько раз SQL вызывается за час внутри сценария. Вес — доля самого сценария: веса 9 и 1 означают 90% и 10%; в robust учитываются оба.'],
  schema: ['Что меняет схема', 'DDL и заполнение действительно выполняются в отдельной demo-БД перед анализом. После изменения колонок обновите SQL и данные; «Проверить схему» обновит диаграмму.']
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
    {target:'#templates', title:'Начните с демо-базы', text:'Каждый шаблон содержит настоящие данные, схему и готовую нагрузку. Выбор заполнит форму — дальше всё можно менять.'},
    {target:'.editor-panel', tab:'queries', title:'Опишите запросы', text:'Здесь редактируются общие SQL: можно добавить запрос или удалить лишний. Вкладка JSON показывает точный вход анализа и позволяет его скачать.'},
    {target:'.editor-panel', tab:'profiles', title:'Задайте сценарии нагрузки', text:'Частоты — вызовы SQL за час внутри сценария. Веса задают долю сценариев: например, обычный день и распродажа.'},
    {target:'.editor-panel', tab:'schema', title:'Измените схему и данные', text:'DDL и заполнение выполняются в отдельной demo-БД перед анализом. «Проверить схему» применяет изменения и обновляет диаграмму справа.'},
    {target:'.schema-panel', title:'Посмотрите на связи', text:'На карте — таблицы, колонки, внешние ключи и существующие индексы. Подпись сообщает, показан ли шаблон, проверенная схема или устаревшая версия.'},
    {target:'.budget-grid', title:'Ограничьте новые индексы', text:'Укажите доступное место, максимальное число индексов и, при необходимости, бюджет записи. Существующие индексы уже входят в исходный план.'},
    {target:'.strategy-grid', title:'Выберите цель и глубину', text:'Robust учитывает худший сценарий, mean — средний с учётом весов. Глубина задаёт ресурсы поиска; больше времени не гарантирует лучший результат.'},
    {target:'.advanced', advanced:true, title:'Тонкая настройка — по желанию', text:'Здесь доступны интенсивность записи, рейтинг кандидатов, ограничения ухудшения запросов и ручные индексы для сравнения. Для первого запуска достаточно значений шаблона.'},
    {target:'.launch-bar', title:'Запустите эксперимент', text:'После подготовки базы начнётся реальный анализ. Готовый отчёт откроется автоматически; частичный результат тоже сохранится с объяснением причин остановки.'}
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
    localStorage.setItem(draftKey, JSON.stringify({state, guardsText, manualText}));
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

function loadTemplate(id) {
  const t = templates.find(t => t.id === id);
  state = {template_id: t.id, template_version: t.version, schema_sql: t.schema_sql,
    seed_sql: t.seed_sql, workload: clone(t.workload), catalog: clone(t.catalog),
    catalogSource: 'template', catalogSchema: t.schema_sql, catalogSeed: t.seed_sql};
  guardsText = ''; manualText = ''; queryIndex = 0;
  error(''); renderAll(); persist();
}

function templateIcon(kind) {
  const paths = kind === 'sand'
    ? '<rect x="2" y="3" width="8" height="7" rx="1"/><rect x="14" y="14" width="8" height="7" rx="1"/><path d="M6 10v7h8M10 6h8v8"/>'
    : kind === 'lavender' ? '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 9v12M15 9v12M3 15h18"/>'
    : '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 4 16 4 16 0V5M4 12v7c0 4 16 4 16 0v-7"/>';
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true">${paths}</svg>`;
}

function renderTemplates() {
  $('templates').innerHTML = templates.map(t => `<button type="button" class="template-card ${state.template_id === t.id ? 'selected' : ''}" data-template="${escape(t.id)}" aria-pressed="${state.template_id === t.id}">
    <div class="template-top"><span class="template-icon ${escape(t.accent)}">${templateIcon(t.accent)}</span><span class="template-tag">${escape(t.tag)}</span></div>
    <h3>${escape(t.name)}</h3><p>${escape(t.description)}</p><div class="template-bottom"><span>${t.catalog.tables.length} табл.</span><span>${t.workload.queries.length} SQL</span><span>${t.workload.profiles.length} проф.</span></div>${state.template_id === t.id ? '<span class="selected-check" aria-hidden="true">✓</span>' : ''}</button>`).join('');
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
  renderRates(); updateObjective();
}

function updateObjective() {
  $('objective-note').textContent = state.workload.objective === 'mean'
    ? 'Учитываем относительные веса сценариев'
    : 'Учитываем каждый сценарий независимо от его веса';
}

function renderRates() {
  const rows = Object.entries(state.workload.table_write_rates || {});
  $('write-rates').innerHTML = `${rows.length ? '<div class="rate-row rate-head"><span>Таблица</span><span>INSERT</span><span>UPDATE</span><span>DELETE</span><span></span></div>' : '<p class="field-note">Профиль записи не задан. При включённом бюджете нужны данные для всех таблиц запросов.</p>'}
    ${rows.map(([name, rate], i) => `<div class="rate-row"><span>${escape(name)}</span>${['inserts_per_hour','updates_per_hour','deletes_per_hour'].map(k => `<input type="number" min="0" step="any" value="${rate[k]}" data-rate="${i}" data-rate-key="${k}" aria-label="${escape(name)} ${k}">`).join('')}<button type="button" data-remove-rate="${i}" aria-label="Удалить интенсивность записи ${escape(name)}">×</button></div>`).join('')}
    <div class="rate-actions"><button type="button" class="quiet-button" id="add-rate">+ Таблица</button><button type="button" class="text-button" id="clear-rates">Очистить профиль записи</button></div>`;
}

function renderEditor() {
  closeHelp();
  $('query-count').textContent = state.workload.queries.length;
  document.querySelectorAll('[data-tab]').forEach(b => {
    b.setAttribute('aria-selected', b.dataset.tab === tab);
    b.tabIndex = b.dataset.tab === tab ? 0 : -1;
  });
  $('editor-content').setAttribute('aria-labelledby', `tab-${tab}`);
  ({queries: renderQueries, profiles: renderProfiles, schema: renderSchema, json: renderJson})[tab]();
}

function renderQueries() {
  const queries = state.workload.queries;
  queryIndex = Math.min(queryIndex, queries.length - 1);
  const q = queries[queryIndex];
  $('editor-content').innerHTML = `<div class="editor-toolbar"><div class="query-tabs">${queries.map((q,i) => `<button type="button" class="query-tab ${i===queryIndex ? 'active' : ''}" data-query="${i}" title="${escape(q.id)}" aria-pressed="${i===queryIndex}"><span>Q${String(i+1).padStart(2,'0')}</span> ${escape(q.id.replace(/^(records_|orders_|wide_)/,''))}</button>`).join('')}</div><button type="button" id="add-query" class="quiet-button" ${queries.length>=20?'disabled':''} aria-label="Добавить запрос">+ SQL</button></div>
    <div class="editor-meta"><label for="query-id">ID</label><input id="query-id" value="${escape(q.id)}" required><button type="button" id="delete-query" class="delete-button" ${queries.length<=1?'disabled':''}>Удалить запрос</button></div>
    <div class="code-editor"><div class="line-numbers" aria-hidden="true" id="line-numbers"></div><textarea id="sql-editor" spellcheck="false" aria-label="SQL запроса ${escape(q.id)}" required>${escape(q.sql)}</textarea></div>
    <div class="editor-footnote"><span>SELECT · без выполнения запроса · полный SQL передаётся в EXPLAIN</span><span class="dialect">POSTGRESQL</span></div>`;
  lineNumbers();
  $('sql-editor').addEventListener('scroll', () => $('line-numbers').scrollTop = $('sql-editor').scrollTop);
}

function lineNumbers() {
  $('line-numbers').textContent = Array.from({length: Math.max(8, $('sql-editor').value.split('\n').length)}, (_,i) => i+1).join('\n');
}

function renderProfiles() {
  const {queries, profiles} = state.workload;
  $('editor-content').innerHTML = `<div class="profile-content"><p>Одни запросы, разные режимы работы. Частоты задаются в вызовах в час.</p><div class="profile-table-wrap"><table class="profile-table"><thead><tr><th>Запрос / профиль</th>${profiles.map((p,i) => `<th><div class="profile-name"><input value="${escape(p.id)}" data-profile-id="${i}" aria-label="ID профиля ${i+1}" required><button type="button" data-delete-profile="${i}" ${profiles.length===1?'disabled':''} aria-label="Удалить профиль ${escape(p.id)}">×</button></div></th>`).join('')}</tr></thead><tbody>
    <tr><td>Вес сценария</td>${profiles.map((p,i) => `<td><input type="number" min="0" step="any" value="${p.weight}" data-weight="${i}" aria-label="Вес ${escape(p.id)}" required><div class="profile-share" data-share="${i}"></div></td>`).join('')}</tr>
    ${queries.map((q,qi) => `<tr><td>${escape(q.id)}</td>${profiles.map((p,pi) => `<td><input type="number" min="0" step="any" value="${p.calls_per_hour[q.id]}" data-frequency-profile="${pi}" data-frequency-query="${qi}" aria-label="Частота ${escape(q.id)} в ${escape(p.id)}" required></td>`).join('')}</tr>`).join('')}</tbody><tfoot><tr><td>Всего вызовов/ч</td>${profiles.map((p,i)=>`<td data-total="${i}"></td>`).join('')}</tr></tfoot></table></div><div class="profile-actions"><button type="button" id="add-profile" class="quiet-button" ${profiles.length>=3?'disabled':''}>+ Профиль нагрузки</button></div><p class="profile-note">Вес задаёт относительную долю сценария для среднего результата. В robust каждый сценарий участвует в худшей оценке, даже с маленьким весом.</p></div>`;
  const intro = $('editor-content').querySelector('.profile-content > p');
  intro.insertAdjacentHTML('beforeend', ' ' + helpButton('profiles'));
  updateProfiles();
}

function updateProfiles() {
  const profiles = state.workload.profiles, sum = profiles.reduce((s,p) => s + p.weight, 0);
  document.querySelectorAll('[data-share]').forEach(e => e.textContent = sum > 0 ? format(profiles[+e.dataset.share].weight/sum*100) + '% от времени' : 'Задайте веса');
  document.querySelectorAll('[data-total]').forEach(e => e.textContent = format(Object.values(profiles[+e.dataset.total].calls_per_hour).reduce((a,b)=>a+b,0)));
}

function renderSchema() {
  $('editor-content').innerHTML = `<div class="schema-content"><p>Подготовка выполняется в отдельной локальной базе. Схема и данные пересоздаются перед каждым анализом.</p><label for="schema-sql">Схема и существующие индексы · DDL</label><textarea id="schema-sql" class="code" spellcheck="false" required>${escape(state.schema_sql)}</textarea><label for="seed-sql">Заполнение данными · SQL</label><textarea id="seed-sql" class="code" spellcheck="false" required>${escape(state.seed_sql)}</textarea><div class="schema-actions"><button type="button" id="preview-schema" class="quiet-button">Проверить схему</button><span class="hint">Обновит диаграмму из PostgreSQL</span></div><p class="field-note">После изменения колонок обновите запросы, заполнение и профиль записи. ANALYZE статистики выполняется на этапе подготовки.</p></div>`;
  const intro = $('editor-content').querySelector('.schema-content > p');
  intro.insertAdjacentHTML('beforeend', ' ' + helpButton('schema'));
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
  return w;
}

function renderJson() {
  let text, valid = true;
  try { text = JSON.stringify(materialize(), null, 2); } catch (e) { text = e.message; valid = false; }
  $('editor-content').innerHTML = `<div class="json-content"><p>Именно этот workload JSON v2 получит анализатор. DDL и заполнение сохраняются отдельно в конфигурации демо.</p><div class="json-toolbar"><button type="button" id="download-json" class="quiet-button" ${valid?'':'disabled'}>↓ Скачать JSON</button><span class="hint">${valid ? format(new TextEncoder().encode(text).length) + ' байт' : 'Исправьте дополнительные параметры'}</span></div><pre>${escape(text)}</pre></div>`;
}

function renderDiagram() {
  const catalog = state.catalog;
  const stale = state.schema_sql !== state.catalogSchema || state.seed_sql !== state.catalogSeed;
  $('schema-source').textContent = stale ? '● Схема изменена · требуется проверка' : state.catalogSource === 'database' ? '● Проверено в локальном PostgreSQL' : '● Структура шаблона · до проверки в БД';
  $('schema-source').classList.toggle('stale', stale);
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
  const items = [['Общих SQL',w.queries.length],['Профилей нагрузки',w.profiles.length],['Бюджет места',format(w.constraints.storage_budget_bytes/1048576)+' MiB'],['Цель',w.objective==='mean'?'Средний результат':'Худший сценарий']];
  $('summary').innerHTML = items.map(([k,v])=>`<div><dt>${escape(k)}</dt><dd>${escape(v)}</dd></div>`).join('');
}

function requestSnapshot() {
  const invalid = [...$('workspace').querySelectorAll('input,textarea,select')].find(e=>!e.disabled && !e.checkValidity());
  if (invalid) { invalid.reportValidity(); throw new Error('Проверьте выделенное поле.'); }
  return {template_id: state.template_id, template_version: state.template_version,
    schema_sql: state.schema_sql, seed_sql: state.seed_sql, workload: materialize()};
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

async function launch(kind) {
  if (busy) return;
  let request;
  try { request = requestSnapshot(); } catch(e) { error(e.message); return; }
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
  const details = {queued:`Позиция в очереди: ${job.queue_position || 1}. База используется одним запуском за раз.`,preparing:'Создаём схему, загружаем данные и собираем статистику.',analyzing:'CLI оценивает наборы гипотетических индексов через EXPLAIN.',publishing:'Сохраняем HTML, JSON и SQL в отдельный каталог.',completed:job.kind==='schema'?'Диаграмма обновлена из каталога PostgreSQL.':'Открываем готовый отчёт.',partial:'Открываем отчёт с причинами остановки.',failed:'Введённые настройки сохранены. Исправьте ошибку и повторите запуск.'};
  $('run-title').textContent = labels[job.status];
  $('run-description').textContent = details[job.status];
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
      if (failures >= 5) { error('Не удалось получить состояние запуска. Обновите страницу: наблюдение восстановится, повторный анализ не отправляется.'); return; }
      await new Promise(resolve=>setTimeout(resolve,2000)); continue;
    }
    showProgress(job);
    if (['completed','partial','failed'].includes(job.status)) {
      activeJob = null; setBusy(false);
      history.replaceState(null,'','/');
      if (job.catalog) {
        state.catalog = job.catalog; state.catalogSource = 'database';
        state.catalogSchema = state.schema_sql; state.catalogSeed = state.seed_sql;
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
  if (d.template) { loadTemplate(d.template); return; }
  if (d.tab) { tab=d.tab; renderEditor(); return; }
  if (d.query !== undefined) { queryIndex=+d.query; renderEditor(); return; }
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
    case 'preview-schema': launch('schema'); break;
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
    case 'sql-editor': w.queries[queryIndex].sql=el.value; lineNumbers(); break;
    case 'schema-sql': state.schema_sql=el.value; renderDiagram(); break;
    case 'seed-sql': state.seed_sql=el.value; renderDiagram(); break;
    case 'storage': w.constraints.storage_budget_bytes=Math.round(el.valueAsNumber*1048576); $('storage-range').value=Math.min(256,el.valueAsNumber); break;
    case 'storage-range': w.constraints.storage_budget_bytes=el.valueAsNumber*1048576; $('storage').value=el.value; break;
    case 'max-indexes': w.constraints.max_indexes=el.valueAsNumber; break;
    case 'write-budget': w.constraints.write_budget_units_per_hour=el.valueAsNumber; break;
    case 'write-enabled': w.constraints.write_budget_units_per_hour=el.checked?+$('write-budget').value:null; $('write-budget').disabled=!el.checked; break;
    case 'objective': w.objective=el.value; updateObjective(); break;
    case 'mode': w.analysis_mode=el.value; break;
    case 'ranking': w.candidate_ranking=el.value; break;
    case 'guards': guardsText=el.value; if(tab==='json') renderJson(); break;
    case 'manual': manualText=el.value; if(tab==='json') renderJson(); break;
  }
  changed();
});

document.addEventListener('change', e => {
  if (!state || busy) return;
  const el=e.target,w=state.workload;
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
    const buttons=[...document.querySelectorAll('[data-tab]')], i=buttons.indexOf(e.target);
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
    if(draft?.state && templates.some(t=>t.id===draft.state.template_id&&t.version===draft.state.template_version)) {
      state=draft.state; guardsText=draft.guardsText||''; manualText=draft.manualText||''; renderAll();
    } else loadTemplate(templates[0].id);
    $('workspace').disabled=false; $('analyze').disabled=false; $('start-tour').disabled=false;
    const id=new URLSearchParams(location.search).get('run');
    if(id) { activeJob=id; setBusy(true); poll(id); }
    else tour.maybeStart();
    health=await api('/api/health');
    $('connection').className='connection '+(health.database&&health.cli?'ready':'offline');
    $('connection').textContent=health.database&&health.cli
    ? 'PostgreSQL подключён · CLI готов · результаты сохраняются на сервере'
      : 'Редактор доступен. Для анализа '+[!health.database?'запустите demo-БД (инструкция в frontend/README.md)':'',!health.cli?'соберите CLI: ./gradlew installDist':''].filter(Boolean).join(' и ')+'.';
    document.querySelector('.local-badge').innerHTML = `<i></i> ${health.local_only ? 'Локальная среда' : 'Демонстрационный стенд'}`;
  } catch(e) { error(e.message); $('connection').textContent='Не удалось подключиться к локальному серверу.'; }
}

const closeHelp = installHelp();
const tour = installTour();
window.addEventListener('beforeunload',()=>{if(state)persist();});
init();
