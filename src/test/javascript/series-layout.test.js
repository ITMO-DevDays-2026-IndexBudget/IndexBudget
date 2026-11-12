/* Offline table agreement, shared catalogs and navigation after relocating the whole series. */
const assert = require('node:assert/strict');
const {cpSync, copyFileSync, mkdtempSync, readFileSync, existsSync, rmSync} = require('node:fs');
const {resolve, join} = require('node:path');
const {tmpdir} = require('node:os');
const {pathToFileURL, fileURLToPath} = require('node:url');
const {chromium} = require('playwright');

const optionalNumber = value => value === '' || value === undefined ? null : Number(value);
const canonical = value => JSON.stringify(normalize(value));
function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, normalize(value[key])]));
  return value;
}
function savedDdl(value, result = new Set()) {
  if (Array.isArray(value)) value.forEach(child => savedDdl(child, result));
  else if (value && typeof value === 'object') {
    if (typeof value.ddl === 'string') result.add(value.ddl);
    Object.values(value).forEach(child => savedDdl(child, result));
  }
  return result;
}
async function assertCatalogs(page, reports) {
  const sql = await page.locator('pre[data-query-sql]').evaluateAll(elements => elements.map(element => ({
    id: element.closest('details.query-detail').querySelector(':scope > summary > code').textContent,
    sql: element.textContent,
  })));
  const sqlKeys = sql.map(query => JSON.stringify([query.id, query.sql]));
  const expectedSql = new Set(reports.flatMap(report => report.queries ?? []).filter(query => query.sql != null)
    .map(query => JSON.stringify([query.id, query.sql])));
  assert.equal(sqlKeys.length, new Set(sqlKeys).size, 'The same query ID and full SQL are not duplicated across variants');
  assert.deepEqual(new Set(sqlKeys), expectedSql, 'Shared catalog preserves every input SQL and its identity');
  const ddl = await page.locator('pre[data-index-ddl]').allTextContents();
  assert.equal(ddl.length, new Set(ddl).size, 'Full DDL is not duplicated across budget variants');
  const allowedDdl = savedDdl(reports);
  for (const value of ddl) assert.ok(allowedDdl.has(value), 'DDL must come from a saved report');
  for (const report of reports) {
    for (const index of report.selected_indexes ?? []) {
      if (index.ddl) assert.ok(ddl.includes(index.ddl), 'Every selected index keeps its full DDL');
    }
  }
  const plans = await page.locator('[data-plan-key]').evaluateAll(elements => elements.map(element =>
    (element.matches('pre') ? element : element.querySelector('pre'))?.textContent).filter(value => value !== undefined));
  const actualPlans = plans.map(value => canonical(JSON.parse(value)));
  assert.equal(plans.length, new Set(plans).size, 'Each identical raw plan has one full JSON block');
  const expectedPlans = new Set(reports.flatMap(report =>
    [report.baseline, report.selected, report.best_robust, report.best_mean, report.manual_comparison?.configuration]
      .flatMap(configuration => Object.values(configuration?.plans_by_query_id ?? {}).map(canonical))));
  assert.deepEqual(new Set(actualPlans), expectedPlans, 'All baseline, selected and alternative plans remain accessible');
}
async function noPageOverflow(page, message) {
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), message);
}

(async () => {
  const moved = mkdtempSync(join(tmpdir(), 'indexbudget-series-'));
  const browser = await chromium.launch({executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined});
  try {
    const page = await browser.newPage();
    const external = [], errors = [];
    page.on('request', request => { if (!request.url().startsWith('file:')) external.push(request.url()); });
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    await page.context().setOffline(true);
    const fixtures = process.argv[2]
      ? [['real', resolve(process.argv[2])]]
      : [...['mixed', 'same', 'nonmonotonic', 'zero-baseline', 'no-write', 'write', 'regression', 'observed-write']
        .map(name => [name, resolve(`build/report-previews/series-${name}`)]),
        ['recorded', resolve('build/report-previews/recorded-series')]];
    for (const [variant, source] of fixtures) {
      const directory = join(moved, `перенесённая серия ${variant}`);
      cpSync(source, directory, {recursive: true});
      const summary = JSON.parse(readFileSync(join(directory, 'series.json'), 'utf8'));
      const available = summary.points.filter(point => point.artifacts?.['report.html']);
      const reports = new Map(available.map(point => [point.ordinal,
        JSON.parse(readFileSync(join(directory, point.artifacts['report.json']), 'utf8'))]));
      const usable = summary.points.filter(point => ['completed', 'partial'].includes(point.status) && point.metrics);
      const selected = [...(usable.length ? usable : summary.points)].sort((a, b) => b.budget_value - a.budget_value)[0];
      const url = pathToFileURL(join(directory, 'report.html')).href;
      for (const width of [1440, 900, 390, 320]) {
        await page.setViewportSize({width, height: 1000});
        await page.goto(url);
        await noPageOverflow(page, `${variant}/${width}: page overflow`);
        assert.equal(await page.locator('.series-chart, .series-bar, .profile-plot, .chart-track').count(), 0,
          'The comparison uses tables without repeated charts');
        const sidebar = page.locator('.series-sidebar');
        assert.ok(await sidebar.isVisible());
        if (width > 800) {
          const menuBox = await sidebar.boundingBox(), mainBox = await page.locator('main').boundingBox();
          assert.ok(menuBox.x + menuBox.width <= mainBox.x + 1, 'Desktop menu stays beside the report');
        }
        const rows = page.locator('.series-table > tbody > tr');
        assert.equal(await rows.count(), summary.points.length);
        for (const point of summary.points) {
          const row = page.locator(`#point-${point.ordinal}`);
          assert.equal(await row.getAttribute('data-status'), point.status);
          // Full saved values stay in the row's technical disclosure, independently of readable units.
          const text = (await row.textContent()).replace(/[\s\u00a0\u202f]/g, '');
          assert.ok(text.includes(String(point.budget_value).replace('.', ',')), 'Exact budget value remains available');
        }
        const selector = page.locator('#series-variant');
        assert.ok(await selector.isVisible(), 'CSP permits the fixed script');
        assert.equal(await selector.inputValue(), String(selected.ordinal));
        const currentOverview = page.locator('[data-overview-variant]:visible');
        assert.equal(await currentOverview.count(), 1);
        assert.equal(await currentOverview.getAttribute('data-overview-variant'), String(selected.ordinal));
        const effectBox = await currentOverview.locator('.effect-sentence').first().boundingBox();
        assert.ok(effectBox && effectBox.y < 1000, `${variant}/${width}: effect remains near the conclusion`);
        await assertCatalogs(page, [...reports.values()]);
        for (const point of summary.points) {
          const saved = reports.get(point.ordinal), ordinal = String(point.ordinal), prefix = `variant-${ordinal}-`;
          await selector.selectOption(ordinal);
          const catalogToggle = page.locator('#series-all-indexes');
          await catalogToggle.uncheck();
          const selectedIds = new Set(point.selected_index_ids ?? []);
          const visibleIndexIds = await page.locator('#series-index-catalog [data-candidate-id]:visible')
            .evaluateAll(elements => elements.map(element => element.dataset.candidateId));
          assert.deepEqual(new Set(visibleIndexIds), selectedIds, 'The catalog shows the chosen set for this budget');
          if (!['completed', 'partial'].includes(point.status)) {
            const unavailable = page.locator('#series-empty-indexes');
            assert.ok(await unavailable.isVisible());
            assert.match(await unavailable.textContent(), /Пригодная рекомендация не сохранена|результат не сохранён/);
            assert.doesNotMatch(await unavailable.textContent(), /[Пп]устой набор|[Нн]овые индексы не предлагаются/,
              'A failed/skipped point is not presented as an empty recommendation');
          } else if (selectedIds.size === 0) {
            assert.match(await page.locator('#series-empty-indexes').textContent(), /[Пп]устой набор/,
              'A usable empty set is a distinct result');
          }
          const indexLink = page.locator(`#point-${ordinal} a[href="#series-index-catalog"]`).first();
          if (await indexLink.count()) {
            const other = summary.points.find(value => value.ordinal !== point.ordinal);
            if (other) await selector.selectOption(String(other.ordinal));
            await indexLink.click();
            assert.equal(await selector.inputValue(), ordinal, 'An index link in a budget row selects that budget');
            assert.deepEqual(new Set(await page.locator('#series-index-catalog [data-candidate-id]:visible')
              .evaluateAll(elements => elements.map(element => element.dataset.candidateId))), selectedIds,
              'The row link reveals its own index set');
          }
          const manualIds = saved?.manual_comparison?.candidates?.map(candidate => candidate.id) ?? [];
          if (manualIds.length) {
            const manualLink = page.locator(`#${prefix}manual-comparison a[href="#series-index-catalog"]`);
            await manualLink.evaluate(link => {
              for (let node = link; node; node = node.parentElement) if (node.tagName === 'DETAILS') node.open = true;
            });
            await manualLink.click();
            assert.ok(await catalogToggle.isChecked(), 'Manual DDL link reveals indexes outside the automatic recommendation');
            const allVisible = new Set(await page.locator('#series-index-catalog [data-candidate-id]:visible')
              .evaluateAll(elements => elements.map(element => element.dataset.candidateId)));
            manualIds.forEach(id => assert.ok(allVisible.has(id), 'Every manual index is visible after following its DDL link'));
            await catalogToggle.uncheck();
          }
          assert.equal(await currentOverview.count(), 1);
          assert.equal(await currentOverview.getAttribute('data-overview-variant'), ordinal);
          const visibleVariants = await page.locator('[data-report-prefix][data-variant]:visible')
            .evaluateAll(elements => elements.map(element => element.dataset.variant));
          assert.ok(visibleVariants.length > 0 && visibleVariants.every(value => value === ordinal),
            'All visible variant sections follow the selector');
          const queryRows = await page.locator(`#${prefix}query-comparison tbody tr`)
            .evaluateAll(elements => elements.map(element => ({...element.dataset})));
          assert.equal(queryRows.length, saved?.queries?.length ?? 0, 'Selected variant keeps saved queries and never invents missing ones');
          for (const row of queryRows) {
            const query = saved.queries[Number(row.order)];
            const effect = saved.query_effects.find(value => value.id === query.id)?.selected;
            assert.equal(optionalNumber(row.cost), query.selected_cost, `${variant}/${ordinal}: saved cost`);
            assert.equal(optionalNumber(row.gain), effect?.gain_percent ?? null, `${variant}/${ordinal}: saved gain`);
          }
          const queryLink = page.locator(`a[href="#${prefix}query-0"]`).first();
          if (await queryLink.count()) {
            await queryLink.click();
            assert.ok(await page.locator(`#${prefix}query-0`).evaluate(anchor => anchor.closest('details').open),
              'Variant SQL anchor opens the shared catalog detail');
            assert.equal(await selector.inputValue(), ordinal, 'Query navigation preserves variant identity');
            const sorting = page.locator(`#${prefix}query-sort`);
            if (await sorting.count()) {
              await sorting.selectOption('original');
              const order = await page.locator(`#${prefix}query-comparison tbody tr`)
                .evaluateAll(elements => elements.map(element => Number(element.dataset.order)));
              assert.deepEqual(order, [...order].sort((a, b) => a - b));
            }
          }
          await noPageOverflow(page, `${variant}/${width}/${ordinal}: selected report overflow`);
        }
        await selector.selectOption(String(selected.ordinal));
        await page.locator('details').evaluateAll(elements => elements.forEach(detail => { detail.open = true; }));
        await noPageOverflow(page, `${variant}/${width}: expanded catalog/diagnostics overflow`);
        if (selected.run_id) assert.ok((await page.locator('main').textContent()).includes(selected.run_id),
          'Run identity remains in details');
        const ids = await page.locator('[id]').evaluateAll(elements => elements.map(element => element.id));
        assert.equal(ids.length, new Set(ids).size, 'Shared catalogs do not duplicate anchors');
        await page.locator('details').evaluateAll(elements => elements.forEach(detail => { detail.open = false; }));
        if (process.env.REPORT_UI_ARTIFACTS && (width === 1440 || width === 390)) {
          await page.evaluate(() => scrollTo(0, 0));
          await page.screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-navigation-${width}.png`)});
          await page.locator('.series-table').screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-comparison-${width}.png`)});
          await page.screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-${width}.png`), fullPage: true});
        }
      }
      const links = await page.locator('a[href]').evaluateAll(elements => elements.map(a => a.href));
      for (const link of [...new Set(links)]) {
        const target = new URL(link);
        assert.equal(target.protocol, 'file:');
        assert.ok(existsSync(fileURLToPath(target)), link);
        if (target.hash) {
          await page.goto(link);
          const id = decodeURIComponent(target.hash.slice(1));
          assert.ok(await page.evaluate(value => document.getElementById(value) !== null, id), `Local anchor exists: ${link}`);
          const variantMatch = /^#variant-([1-5])(?:-[a-z0-9-]+)?$/.exec(target.hash);
          if (variantMatch) assert.equal(await page.locator('[data-overview-variant]:visible').getAttribute('data-overview-variant'),
            variantMatch[1], 'Direct variant hash selects the matching conclusion');
        } else if (target.pathname.endsWith('.sql')) {
          // Chromium may reject goto before it emits download (notably on Linux).
          // Keep the listener until the asynchronous download has actually arrived.
          let onDownload, downloadTimeout;
          const downloaded = new Promise(resolve => {
            onDownload = resolve;
            page.on('download', onDownload);
          });
          try {
            let response;
            try {
              response = await page.goto(link);
            } catch (error) {
              assert.match(error.message, /Download is starting/, link);
              const download = await Promise.race([
                downloaded,
                new Promise((_, reject) => {
                  downloadTimeout = setTimeout(() => reject(new Error(`Download did not start: ${link}`)), 10_000);
                }),
              ]);
              assert.equal(await download.failure(), null, link);
              assert.deepEqual(readFileSync(await download.path()), readFileSync(fileURLToPath(target)));
              continue;
            }
            assert.ok(response?.ok(), link);
            assert.deepEqual(await response.body(), readFileSync(fileURLToPath(target)));
          } finally {
            clearTimeout(downloadTimeout);
            page.off('download', onDownload);
          }
        } else {
          const response = await page.goto(link);
          assert.ok(!response || response.ok(), link);
        }
      }
      const fallback = await browser.newPage({javaScriptEnabled: false, viewport: {width: 390, height: 900}});
      await fallback.context().setOffline(true);
      await fallback.goto(url);
      const fallbackVariants = await fallback.locator('[data-report-prefix][data-variant]').evaluateAll(elements =>
        elements.map(element => ({id: element.dataset.variant, hidden: element.hidden || getComputedStyle(element).display === 'none'})));
      assert.deepEqual(new Set(fallbackVariants.map(value => value.id)), new Set(summary.points.map(point => String(point.ordinal))));
      assert.ok(fallbackVariants.every(value => !value.hidden), 'Without JS every variant and its missing-data state remain accessible');
      const first = fallback.locator('#series-query-catalog details.query-detail').first();
      if (await first.count()) {
        await first.locator(':scope > summary').click();
        assert.ok(await first.evaluate(detail => detail.open), 'No-JS shared SQL disclosure works');
      }
      await noPageOverflow(fallback, `${variant}: no-JS page overflow`);
      await fallback.close();
      const standalone = join(moved, `standalone-${variant}.html`);
      copyFileSync(join(directory, 'report.html'), standalone);
      await page.goto(pathToFileURL(standalone).href);
      assert.ok(await page.locator('#series-variant').isVisible());
      await assertCatalogs(page, [...reports.values()]);
      console.log(`Offline Chromium: ${variant}, 4 widths, saved values, shared catalogs, switching, standalone/no-JS and relocation passed.`);
    }
    assert.deepEqual(external, [], 'No network requests');
    assert.deepEqual(errors, [], 'No runtime/CSP errors');
  } finally {
    await browser.close();
    rmSync(moved, {recursive: true, force: true});
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
