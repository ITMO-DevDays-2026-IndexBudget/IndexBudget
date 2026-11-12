'use strict';
// Run against observed-smoke.py --serve. Input is an actual #107 workload artifact.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const base = process.env.FRONTEND_TEST_URL || 'http://127.0.0.1:8090';
const output = path.resolve('build/frontend-observed-ui');
(async () => {
  const observed = JSON.parse(await fs.readFile(process.env.OBSERVED_WORKLOAD, 'utf8'));
  await fs.mkdir(output, {recursive: true});
  const browser = await chromium.launch({headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => localStorage.setItem('indexbudget.tour.v1', 'done'));
    async function openForm() {
      await page.goto(base);
      await page.locator('#workspace:not([disabled])').waitFor();
      await page.locator('.advanced > summary').click();
    }
    async function upload(value) {
      await page.locator('#write-profile-file').setInputFiles({name: 'workload.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(value))});
      await page.waitForFunction(() => document.querySelector('#write-origin').textContent.includes('Наблюдение'));
    }
    await openForm();
    await page.locator('[data-template="04-scenarios"]').click();
    // Окно импорта содержит только records: удаляем JOIN на groups через редактор.
    await page.locator('[data-query="2"]').click();
    await page.locator('#delete-query').click();
    await page.locator('#write-source').selectOption('observed');
    await page.locator('#analyze').click();
    assert.match(await page.locator('#error').innerText(), /Профиль записи не задан/);
    const incomplete = structuredClone(observed);
    incomplete.table_write_rates_origin.complete = false;
    incomplete.table_write_rates_origin.reasons = ['INCOMPLETE_SNAPSHOT <script>alert(1)</script>'];
    await upload(incomplete);
    await page.locator('#write-enabled').check();
    await page.locator('#analyze').click();
    assert.match(await page.locator('#error').innerText(), /Наблюдение неполное/);
    assert.equal(await page.locator('#write-origin script').count(), 0);
    await upload(observed);
    await page.waitForFunction(() => document.querySelector('#write-origin').textContent.includes('Наблюдение полное'));
    assert.equal(await page.locator('[data-rate]').count(), 0);
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `overflow ${width}`);
      await page.screenshot({path: path.join(output, `observed-${width}.png`), fullPage: true});
    }
    await page.setViewportSize({width: 1440, height: 1000});
    await page.reload();
    await page.locator('#workspace:not([disabled])').waitFor();
    assert.equal(await page.locator('#write-source').inputValue(), 'observed');
    for (const mode of ['single', 'write']) {
      if (mode === 'write') {
        await openForm();
        await page.locator('#launch-mode').selectOption('series');
        await page.locator('#series-budget').selectOption('write_budget_units_per_hour');
        await page.locator('#series-values').fill('0, 10000, 1000000');
      }
      await page.locator('#mode').selectOption('normal');
      const responsePromise = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/runs'));
      await page.locator('#analyze').click();
      const response = await responsePromise;
      assert.equal(response.status(), 202);
      const request = response.request().postDataJSON();
      for (const key of ['table_write_rates', 'table_write_rates_origin']) assert.deepEqual(request.workload[key], observed[key]);
      await Promise.race([
        page.waitForURL(/\/reports\/[^/]+\/(?:report|demo-series)\.html$/, {timeout: 120000}),
        page.locator('#error:not([hidden])').waitFor({timeout: 120000}).then(async () => { throw new Error(await page.locator('#error').innerText()); })
      ]);
      if (page.url().endsWith('/demo-series.html')) await page.goto(page.url().replace('/demo-series.html', '/report.html'));
      assert.match(await page.locator('body').innerText(), /Наблюдаемый|наблюдаемый/);
      await page.locator('.write-profile > summary').first().click();
      assert.match(await page.locator('.write-profile').first().innerText(), new RegExp(observed.table_write_rates_origin.window.from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
      for (const width of [1440, 390, 320]) {
        await page.setViewportSize({width, height: 1000});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `${mode} report overflow ${width}`);
        await page.screenshot({path: path.join(output, `${mode}-${width}.png`), fullPage: true});
      }
      // A saved HTML still shows the full profile without any network or server.
      const html = await (await page.request.get(page.url())).text();
      const local = path.join(output, `${mode}.html`);
      await fs.writeFile(local, html);
      const offline = await browser.newPage();
      await offline.route('http://**/*', r => r.abort());
      await offline.route('https://**/*', r => r.abort());
      await offline.goto('file://' + local);
      assert.match(await offline.locator('.write-profile').first().textContent(), /Наблюдение полное/);
      await offline.close();
    }
    assert.deepEqual(errors, []);
    console.log('PASS observed browser: real import, incomplete/missing inputs, reload, single/write-series, 1440/390/320px and offline HTML');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
