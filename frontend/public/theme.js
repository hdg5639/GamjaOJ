// Applies the saved or system color theme before the first paint (loaded synchronously in <head>).
(function () {
  var theme = 'light';
  try {
    theme = localStorage.getItem('gamjaoj-theme');
    if (theme !== 'light' && theme !== 'dark') theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch (e) { theme = 'light'; }
  document.documentElement.setAttribute('data-theme', theme);
})();
