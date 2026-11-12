'use strict';
// Explicit real end-to-end acceptance on an exclusive LOCAL demo DB, never the shared stand.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const base = process.env.FRONTEND_TEST_URL || 'http://127.0.0.1:8090';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use an exclusive local demo environment');
const output = path.resolve('build/frontend-series-verification');
const cli = path.resolve(process.env.FRONTEND_CLI || 'build/install/indexbudget/bin/indexbudget');

(async () => {
  await fs.mkdir(output, {recursive: true});
  const browser = await chromium.launch({headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined});
  try {
    const page = await browser.newPage();
    await page.addInitScript(() => localStorage.setItem('indexbudget.tour.v1', 'done'));
    for (const mode of ['single', 'storage_budget_bytes', 'write_budget_units_per_hour']) {
      await page.goto(base);
      await page.locator('#workspace:not([disabled])').waitFor();
      const skip = page.locator('[data-tour="skip"]');
      if (await skip.isVisible()) await skip.click();
      // Для сравнения оставляем один SELECT объединённого датасета.
      await page.locator('[data-template="04-scenarios"]').click();
      // A small supported workload keeps this acceptance deterministic within the normal budget.
      await page.evaluate(() => {
        const first = state.workload.queries[0];
        state.workload.queries = [first];
        for (const p of state.workload.profiles) p.calls_per_hour = {[first.id]: 100};
        state.workload.constraints.max_indexes = 1;
        state.workload.analysis_mode = 'normal';
        renderAll();
      });
      await page.locator('#launch-mode').selectOption(mode === 'single' ? 'single' : 'series');
      if (mode !== 'single') {
        await page.locator('#series-budget').selectOption(mode);
        await page.locator('#series-values').fill(mode === 'storage_budget_bytes' ? '0, 16777216, 67108864' : '0, 10000, 100000');
        await page.locator('#series-timeout').fill('600');
      }
      const responsePromise = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/runs'));
      await page.locator('#analyze').click();
      const response = await responsePromise;
      assert.equal(response.status(), 202);
      const submitted = response.request().postDataJSON();
      const job = await response.json();
      // Reload during the job: browser must resume polling by saved run ID, not submit another job.
      await page.goto(`${base}/?run=${job.run_id}`);
      await page.waitForURL(/\/reports\/[^/]+\/(?:report|demo-series)\.html$/, {timeout: 720000});
      const reportBase = `${base}/reports/${job.run_id}/`;
      const folder = path.join(output, mode);
      await fs.mkdir(folder, {recursive: true});
      const workload = path.join(folder, 'workload.json');
      await fs.writeFile(workload, JSON.stringify(submitted.workload));
      const args = ['analyze', '--workload', workload, '--output-dir', path.join(folder, 'cli')];
      if (mode !== 'single') {
        const request = path.join(folder, 'series-request.json');
        await fs.writeFile(request, JSON.stringify(submitted.series));
        args[0] = 'analyze-series';
        args.push('--series', request, '--analysis-mode', submitted.workload.analysis_mode || 'normal');
      }
      try {
        await promisify(execFile)(cli, args, {timeout: 720000, maxBuffer: 8 * 1024 * 1024,
          env: {...process.env, INDEXBUDGET_DATABASE_URL: `jdbc:postgresql://127.0.0.1:${process.env.FRONTEND_DB_PORT || '55433'}/frontend_demo?user=frontend_scenarios`}});
      } catch (error) { if (error.code !== 3) throw error; }
      const file = mode === 'single' ? 'report.json' : 'series.json';
      const remote = await (await page.request.get(reportBase + file)).json();
      const local = JSON.parse(await fs.readFile(path.join(folder, 'cli', file), 'utf8'));
      if (mode === 'single') {
        assert.deepEqual(remote.selected.candidate_ids, local.selected.candidate_ids);
      } else {
        assert.deepEqual(remote.values, submitted.series.values);
        assert.deepEqual(remote.values, local.values);
        assert.equal(remote.budget, local.budget);
        assert.deepEqual(remote.points.map(p => p.selected_index_ids), local.points.map(p => p.selected_index_ids));
        for (const point of remote.points) for (const link of Object.values(point.artifacts || {})) {
          assert.ok(!link.includes('..') && !link.includes('://') && !link.startsWith('/'));
          assert.equal((await page.request.get(reportBase + link)).status(), 200);
        }
      }
      await fs.writeFile(path.join(folder, file), JSON.stringify(remote, null, 2));
      await page.screenshot({path: path.join(folder, 'report.png'), fullPage: true});
      console.log(`PASS real browser/CLI parity: ${mode}; job ${job.run_id}`);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
