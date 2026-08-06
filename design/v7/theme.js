/* Ours v7 — light/dark toggle for every demo and mockup on a page.
   Light is not an inversion of dark here, so both have to be inspectable; a
   spec page that can only be read one way hides half of what it specifies.
   Anything marked .locked keeps the theme it was authored with — used where a
   figure is only true in one theme, or where the two are shown side by side. */
(function () {
  function apply(mode) {
    document.querySelectorAll('.ours:not(.locked)').forEach(function (el) {
      el.setAttribute('data-theme', mode);
      el.classList.remove('ours-dark', 'ours-light');
      el.classList.add(mode === 'dark' ? 'ours-dark' : 'ours-light');
    });
    document.querySelectorAll('.theme-switch button').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.mode === mode));
    });
    try { localStorage.setItem('ours-v7-theme', mode); } catch (e) { /* file:// with storage off */ }
  }

  function mount() {
    var box = document.createElement('div');
    box.className = 'theme-switch';
    box.setAttribute('role', 'group');
    box.setAttribute('aria-label', 'Preview theme');
    box.innerHTML =
      '<button data-mode="dark" aria-pressed="true">Dark</button>' +
      '<button data-mode="light" aria-pressed="false">Light</button>';
    box.addEventListener('click', function (e) {
      var b = e.target.closest('button');
      if (b) apply(b.dataset.mode);
    });
    document.body.appendChild(box);

    var saved = null;
    try { saved = localStorage.getItem('ours-v7-theme'); } catch (e) { /* ignore */ }
    apply(saved === 'light' ? 'light' : 'dark');
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
})();
