/* Browser geometry, not CSS-string assertions. Run after ./gradlew reportPreview. */
const assert = require('node:assert/strict');
const {resolve} = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');

(async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.context().setOffline(true);
    for (const width of [1440, 900, 390, 320]) {
      await page.setViewportSize({width, height: 900});
      await page.goto(pathToFileURL(resolve('build/report-previews/fractional.html')).href);
      for (const section of ['profiles', 'comparison']) {
        const charts = await page.locator(`#${section} .ratio-chart`).evaluateAll(elements =>
          elements.map(chart => Array.from(chart.querySelectorAll('.chart-row'), row => {
            const track = row.querySelector('.chart-track').getBoundingClientRect();
            const fill = row.querySelector('.chart-fill').getBoundingClientRect();
            return {
              track: track.width,
              fill: fill.width,
              label: row.querySelector('strong').textContent,
            };
          })));
        assert.ok(charts.length >= 2, 'Exercise separate comparison cards too');
        const rows = charts.flat();
        assert.ok(rows.some(row => row.label === '100'));
        assert.ok(rows.some(row => /,\d{8,}/.test(row.label)), 'Fixture must have a long fractional label');
        const trackWidth = rows[0].track;
        assert.ok(trackWidth > 30);
        for (const chart of charts) {
          const baseline = chart[0];
          for (const row of chart) {
            assert.ok(Math.abs(row.track - trackWidth) < 0.1,
              `${width}px/${section}: unequal tracks ${row.track} vs ${trackWidth} (${row.label})`);
            const ratio = Number(row.label.replaceAll('\u202f', '').replace(',', '.')) / 100;
            assert.ok(Math.abs(row.fill - baseline.fill * ratio) < 0.15,
              `${width}px/${section}: visual ratio disagrees with ${row.label}`);
          }
        }
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        `${width}px: page overflows horizontally`);
      // This also detects a blocked inline script/CSP mismatch in the actual exported file.
      assert.ok(await page.locator('.query-controls').isVisible());
      console.log(`Offline Chromium: common chart widths and proportional bars at ${width}px passed.`);
    }
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
