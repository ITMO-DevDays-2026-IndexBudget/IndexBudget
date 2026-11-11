/* Run from repository root with node, or the JavaScriptCore shell on macOS.
   A minimal DOM double verifies view behavior without a browser or network. */
const listeners = {};
const select = {value: 'regression', addEventListener: (name, handler) => { listeners[name] = handler; }};
const rows = [
  {dataset: {order: '1', regression: '1', gain: '-20', cost: '120'}},
  {dataset: {order: '0', regression: '0', gain: '80', cost: '20'}},
  {dataset: {order: '2', regression: '0', gain: '', cost: ''}},
  {dataset: {order: '3', regression: '0', gain: '80', cost: '0'}},
];
const body = {rows: rows.slice(), appendChild(row) {
  this.rows.splice(this.rows.indexOf(row), 1);
  this.rows.push(row);
}};
const controls = {hidden: true};
const details = {tagName: 'DETAILS', open: false, scrollIntoView() { this.scrolled = true; }};
const link = {getAttribute: () => '#query-1', addEventListener: (name, handler) => { listeners.click = handler; }};
const location = {hash: '#query-1'};
const copied = [];
let denyClipboard = false;
const navigator = {clipboard: {async writeText(text) {
  if (denyClipboard) throw new Error('Clipboard permission denied');
  copied.push(text);
}}};
const selection = {ranges: [], removeAllRanges() { this.ranges = []; }, addRange(range) { this.ranges.push(range); }};
const window = {addEventListener: (name, handler) => { listeners[name] = handler; }, getSelection: () => selection};
const sql = {textContent: 'SELECT "value"\nFROM "archive"."records" WHERE value = \'</script>\';'};
const ddl = {textContent: 'CREATE INDEX "ib_name" ON "public"."records" USING btree ("value");'};
const button = id => ({dataset: {copy: id}, hidden: true, nextElementSibling: {textContent: ''},
  addEventListener(name, handler) { this[name] = handler; }});
const copyButtons = [button('copy-sql-0'), button('copy-ddl-0'), button('missing-target')];
const document = {
  getElementById: id => ({'query-sort': select, 'query-1': details, 'copy-sql-0': sql, 'copy-ddl-0': ddl}[id]),
  querySelector: selector => ({'#query-comparison tbody': body, '.query-controls': controls}[selector]),
  querySelectorAll: selector => ({'a[href^="#"]': [link], 'button[data-copy]': copyButtons}[selector] || []),
  createRange: () => ({selectNodeContents(target) { this.target = target; }}),
};
// Node eval sees this module's DOM doubles; JavaScriptCore load sees top-level lexical bindings.
if (typeof require === 'function') eval(require('node:fs').readFileSync('src/main/resources/report/report.js', 'utf8'));
else load('src/main/resources/report/report.js');
function check(condition, message) { if (!condition) throw new Error(message); }
function sort(key, expected) {
  select.value = key;
  listeners.change();
  check(body.rows.map(row => row.dataset.order).join(',') === expected, 'Unexpected ' + key + ' order');
}
check(!controls.hidden, 'Controls stay hidden after initialization');
check(details.open && details.scrolled, 'Initial local anchor did not reveal saved SQL');
sort('cost', '1,0,3,2'); // Unknown last, a real zero is known.
sort('gain', '0,3,1,2'); // Stable ties, negative regression and unknown last.
sort('original', '0,1,2,3');
sort('regression', '1,0,2,3');
details.open = false;
listeners.click();
check(details.open, 'Repeated click must reopen the same anchor');
details.open = false;
location.hash = '#query-1<script>';
listeners.hashchange();
check(!details.open, 'Non-ordinal anchor must be ignored');
location.hash = '#query-1';
listeners.hashchange();
check(details.open, 'Hash navigation must reveal SQL');
check(rows[0].dataset.gain === '-20' && rows[1].dataset.cost === '20', 'Controls modified saved data');
check(copied.length === 0 && selection.ranges.length === 0, 'Copy must require an explicit click');
check(copyButtons.every(button => !button.hidden), 'Script must enable local copy buttons');
(async () => {
  await copyButtons[0].click();
  check(copied.length === 1 && copied[0] === sql.textContent, 'SQL copy must preserve exact text, quotes and newlines');
  check(copyButtons[0].nextElementSibling.textContent === 'Скопировано', 'Copy success must be announced');
  await copyButtons[1].click();
  check(copied[1] === ddl.textContent, 'DDL copy must preserve the full qualified definition');
  denyClipboard = true;
  await copyButtons[1].click();
  check(copied.length === 2, 'Denied clipboard cannot report successful copying');
  check(selection.ranges.length === 1 && selection.ranges[0].target === ddl, 'Denied clipboard must select exact target text');
  check(copyButtons[1].nextElementSibling.textContent.includes('Ctrl+C'), 'Fallback must explain how to copy manually');
  navigator.clipboard = undefined;
  await copyButtons[0].click();
  check(selection.ranges.length === 1 && selection.ranges[0].target === sql, 'Missing Clipboard API must use the same manual fallback');
  await copyButtons[2].click();
  check(copied.length === 2 && copyButtons[2].nextElementSibling.textContent === '', 'A missing target must not copy stale content');
  const message = 'Report controls: sorting, null/zero, stable ties, local anchors and exact copy success/fallback passed.';
  if (typeof print === 'function') print(message); else console.log(message);
})().catch(error => {
  if (typeof console !== 'undefined') console.error(error); else print(error);
  if (typeof process !== 'undefined') process.exitCode = 1; else if (typeof quit === 'function') quit(1);
});
