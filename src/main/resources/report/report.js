/* View controls only. All numbers and plans are supplied by the shared report model. */
(() => {
  'use strict';
  const select = document.getElementById('query-sort');
  const body = document.querySelector('#query-comparison tbody');
  if (select && body) {
    document.querySelector('.query-controls').hidden = false;
    select.addEventListener('change', () => {
      const key = select.value;
      const rows = Array.from(body.rows);
      const numeric = (row, field) => row.dataset[field] === '' ? null : Number(row.dataset[field]);
      rows.sort((a, b) => {
        const original = Number(a.dataset.order) - Number(b.dataset.order);
        if (key === 'original') return original;
        const left = numeric(a, key);
        const right = numeric(b, key);
        if (left === null || right === null) return (left === null) - (right === null) || original;
        return right - left || original;
      });
      rows.forEach(row => body.appendChild(row));
    });
  }
  const reveal = () => {
    // Only renderer-owned ordinal anchors, never selectors supplied by SQL or identifiers.
    if (!/^#query-\d+$/.test(location.hash)) return;
    const details = document.getElementById(location.hash.slice(1));
    if (details && details.tagName === 'DETAILS') {
      details.open = true;
      details.scrollIntoView({block: 'start'});
    }
  };
  window.addEventListener('hashchange', reveal);
  document.querySelectorAll('a[href^="#query-"]').forEach(link => link.addEventListener('click', () => {
    const details = document.getElementById(link.getAttribute('href').slice(1));
    if (details && details.tagName === 'DETAILS') details.open = true;
  }));
  reveal();
})();
