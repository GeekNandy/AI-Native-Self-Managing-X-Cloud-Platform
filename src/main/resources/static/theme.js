(() => {
  let p;
  try {
    p = localStorage.getItem("workbench-theme");
  } catch {}
  document.documentElement.dataset.theme =
    p ||
    (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
})();
