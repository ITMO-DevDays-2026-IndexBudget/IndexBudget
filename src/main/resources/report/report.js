/* View controls only. All numbers and plans are supplied by the shared report model. */
(() => {
  'use strict';
  const content = document.getElementById('series-detail-content');
  const roots = content ? content.querySelectorAll('[data-report-prefix]') : [document];
  for (const root of roots) {
    const prefix = root.dataset?.reportPrefix || '';
    const select = document.getElementById(prefix + 'query-sort');
    const body = root.querySelector('#' + prefix + 'query-comparison tbody');
    if (select && body) {
      root.querySelector('.query-controls').hidden = false;
      const headers = root.querySelectorAll('th[data-sort-key]');
      let activeKey = 'regression', ascending = false;
      const sort = (key, asc) => {
        activeKey = key;
        ascending = asc;
        const rows = Array.from(body.rows);
        const numeric = (row, field) => row.dataset[field] === '' ? null : Number(row.dataset[field]);
        rows.sort((a, b) => {
          const original = Number(a.dataset.order) - Number(b.dataset.order);
          const left = numeric(a, key);
          const right = numeric(b, key);
          if (left === null || right === null) return (left === null) - (right === null) || original;
          return (asc ? left - right : right - left) || original;
        });
        rows.forEach(row => body.appendChild(row));
        headers.forEach(header => header.setAttribute('aria-sort',
          header.dataset.sortKey === key ? (asc ? 'ascending' : 'descending') : 'none'));
      };
      select.addEventListener('change', () => {
        if (select.value === 'column') return;
        sort(select.value === 'original' ? 'order' : select.value, select.value === 'original');
      });
      headers.forEach(header => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'query-sort-button';
        button.textContent = header.textContent;
        button.title = 'Сортировать по столбцу';
        header.textContent = '';
        header.appendChild(button);
        header.setAttribute('aria-sort', 'none');
        button.addEventListener('click', () => {
          const key = header.dataset.sortKey;
          select.value = 'column';
          sort(key, activeKey === key ? !ascending : key === 'order');
        });
      });
    }
  }
  document.querySelectorAll('button[data-copy]').forEach(button => {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const target = document.getElementById(button.dataset.copy);
      const status = button.nextElementSibling;
      if (!target) return;
      try {
        await navigator.clipboard.writeText(target.textContent);
        status.textContent = 'Скопировано';
      } catch (_) {
        // file:// and restrictive browser settings may deny the Clipboard API.
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(target);
        selection.removeAllRanges();
        selection.addRange(range);
        status.textContent = 'Текст выделен. Нажми Ctrl+C или ⌘C.';
      }
    });
  });
  const reveal = hash => {
    // Lookup by ID only; no selector or HTML is constructed from an anchor.
    if (!/^#[a-z0-9-]+$/.test(hash)) return;
    const target = document.getElementById(hash.slice(1));
    if (!target) return;
    for (let node = target; node; node = node.parentElement) {
      if (node.tagName === 'DETAILS') node.open = true;
    }
    target.scrollIntoView({block: 'start'});
  };
  window.addEventListener('hashchange', () => reveal(location.hash));
  document.querySelectorAll('a[href^="#"]').forEach(link => link.addEventListener('click', () => reveal(link.getAttribute('href'))));
  reveal(location.hash);
})();
