/* View state only: all results and labels are generated from retained report models. */
(() => {
  'use strict';
  const select = document.getElementById('series-variant');
  if (!select) return;
  const choices = Array.from(select.options).map(option => option.value);
  const catalogToggle = document.getElementById('series-all-indexes');
  const catalog = () => {
    let count = 0;
    document.querySelectorAll('[data-index-members]').forEach(entry => {
      const member = entry.dataset.indexMembers.split(',').includes(select.value);
      if (member) count++;
      entry.hidden = !catalogToggle?.checked && !member;
    });
    const empty = document.getElementById('series-empty-indexes');
    if (empty) {
      const state = select.selectedOptions[0]?.dataset.catalogState;
      empty.textContent = state === 'empty'
        ? 'Выбран пустой набор: оставь существующие индексы.'
        : state === 'unavailable'
          ? 'Пригодная рекомендация не сохранена.'
          : 'Определения выбранных индексов не сохранены.';
      empty.hidden = count > 0 || !!catalogToggle?.checked;
    }
  };
  const show = value => {
    if (!choices.includes(value)) return;
    select.value = value;
    document.querySelectorAll('[data-overview-variant]').forEach(el => el.hidden = el.dataset.overviewVariant !== value);
    document.querySelectorAll('[data-variant]').forEach(el => el.hidden = el.dataset.variant !== value);
    document.querySelectorAll('[data-variants]').forEach(el => el.hidden = !el.dataset.variants.split(',').includes(value));
    document.querySelectorAll('[data-select-variant]').forEach(button => {
      const active = button.dataset.selectVariant === value;
      button.setAttribute('aria-pressed', String(active));
      button.closest('tr').classList.toggle('chosen', active);
    });
    catalog();
  };
  document.querySelector('.series-selector').hidden = false;
  const toggleLabel = catalogToggle?.closest('label');
  if (toggleLabel) toggleLabel.hidden = false;
  catalogToggle?.addEventListener('change', catalog);
  show(select.value);
  select.addEventListener('change', () => { show(select.value); history.replaceState(null, '', '#variant-' + select.value + '-overview'); });
  document.querySelectorAll('[data-select-variant]').forEach(button => button.addEventListener('click', () => show(button.dataset.selectVariant)));
  const follow = hash => {
    const match = /^#variant-([1-5])(?:-[a-z0-9-]+)?$/.exec(hash);
    if (match) show(match[1]);
    const target = document.getElementById(hash.slice(1));
    if (!target) return;
    if (target.closest('[data-index-members]') && catalogToggle) { catalogToggle.checked = true; catalog(); }
    for (let node = target; node; node = node.parentElement) if (node.tagName === 'DETAILS') node.open = true;
  };
  document.querySelectorAll('a[href^="#"]').forEach(link => link.addEventListener('click', () => {
    const point = /^point-([1-5])$/.exec(link.closest('tr')?.id || '');
    if (point) show(point[1]);
    if (link.closest('[id$="-manual-comparison"]') && catalogToggle) { catalogToggle.checked = true; catalog(); }
    follow(link.getAttribute('href'));
  }));
  window.addEventListener('hashchange', () => follow(location.hash));
  follow(location.hash);
})();
