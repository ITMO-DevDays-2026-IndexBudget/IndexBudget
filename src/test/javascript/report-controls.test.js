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
const window = {addEventListener: (name, handler) => { listeners[name] = handler; }};
const document = {
  getElementById: id => ({'query-sort': select, 'query-1': details}[id]),
  querySelector: selector => ({'#query-comparison tbody': body, '.query-controls': controls}[selector]),
  querySelectorAll: () => [link],
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
if (typeof print === 'function') print('Report controls: sorting, null/zero, stable ties and local anchors passed.');
else console.log('Report controls: sorting, null/zero, stable ties and local anchors passed.');
