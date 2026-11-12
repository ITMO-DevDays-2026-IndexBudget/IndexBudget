'use strict';

// Опциональная проверка реального UI: нужен запущенный frontend и Playwright.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const base = process.env.FRONTEND_TEST_URL || 'http://127.0.0.1:8090';
const output = path.resolve('build/frontend-ui-verification');

(async () => {
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base);
    await page.locator('#workspace:not([disabled])').waitFor();
    await page.locator('[data-tour="skip"]').click();
    const templates = await (await page.request.get(base + '/api/templates')).json();
    const scenario = templates.find(t => t.id === '04-scenarios');
    assert.ok(scenario && scenario.examples.length === 9);
    assert.equal(await page.locator('[data-template="04-scenarios"]').getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#templates > button').first().getAttribute('data-template'), '04-scenarios');
    await page.locator('#tab-queries').focus();
    await page.keyboard.press('End');
    assert.equal(await page.locator('#tab-json').evaluate(el => el === document.activeElement), true);
    await page.keyboard.press('Home');
    assert.equal(await page.locator('#tab-queries').evaluate(el => el === document.activeElement), true);
    assert.equal(await page.locator('.query-library-link button').getAttribute('role'), null);
    assert.equal(await page.locator('#templates > button').count(), 5);
    const sql = '\nSELECT id FROM public.records WHERE group_id = 42;  \n';
    const draft = { state: { template_id: templates[0].id, template_version: templates[0].version,
      workload: structuredClone(templates[0].workload), schema_sql: 'CREATE TABLE injected(id int)',
      seed_sql: 'SELECT injected()', catalog: { tables: [{ name: 'injected' }] }, catalogSource: 'database' } };
    draft.state.workload.queries[0].sql = sql;
    delete draft.state.workload.index_methods;
    // Старый черновик должен быть на диске до bootstrap; beforeunload текущего UI сохраняет свою форму.
    await page.addInitScript(draft => {
      if (!sessionStorage.getItem('legacy-draft-test')) {
        localStorage.setItem('indexbudget.frontend.v1', JSON.stringify(draft));
        sessionStorage.setItem('legacy-draft-test', 'done');
      }
    }, draft);
    await page.reload();
    await page.locator('#workspace:not([disabled])').waitFor();
    assert.equal(await page.locator('#sql-editor').inputValue(), sql);
    assert.equal(await page.locator('.schema-panel').count(), 0);
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('indexbudget.frontend.v1')));
    assert.deepEqual(Object.keys(saved.state).sort(), ['template_id', 'template_version', 'workload']);
    assert.deepEqual(saved.state.workload.index_methods, ['btree', 'brin', 'hash']);
    await page.locator('#tab-schema').click();
    assert.equal(await page.locator('#schema-sql, #seed-sql, .schema-content textarea').count(), 0);
    assert.equal(await page.locator('#preview-schema').count(), 0);
    assert.match(await page.locator('.schema-content pre').first().innerText(), /CREATE TABLE/);
    assert.doesNotMatch(await page.locator('.schema-content').innerText(), /injected/);
    await page.locator('#show-diagram').click();
    assert.equal(await page.locator('#schema-dialog').evaluate(el => el.open), true);
    await page.locator('#schema-diagram-image').evaluate(img => img.decode());
    assert.ok((await page.locator('#schema-diagram-image').getAttribute('src')).endsWith(`${templates[0].id}-v${templates[0].version}.svg`));
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#schema-dialog').evaluate(el => el.open), false);
    assert.equal(await page.locator('#show-diagram').evaluate(el => el === document.activeElement), true);
    await page.screenshot({ path: path.join(output, 'schema-1440.png'), fullPage: true });
    await page.locator('#tab-profiles').click();
    const frequency = page.locator('[data-frequency-profile="0"][data-frequency-query="0"]');
    assert.equal(await frequency.getAttribute('max'), '1000000');
    assert.equal(await frequency.getAttribute('step'), '1');
    let posts = 0;
    page.on('request', request => { if (request.method() === 'POST') posts++; });
    for (const value of ['-1', '0.5', '1000001', '']) {
      await frequency.fill(value);
      await page.locator('#tab-queries').click();
      await page.locator('#analyze').click();
      assert.match(await page.locator('#error').innerText(), /целым числом/);
      assert.equal(posts, 0);
      await page.locator('#tab-profiles').click();
    }
    await frequency.fill('1000000');
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.screenshot({ path: path.join(output, `profiles-${width}.png`), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true,
        `Horizontal page overflow at ${width}px`);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.locator('#tab-json').click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download-json').click();
    const download = await downloadPromise;
    const downloaded = JSON.parse(await fs.readFile(await download.path(), 'utf8'));
    assert.equal(downloaded.queries[0].sql, sql);
    assert.deepEqual(downloaded.index_methods, ['btree', 'brin', 'hash']);
    assert.equal(downloaded.profiles[0].calls_per_hour[downloaded.queries[0].id], 1000000);
    await page.locator('#reset').click();
    await page.locator('#tab-queries').click();
    assert.equal(await page.locator('#sql-editor').inputValue(), templates[0].workload.queries[0].sql);
    // Примеры привязаны к выбранной базе; чужой JOIN/DML в её библиотеке не появляется.
    await page.locator('.query-library-link [data-tab="examples"]').click();
    await page.locator('[data-template="02-shop"]').click();
    assert.equal(await page.locator('.example-item').count(), 12);
    assert.equal(await page.locator('[data-add-example="update_payload"]').count(), 0);
    await page.locator('[data-template="04-scenarios"]').click();
    assert.equal(await page.locator('.example-item').count(), 9);
    assert.equal(await page.locator('[data-add-example="records_by_group"]').isDisabled(), true);
    await page.locator('#tab-queries').click();
    const editedSql = 'SELECT id FROM public.records WHERE id = 42';
    await page.locator('#sql-editor').fill(editedSql);
    await page.locator('#tab-examples').click();
    await page.locator('[data-add-example="records_by_group"]').click();
    assert.match(await page.locator('#example-status').innerText(), /ранее изменённый запрос сохранён/);
    await page.locator('#tab-queries').click();
    assert.equal(await page.locator('#query-id').inputValue(), 'records_by_group_2');
    await page.locator('[data-query="0"]').click();
    assert.equal(await page.locator('#sql-editor').inputValue(), editedSql);
    await page.locator('#reset').click();
    await page.locator('#tab-examples').click();
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => { document.activeElement.blur(); window.scrollTo(0, 0); });
      await page.screenshot({ path: path.join(output, `examples-${width}.png`), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true,
        `Examples horizontal page overflow at ${width}px`);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    for (const example of scenario.examples) {
      const add = page.locator(`[data-add-example="${example.id}"]`);
      if (!await add.isDisabled()) await add.click();
    }
    assert.equal(await page.locator('#query-count').innerText(), '9');
    assert.equal(await page.locator('.add-example:disabled').count(), 9);
    const updateDetails = page.locator('[data-example-id="update_payload"] details');
    await updateDetails.locator('summary').click();
    assert.equal(await updateDetails.locator('pre').innerText(), scenario.examples.find(e => e.id === 'update_payload').sql);
    await page.locator('#tab-json').click();
    const selected = JSON.parse(await page.locator('.json-content > pre').innerText());
    assert.equal(selected.queries.length, 9);
    for (const example of scenario.examples) assert.deepEqual(selected.queries.find(q => q.id === example.id), {id: example.id, sql: example.sql});
    assert.deepEqual(selected.constraints.query_cost_guards, scenario.workload.constraints.query_cost_guards);
    assert.deepEqual(selected.manual_indexes, scenario.workload.manual_indexes);
    assert.deepEqual(selected.table_write_rates, scenario.workload.table_write_rates);
    assert.equal(selected.profiles.find(p => p.id === 'peak').calls_per_hour.update_payload, 120);
    await page.locator('#mode').selectOption('fast');
    const requestPromise = page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith('/api/runs'));
    await page.locator('#analyze').click();
    const submitted = (await requestPromise).postDataJSON();
    assert.deepEqual(Object.keys(submitted).sort(),
      ['template_id', 'template_version', 'workload']);
    assert.equal(submitted.template_id, '04-scenarios');
    assert.equal(submitted.workload.queries.length, 9);
    await page.waitForURL('**/reports/*/report.html', { timeout: 120000 });
    assert.match(await page.title(), /IndexBudget/);
    const firstReport = await (await page.request.get(page.url().replace(/report\.html$/, 'report.json'))).json();
    assert.ok(firstReport.selected_indexes.some(index => index.definition.method === 'btree'));
    for (const index of firstReport.selected_indexes) {
      assert.match(await page.locator(`[data-selected-index="${index.id}"] h3`).innerText(),
        new RegExp(` · ${index.definition.method} \\(`));
    }
    assert.match(await page.locator('.recommended-indexes').innerText(), / · btree \(/);
    await page.goto(base);
    await page.locator('#workspace:not([disabled])').waitFor();
    // После редактирования SQL-библиотеки подтверждаем смену датасета.
    page.once('dialog', dialog => dialog.accept());
    await page.locator('[data-template="04-events-brin"]').click();
    assert.equal(await page.locator('#storage').inputValue(), '0.0625');
    assert.match(await page.locator('#summary').innerText(), /B-tree, BRIN, Hash/);
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.screenshot({ path: path.join(output, `events-${width}.png`), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
    await page.locator('#analyze').click();
    await page.waitForURL('**/reports/*/report.html', { timeout: 120000 });
    const report = await (await page.request.get(page.url().replace(/report\.html$/, 'report.json'))).json();
    assert.deepEqual(report.index_methods, ['btree', 'brin', 'hash']);
    assert.deepEqual(report.selected_indexes.map(index => index.definition.method), ['brin']);
    await page.goto(base);
    await page.locator('#workspace:not([disabled])').waitFor();
    await page.locator('[data-template="05-lookup-hash"]').click();
    assert.equal(await page.locator('#storage').inputValue(), '16');
    assert.equal(await page.locator('#max-indexes').inputValue(), '2');
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.screenshot({ path: path.join(output, `hash-${width}.png`), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
    await page.locator('#analyze').click();
    await page.waitForURL('**/reports/*/report.html', { timeout: 120000 });
    const hashReport = await (await page.request.get(page.url().replace(/report\.html$/, 'report.json'))).json();
    assert.deepEqual(hashReport.index_methods, ['btree', 'brin', 'hash']);
    assert.deepEqual(hashReport.selected_indexes.map(index => index.definition.method).sort(), ['btree', 'hash']);
    assert.equal(await page.locator('[data-selected-index]').count(), 2);
    assert.match(await page.locator('.recommended-indexes').innerText(), / · btree \(created_at\)/);
    assert.match(await page.locator('.recommended-indexes').innerText(), / · hash \(external_key\)/);
    assert.deepEqual(errors, []);
    console.log('PASS browser: legacy methods, fixed schema, named SQL/DML examples, frequencies, JSON, reset, real reports, BRIN/Hash datasets; 1440/390/320px');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
