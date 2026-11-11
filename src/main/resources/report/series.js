/* Switch saved reports only: no network, HTML interpolation or metric calculation. */
(() => {
  'use strict';
  const select = document.getElementById('series-variant');
  const content = document.getElementById('series-detail-content');
  if (!select || !content) return;
  const reports = Array.from(content.querySelectorAll('.series-detail'));
  const show = value => {
    const selected = reports.find(report => report.id === 'variant-' + value);
    if (!selected) return;
    select.value = value;
    reports.forEach(report => {
      report.hidden = report !== selected;
      report.open = report === selected;
    });
    document.getElementById('series-nav-variant').textContent = value;
    document.querySelectorAll('.series-variant-nav a').forEach(link => {
      link.setAttribute('href', '#' + selected.dataset.reportPrefix + link.dataset.section);
    });
  };
  const followAnchor = () => {
    const match = /^#variant-([1-5])(?:-[a-z0-9-]+)?$/.exec(location.hash);
    if (!match) return;
    show(match[1]);
    const target = document.getElementById(location.hash.slice(1));
    if (target) target.scrollIntoView({block: 'start'});
  };
  document.querySelector('.series-selector').hidden = false;
  show(select.value);
  select.addEventListener('change', () => {
    show(select.value);
    // Keep pending hash navigation and browser Back/Forward aligned with the visible variant.
    location.hash = 'variant-' + select.value;
  });
  window.addEventListener('hashchange', followAnchor);
  document.querySelectorAll('a[href^="#variant-"]').forEach(link => link.addEventListener('click', () => {
    const match = /^#variant-([1-5])(?:-[a-z0-9-]+)?$/.exec(link.getAttribute('href'));
    if (match) show(match[1]);
  }));
  followAnchor();
})();
