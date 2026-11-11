/* Real offline file navigation, JSON agreement and chart geometry after moving the entire series. */
const assert = require('node:assert/strict');
const {cpSync, copyFileSync, mkdtempSync, readFileSync, existsSync, rmSync} = require('node:fs');
const {resolve, join} = require('node:path');
const {tmpdir} = require('node:os');
const {pathToFileURL, fileURLToPath} = require('node:url');
const {chromium} = require('playwright');

const availableCount = summary => summary.points.filter(p => p.artifacts['report.html']).length;

(async () => {
  const moved = mkdtempSync(join(tmpdir(), 'indexbudget-series-'));
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const external = [];
    const errors = [];
    page.on('request', request => { if (!request.url().startsWith('file:')) external.push(request.url()); });
    page.on('pageerror', error => errors.push(error.message));
    await page.context().setOffline(true);
    const fixtures = process.argv[2]
      ? [['real', resolve(process.argv[2])]]
      : ['mixed', 'same', 'nonmonotonic', 'zero-baseline', 'no-write', 'write', 'regression']
        .map(name => [name, resolve(`build/report-previews/series-${name}`)]);
    for (const [variant, source] of fixtures) {
      const directory = join(moved, `перенесённая серия ${variant}`);
      cpSync(source, directory, {recursive: true});
      const summary = JSON.parse(readFileSync(join(directory, 'series.json'), 'utf8'));
      const url = pathToFileURL(join(directory, 'report.html')).href;
      for (const width of [1440, 900, 390, 320]) {
        await page.setViewportSize({width, height: 1000});
        await page.goto(url);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          `${variant}/${width}: horizontal overflow`);
        const sidebar = page.locator('.series-sidebar');
        assert.ok(await sidebar.isVisible());
        if (width > 800) {
          const menuBox = await sidebar.boundingBox();
          const mainBox = await page.locator('main').boundingBox();
          assert.ok(menuBox.x + menuBox.width <= mainBox.x + 1, 'Desktop navigation is to the left of the report');
        }
        const rows = page.locator('.series-table tbody tr');
        assert.equal(await rows.count(), summary.points.length);
        for (const point of summary.points) {
          const row = page.locator(`#point-${point.ordinal}`);
          assert.ok((await row.innerText()).includes(point.status));
          assert.ok((await row.innerText()).includes(String(point.budget_value).replace('.', ',')));
          if (width <= 800) {
            const rowBox = await row.boundingBox();
            const headingBox = await row.locator('th').boundingBox();
            assert.ok(headingBox.width > rowBox.width - 4, 'Mobile budget heading must span the whole card');
          }
        }
        const profiles = summary.settings.profiles;
        const gains = summary.points.flatMap(p => p.metrics?.profiles ?? []).map(p => p.gain_percent).filter(x => x !== null);
        const low = Math.min(0, ...gains);
        const high = Math.max(100, ...gains);
        for (let index = 0; index < profiles.length; index++) {
          const chart = page.locator('.series-chart').nth(index);
          const bars = chart.locator('.series-bar');
          const calculated = summary.points.filter(p => ['completed', 'partial'].includes(p.status) &&
            p.metrics?.profiles.some(s => s.profile_id === profiles[index].id && s.gain_percent !== null));
          assert.equal(await bars.count(), calculated.length);
          const columns = chart.locator('.series-column');
          const ordered = [...summary.points].sort((a, b) => a.budget_value - b.budget_value);
          assert.deepEqual(await columns.evaluateAll(elements => elements.map(a => a.getAttribute('href'))),
            ordered.map(p => `#point-${p.ordinal}`));
          const widths = await columns.evaluateAll(elements => elements.map(a => a.getBoundingClientRect().width));
          assert.ok(widths.every(w => Math.abs(w - widths[0]) < 1), 'Budget categories use equal column widths');
          for (const point of calculated) {
            const column = chart.locator(`.series-column[href="#point-${point.ordinal}"]`);
            const track = await column.locator('.series-track').boundingBox();
            const bar = column.locator('.series-bar');
            const box = await bar.boundingBox();
            const gain = point.metrics.profiles.find(p => p.profile_id === profiles[index].id).gain_percent;
            const expectedHeight = Math.abs(gain) / (high - low) * track.height;
            const expectedBottom = track.y + track.height - (Math.min(gain, 0) - low) / (high - low) * track.height;
            assert.ok(Math.abs(box.height - expectedHeight) < 1, `${variant}/${width}: saved effect height`);
            assert.ok(Math.abs(box.y + box.height - expectedBottom) < 1, `${variant}/${width}: bar starts at zero`);
            assert.equal(await bar.evaluate(el => el.classList.contains('partial')), point.status === 'partial');
            assert.equal(await bar.evaluate(el => el.classList.contains('negative')), gain < 0);
          }

        }
        const available = summary.points.filter(p => p.artifacts['report.html']);
        const usable = available.filter(p => ['completed', 'partial'].includes(p.status) && p.metrics);
        const selected = [...(usable.length ? usable : available)].sort((a, b) => b.budget_value - a.budget_value)[0];
        const selector = page.locator('#series-variant');
        assert.ok(await selector.isVisible(), 'CSP must permit the fixed selection script');
        assert.equal(await selector.inputValue(), String(selected.ordinal));
        for (const point of available) {
          await selector.selectOption(String(point.ordinal));
          const detail = page.locator(`#variant-${point.ordinal}`);
          assert.ok(await detail.isVisible());
          assert.equal(await page.locator('.series-detail:visible').count(), 1);
          assert.ok((await detail.innerText()).includes(point.run_id));
          const menuLinks = page.locator('.series-variant-nav a');
          for (const link of await menuLinks.all()) {
            const href = await link.getAttribute('href');
            assert.ok(href.startsWith(`#variant-${point.ordinal}-`));
            assert.equal(await detail.locator(href).count(), 1, 'Menu targets the selected report');
          }
          await page.locator('.series-variant-nav a[data-section="queries"]').click();
          await page.waitForFunction(ordinal => location.hash === `#variant-${ordinal}-queries`, point.ordinal);
          assert.ok(await detail.isVisible(), 'Menu navigation preserves the selected variant');
          assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
            `${variant}/${width}/point-${point.ordinal}: embedded report overflow`);
          const queryLink = detail.locator(`a[href="#variant-${point.ordinal}-query-0"]`).first();
          if (await queryLink.count()) {
            await queryLink.click();
            assert.equal(await detail.locator(`#variant-${point.ordinal}-query-0`).getAttribute('open'), '');
            const sorting = detail.locator(`#variant-${point.ordinal}-query-sort`);
            await sorting.selectOption('original');
            const order = await detail.locator('#variant-' + point.ordinal + '-query-comparison tbody tr')
              .evaluateAll(rows => rows.map(row => Number(row.dataset.order)));
            assert.deepEqual(order, [...order].sort((a, b) => a - b));
          }
        }
        await selector.selectOption(String(selected.ordinal));
        if (process.env.REPORT_UI_ARTIFACTS && (width === 1440 || width === 390)) {
          await page.locator('.series-sidebar a[href="#overview"]').click();
          await page.screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-navigation-${width}.png`)});
          await page.locator('#variant-details').screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-details-${width}.png`)});
          await page.locator('#effects').screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-charts-${width}.png`)});
          await page.screenshot({path: join(process.env.REPORT_UI_ARTIFACTS, `series-${variant}-${width}.png`), fullPage: true});
        }
      }
      // Every visible link resolves after relocation, including failed report links and in-page anchors.
      const links = await page.locator('a').evaluateAll(elements => elements.map(a => a.href));
      for (const link of [...new Set(links)]) {
        const target = new URL(link);
        assert.equal(target.protocol, 'file:');
        assert.ok(existsSync(fileURLToPath(target)), link);
        if (target.hash) {
          await page.goto(link);
          assert.equal(await page.locator(target.hash).count(), 1);
        } else if (target.pathname.endsWith('.sql')) {
          const downloadEvent = page.waitForEvent('download');
          await page.goto(link).catch(error => assert.match(error.message, /Download is starting/));
          const download = await downloadEvent;
          assert.equal(await download.failure(), null);
          assert.deepEqual(readFileSync(await download.path()), readFileSync(fileURLToPath(target)));
        } else {
          const response = await page.goto(link);
          assert.ok(!response || response.ok(), link);
        }
      }
      const fallback = await browser.newPage({javaScriptEnabled: false, viewport: {width: 390, height: 900}});
      await fallback.context().setOffline(true);
      await fallback.goto(url);
      assert.equal(await fallback.locator('.series-detail').count(), availableCount(summary));
      assert.equal(await fallback.locator('.series-detail[hidden]').count(), 0);
      const first = fallback.locator('.series-detail').first();
      if (await first.getAttribute('open') === null) await first.locator(':scope > summary').click();
      assert.equal(await first.getAttribute('open'), '');
      await fallback.close();
      const standalone = join(moved, `standalone-${variant}.html`);
      copyFileSync(join(directory, 'report.html'), standalone);
      await page.goto(pathToFileURL(standalone).href);
      assert.equal(await page.locator('.series-detail').count(), availableCount(summary));
      assert.ok(await page.locator('#series-variant').isVisible());
      console.log(`Offline Chromium: ${variant}, 4 widths, switching, SQL anchors, standalone HTML, no-JS and moved links passed.`);
    }
    assert.deepEqual(external, []);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
    rmSync(moved, {recursive: true, force: true});
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
