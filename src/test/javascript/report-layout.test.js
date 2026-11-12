/* Saved data, responsive tables and local controls. Run after reportPreview, without a server. */
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');

const preview = name => pathToFileURL(resolve(`build/report-previews/${name}.html`)).href;
const fixture = name => JSON.parse(readFileSync(`build/report-previews/${name}.json`, 'utf8'));
const cases = ['fractional', 'one-profile', 'different', 'three-profiles', 'same', 'empty', 'regression',
  'zero-baseline', 'no-write', 'observed-write', 'incomplete-write', 'partial', 'failed', 'long-names', 'wide', 'recorded-import',
  'tiny-decrease', 'tiny-increase', 'recorded-series-0', 'recorded-series-1', 'recorded-series-2'];
const optionalNumber = value => value === '' || value === undefined ? null : Number(value);

async function noPageOverflow(page, context) {
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), context);
}

async function queryAgreement(page, saved, prefix = '') {
  const rows = await page.locator(`#${prefix}query-comparison tbody tr`).evaluateAll(elements =>
    elements.map(row => ({...row.dataset})));
  assert.equal(rows.length, saved.queries?.length ?? 0, 'Every input SQL has one comparison row');
  for (const row of rows) {
    const query = saved.queries[Number(row.order)];
    const effect = saved.query_effects.find(value => value.id === query.id)?.selected;
    assert.equal(optionalNumber(row.cost), query.selected_cost, `${query.id}: saved selected cost`);
    assert.equal(optionalNumber(row.gain), effect?.gain_percent ?? null, `${query.id}: saved gain`);
    assert.equal(row.regression, query.regression ? '1' : '0', `${query.id}: regression flag`);
  }
}

(async () => {
  const browser = await chromium.launch({executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined});
  try {
    const page = await browser.newPage();
    const errors = [];
    const network = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('request', request => { if (/^https?:/.test(request.url())) network.push(request.url()); });
    await page.context().setOffline(true);
    for (const width of [1440, 900, 390, 320]) {
      await page.setViewportSize({width, height: 900});
      for (const name of cases) {
        const saved = fixture(name);
        await page.goto(preview(name));
        await noPageOverflow(page, `${width}px/${name}: collapsed page overflow`);
        assert.equal(await page.locator('.profile-plot, .chart-track, .budget-meter').count(), 0,
          'Repeated charts and resource bars are replaced by saved-value tables');
        await queryAgreement(page, saved);
        assert.equal(await page.locator('.query-sort-button').count(), 4);
        assert.equal(await page.locator('h3').filter({hasText: 'Ограничения planner cost'}).count(), 0);
        if (saved.status === 'partial') {
          assert.equal(await page.locator('#overview .badge.partial').count(), 0);
          const limitation = page.locator('#overview .workload-context a[href="#search"]');
          await limitation.click();
          assert.ok(await page.locator('#search').evaluate(section => section.closest('details').open));
        }
        const groupedIndexes = await page.locator('#overview .recommended-table tbody tr').evaluateAll(groups =>
          groups.map(group => ({table: group.querySelector('th').textContent, count: group.querySelectorAll('li').length})));
        const selectedByTable = new Map();
        for (const candidate of saved.selected_indexes ?? []) {
          const table = candidate.definition.table;
          const ambiguous = saved.candidates.some(other => other.definition.table.name === table.name && other.definition.table.schema !== table.schema);
          const label = ambiguous ? `${table.schema}.${table.name}` : table.name;
          selectedByTable.set(label, (selectedByTable.get(label) ?? 0) + 1);
        }
        assert.deepEqual(new Map(groupedIndexes.map(group => [group.table, group.count])), selectedByTable,
          'Every selected index appears once in its table group');
        assert.equal(groupedIndexes.length, selectedByTable.size, 'Each table has one group');
        if (await page.locator('.resource-note').count()) {
          assert.equal(await page.locator('.resource-note').evaluate(e => getComputedStyle(e).fontSize),
            await page.locator('.resource-note + p').evaluate(e => getComputedStyle(e).fontSize),
            'Both resource notes use the same font size');
        }
        if (saved.queries?.length) {
          // Visibility also detects a blocked script or an incorrect CSP hash.
          assert.ok(await page.locator('.query-controls').isVisible(), `${width}px/${name}: sorting unavailable`);
          assert.equal(await page.locator('.query-legend').evaluate(e => getComputedStyle(e).fontSize),
            await page.locator('.query-controls label').evaluate(e => getComputedStyle(e).fontSize));
          assert.equal(await page.locator('.query-details-group').getAttribute('open'), null);
          assert.equal(await page.locator('#query-0 > summary').isVisible(), false,
            'Query disclosures are hidden inside the collapsed group');
        }
        const profileRows = await page.locator('.profile-table tr[data-profile-id]').evaluateAll(elements =>
          elements.map(row => ({...row.dataset})));
        const profiles = saved.profiles ?? [];
        assert.equal(profileRows.length, profiles.length > 1 ? profiles.length : 0,
          'One profile does not repeat the mean/worst summary; 2–3 keep their actual names');
        for (const row of profileRows) {
          const effect = saved.profile_comparisons.find(value => value.id === row.profileId)?.selected;
          assert.ok(effect, `${row.profileId}: profile must come from the saved input`);
          assert.equal(optionalNumber(row.baseline), effect.baseline_cost, 'Saved profile baseline');
          assert.equal(optionalNumber(row.cost), effect.cost, 'Saved profile selected cost');
          assert.equal(optionalNumber(row.gain), effect.gain_percent, 'Saved profile percent, including null/zero');
        }
        for (let index = 0; index < (saved.queries?.length ?? 0); index++) {
          assert.equal(await page.locator(`#copy-sql-${index}`).textContent(), saved.queries[index].sql,
            'Full SQL remains exact, including escaping and whitespace');
        }
        const idProblems = await page.evaluate(() => {
          const ids = [...document.querySelectorAll('[id]')].map(element => element.id);
          return {duplicates: ids.length - new Set(ids).size,
            missing: [...document.querySelectorAll('a[href^="#"]')].filter(a =>
              !document.getElementById(a.hash.slice(1))).map(a => a.hash)};
        });
        assert.deepEqual(idProblems, {duplicates: 0, missing: []}, `${name}: local anchors`);
        await page.locator('details').evaluateAll(elements => elements.forEach(details => { details.open = true; }));
        await noPageOverflow(page, `${width}px/${name}: expanded details overflow`);
      }
      console.log(`Offline Chromium: saved values and ${cases.length} collapsed/expanded reports at ${width}px passed.`);
    }
    await page.goto(preview('regression'));
    const firstDisclosure = page.locator('details > summary').first();
    await firstDisclosure.focus();
    await page.keyboard.press('Enter');
    assert.ok(await firstDisclosure.evaluate(summary => summary.parentElement.open), 'Enter opens native details');
    await page.keyboard.press('Space');
    assert.ok(await firstDisclosure.evaluate(summary => !summary.parentElement.open), 'Space closes native details');
    await page.locator('a[href="#query-0"]').first().click();
    assert.ok(await page.locator('.query-details-group').evaluate(details => details.open), 'SQL link opens the parent group');
    assert.ok(await page.locator('#query-0').evaluate(details => details.open), 'SQL link reveals saved SQL');
    const original = await page.locator('#query-comparison tbody tr').evaluateAll(rows => rows.map(row => ({...row.dataset})));
    await page.selectOption('#query-sort', 'original');
    assert.deepEqual(await page.locator('#query-comparison tbody tr').evaluateAll(rows => rows.map(row => row.dataset.order)), ['0', '1']);
    await page.selectOption('#query-sort', 'regression');
    assert.equal(await page.locator('#query-comparison tbody tr').first().getAttribute('data-regression'), '1');
    for (const key of ['order', 'before', 'cost', 'gain']) {
      const header = page.locator(`#query-comparison th[data-sort-key="${key}"]`);
      for (const direction of [key === 'order' ? 'ascending' : 'descending', key === 'order' ? 'descending' : 'ascending']) {
        await header.locator('button').click();
        assert.equal(await header.getAttribute('aria-sort'), direction);
        const values = await page.locator('#query-comparison tbody tr').evaluateAll(
          (rows, key) => rows.map(row => row.dataset[key]), key);
        const known = values.filter(value => value !== '').map(Number);
        assert.deepEqual(known, [...known].sort((a, b) => direction === 'ascending' ? a - b : b - a));
        assert.ok(values.slice(known.length).every(value => value === ''), 'Unknown values stay last');
      }
    }
    await page.selectOption('#query-sort', 'original');
    assert.equal(await page.locator('th[data-sort-key="order"]').getAttribute('aria-sort'), 'ascending');
    const after = await page.locator('#query-comparison tbody tr').evaluateAll(rows => rows.map(row => ({...row.dataset})));
    const byOrder = (a, b) => Number(a.order) - Number(b.order);
    assert.deepEqual(after.sort(byOrder), original.sort(byOrder), 'View controls do not mutate saved data');
    const copyButton = page.locator('button[data-copy="copy-sql-0"]');
    await copyButton.click();
    await page.waitForFunction(() => /Скопировано|Текст выделен/.test(
      document.querySelector('button[data-copy="copy-sql-0"]').nextElementSibling.textContent));
    assert.match(await copyButton.evaluate(button => button.nextElementSibling.textContent), /Скопировано|Текст выделен/,
      'Copy success or the offline manual fallback is announced');
    const fallback = await browser.newPage({javaScriptEnabled: false, viewport: {width: 390, height: 900}});
    await fallback.context().setOffline(true);
    await fallback.goto(preview('regression'));
    await noPageOverflow(fallback, 'No-JS report overflows');
    assert.equal(await fallback.locator('#query-comparison tbody tr').first().getAttribute('data-regression'), '1');
    await fallback.locator('.query-details-group > summary').click();
    await fallback.locator('#query-0 > summary').click();
    assert.ok(await fallback.locator('#query-0').evaluate(details => details.open), 'No-JS SQL details remain available');
    await fallback.close();
    assert.deepEqual(network, [], 'No network resources');
    assert.deepEqual(errors, [], 'No runtime or CSP errors');
    console.log('Offline Chromium: keyboard, exact SQL, sorting, copy, no-JS and anchors passed.');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
