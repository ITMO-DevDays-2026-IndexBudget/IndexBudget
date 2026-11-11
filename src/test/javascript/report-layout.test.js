/* Saved data, responsive tables and local controls. Run after reportPreview, without a server. */
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');

const preview = name => pathToFileURL(resolve(`build/report-previews/${name}.html`)).href;
const fixture = name => JSON.parse(readFileSync(`build/report-previews/${name}.json`, 'utf8'));
const cases = ['fractional', 'one-profile', 'different', 'three-profiles', 'same', 'empty', 'regression',
  'zero-baseline', 'no-write', 'partial', 'failed', 'long-names', 'wide', 'recorded-import',
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
        if (saved.queries?.length) {
          // Visibility also detects a blocked script or an incorrect CSP hash.
          assert.ok(await page.locator('.query-controls').isVisible(), `${width}px/${name}: sorting unavailable`);
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
    assert.ok(await page.locator('#query-0').evaluate(details => details.open), 'SQL link reveals saved SQL');
    const original = await page.locator('#query-comparison tbody tr').evaluateAll(rows => rows.map(row => ({...row.dataset})));
    await page.selectOption('#query-sort', 'original');
    assert.deepEqual(await page.locator('#query-comparison tbody tr').evaluateAll(rows => rows.map(row => row.dataset.order)), ['0', '1']);
    await page.selectOption('#query-sort', 'regression');
    assert.equal(await page.locator('#query-comparison tbody tr').first().getAttribute('data-regression'), '1');
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
