import { state, api, esc, pretty, renderEvaluations, toast } from "/app.js";
import { codeState } from "/code.js";
const $ = (s) => document.querySelector(s);
const icon = (name) =>
  `<svg class="material-icon" aria-hidden="true"><use href="/assets/material-symbols.svg#${name}"></use></svg>`;
const pages = [
  [
    "workbench",
    "Agent workbench",
    "Compile intent and review policy",
    "terminal",
    "Build",
  ],
  [
    "code",
    "Repository agent",
    "Repair Java sources with isolated tests",
    "code",
    "Repair",
  ],
  [
    "environments",
    "Environments",
    "Inspect state, drift and recovery",
    "domain",
    "Operate",
  ],
  [
    "evaluations",
    "Evaluations",
    "Measure behavior and regressions",
    "science",
    "Evaluate",
  ],
  [
    "activity",
    "Activity log",
    "Find decisions and correlated operations",
    "history",
    "Activity",
  ],
];
const glyphs = {
  "◈": "account_tree",
  "▦": "domain",
  "◎": "science",
  "≋": "history",
  "⌘": "code",
  "⌁": "compare_arrows",
  "◇": "check_circle",
  "△": "shield",
  "?": "help",
  i: "info",
  "↻": "refresh",
  "✓": "check",
  "×": "close",
  "↗": "arrow_forward",
  "↓": "download",
};
function text(el, value) {
  if (el && el.textContent !== String(value)) el.textContent = value;
}
function save(content, name) {
  const u = URL.createObjectURL(
    new Blob([pretty(content)], { type: "application/json" }),
  );
  const a = document.createElement("a");
  a.href = u;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(u), 1000);
}
for (const [id, label, , symbol, mobile] of pages) {
  const link = $(`[data-view="${id}"]`);
  link.setAttribute("aria-label", label);
  const count =
    id === "environments"
      ? '<span id="nav-environments" class="nav-count">0</span>'
      : "";
  link.innerHTML = `<span class="nav-icon">${icon(symbol)}</span><span class="nav-text">${label}</span><span class="mobile-nav-label" aria-hidden="true">${mobile}</span>${count}`;
}
const skip = document.createElement("a");
skip.href = "#main-content";
skip.className = "skip-link";
skip.textContent = "Skip to content";
document.body.prepend(skip);
$("main").id = "main-content";
$("main").tabIndex = -1;
skip.addEventListener("click", (e) => {
  e.preventDefault();
  $("main").focus();
});
$(".sidebar-footer>div").innerHTML =
  '<strong id="session-name">Local session</strong><small id="session-scope">Connect to begin</small>';
$(".sidebar-footer").insertAdjacentHTML(
  "beforeend",
  `<button id="disconnect-workspace" class="icon-button" aria-label="Disconnect workspace" title="Disconnect this tab; server runs continue">${icon("logout")}</button>`,
);
$(".topbar-right").insertAdjacentHTML(
  "afterbegin",
  `<button id="navigate-workspace" class="jump-button" aria-label="Navigate workspace">${icon("search")}<span>Go to…</span><kbd>Ctrl K</kbd></button>`,
);
$(".topbar-right").insertAdjacentHTML(
  "beforeend",
  `<button id="theme-toggle" class="icon-button" aria-label="Switch to dark theme">${icon("dark_mode")}</button><button id="mobile-disconnect" class="icon-button mobile-disconnect" aria-label="Disconnect workspace">${icon("logout")}</button>`,
);
function disconnect() {
  sessionStorage.removeItem("workbench-token");
  location.reload();
}
$("#disconnect-workspace").addEventListener("click", disconnect);
$("#mobile-disconnect").addEventListener("click", disconnect);
function themeControl() {
  const dark = document.documentElement.dataset.theme === "dark";
  $("#theme-toggle").setAttribute(
    "aria-label",
    `Switch to ${dark ? "light" : "dark"} theme`,
  );
  $("#theme-toggle").innerHTML = icon(dark ? "light_mode" : "dark_mode");
  $('meta[name="theme-color"]').content = dark ? "#121318" : "#f8f9fc";
}
$("#theme-toggle").addEventListener("click", () => {
  const theme =
    document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem("workbench-theme", theme);
  } catch {}
  themeControl();
});
themeControl();
const nav = document.createElement("dialog");
nav.id = "navigation-dialog";
nav.setAttribute("aria-labelledby", "navigation-label");
nav.innerHTML = `<div class="navigation-search">${icon("search")}<label id="navigation-label" for="navigation-search" class="sr-only">Navigate workspace</label><input id="navigation-search" type="search" placeholder="Go to a workspace page…" autocomplete="off"/><button class="icon-button" id="close-navigation" aria-label="Close navigation">${icon("close")}</button></div><div id="navigation-results"></div><div class="navigation-footer">↑ ↓ to navigate · Enter to open · Esc to close</div>`;
document.body.append(nav);
let matches = pages,
  index = 0;
function navigation() {
  const q = $("#navigation-search").value.toLowerCase();
  matches = pages.filter((p) => p.join(" ").toLowerCase().includes(q));
  index = Math.max(0, Math.min(index, matches.length - 1));
  $("#navigation-results").innerHTML = matches.length
    ? matches
        .map(
          ([id, label, desc, symbol], i) =>
            `<a href="#${id}" class="navigation-result ${index === i ? "highlighted" : ""}">${icon(symbol)}<span><strong>${label}</strong><small>${desc}</small></span>${icon("arrow_forward")}</a>`,
        )
        .join("")
    : '<p class="filter-empty" role="status">No matching pages. Try “repair”, “state” or “checks”.</p>';
}
function openNav() {
  if (document.querySelector("dialog[open]")) return;
  index = 0;
  $("#navigation-search").value = "";
  navigation();
  nav.showModal();
  $("#navigation-search").focus();
}
$("#navigate-workspace").addEventListener("click", openNav);
$("#close-navigation").addEventListener("click", () => nav.close());
$("#navigation-search").addEventListener("input", () => {
  index = 0;
  navigation();
});
nav.addEventListener("click", (e) => {
  if (e.target.closest("a")) nav.close();
});
$("#navigation-search").addEventListener("keydown", (e) => {
  if (["ArrowDown", "ArrowUp"].includes(e.key)) {
    e.preventDefault();
    index = matches.length
      ? (index + (e.key === "ArrowDown" ? 1 : -1) + matches.length) %
        matches.length
      : 0;
    navigation();
  }
  if (e.key === "Enter" && matches[index]) {
    e.preventDefault();
    nav.close();
    location.hash = matches[index][0];
  }
});
function selectedView(focus = false) {
  const id = pages.some((p) => p[0] === location.hash.slice(1))
    ? location.hash.slice(1)
    : "workbench";
  for (const link of document.querySelectorAll("[data-view]")) {
    if (link.dataset.view === id) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  document.title = `${pages.find((p) => p[0] === id)[1]} · X Cloud`;
  if (focus) {
    const h = $(`#view-${id} h1`);
    h.tabIndex = -1;
    h.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
}
window.addEventListener("hashchange", () => selectedView(true));
selectedView();
for (const d of document.querySelectorAll("dialog:not(#navigation-dialog)")) {
  const h = d.querySelector("h2");
  if (h) {
    if (!h.id) h.id = d.id + "-title";
    d.setAttribute("aria-labelledby", h.id);
  }
}
function toolbar(target, html) {
  const bar = document.createElement("div");
  bar.className = "filter-bar";
  bar.innerHTML = html;
  $(target).before(bar);
  return bar;
}
toolbar(
  "#environments-content",
  '<input id="environment-search" type="search" aria-label="Find an environment" placeholder="Find an environment"/><select id="environment-status" aria-label="Environment status"><option value="all">All states</option><option value="Converged">Converged</option><option value="drifted">Drift detected</option><option value="unknown">Observation unknown</option></select><span id="environment-count" class="filter-count" role="status"></span>',
);
toolbar(
  "#activity-content",
  `<input id="activity-search" type="search" aria-label="Search activity" placeholder="Search events, environments or correlations"/><select id="activity-type" aria-label="Event type"><option value="all">All event types</option></select><button id="activity-export" class="button secondary small filter-action">${icon("download")}Export events</button><span id="activity-count" class="filter-count" role="status"></span>`,
);
const history = toolbar(
  "#evaluation-content",
  '<label for="evaluation-history">Evaluation run</label><select id="evaluation-history"></select><select id="evaluation-filter" aria-label="Evaluation result"><option value="all">All results</option><option value="PASS">Passed checks</option><option value="FAIL">Failed checks</option></select>',
);
history.hidden = true;
toolbar(
  "#code-runs",
  '<input id="code-run-search" type="search" aria-label="Search repair runs" placeholder="Search repair runs"/><select id="code-run-status" aria-label="Repair status"><option value="all">All statuses</option><option value="AWAITING_REVIEW">Awaiting review</option><option value="APPROVED">Approved</option><option value="RUNNING">Running</option><option value="FAILED">Failed</option></select><span id="code-run-count" class="filter-count" role="status"></span>',
);
let reportKey = "",
  eventKey = "";
function filterViews() {
  const env = [...document.querySelectorAll(".environment-card")],
    q = $("#environment-search").value.toLowerCase(),
    status = $("#environment-status").value;
  for (const el of env)
    el.hidden =
      !el.querySelector("h2").textContent.toLowerCase().includes(q) ||
      (status !== "all" &&
        !el
          .querySelector(".badge")
          .textContent.toLowerCase()
          .includes(status.toLowerCase()));
  text(
    $("#environment-count"),
    `${env.filter((e) => !e.hidden).length} of ${env.length} environments`,
  );
  const types = [...new Set(state.audit.map((e) => e.type))].sort(),
    key = types.join("|");
  if (key !== eventKey) {
    eventKey = key;
    const v = $("#activity-type").value;
    $("#activity-type").innerHTML =
      '<option value="all">All event types</option>' +
      types.map((t) => `<option>${esc(t)}</option>`).join("");
    $("#activity-type").value = types.includes(v) ? v : "all";
  }
  const aq = $("#activity-search").value.toLowerCase(),
    at = $("#activity-type").value;
  const events = [...document.querySelectorAll("#activity-content tbody tr")];
  for (const row of events)
    row.hidden =
      !row.textContent.toLowerCase().includes(aq) ||
      (at !== "all" && row.querySelector(".event-type")?.textContent !== at);
  text(
    $("#activity-count"),
    `${events.filter((e) => !e.hidden).length} of ${events.length} events`,
  );
  $("#activity-export").disabled = !events.some((e) => !e.hidden);
  const rkey = state.reports.map((r) => r.id).join("|");
  history.hidden = !state.reports.length;
  if (rkey !== reportKey) {
    reportKey = rkey;
    $("#evaluation-history").innerHTML = state.reports
      .map(
        (r, i) =>
          `<option value="${esc(r.id)}">${i ? "" : "Latest · "}${esc(new Date(r.createdAt).toLocaleString())} · ${r.passed}/${r.total}</option>`,
      )
      .join("");
  }
  if (state.report) $("#evaluation-history").value = state.report.id;
  for (const row of document.querySelectorAll("#evaluation-content tbody tr"))
    row.hidden =
      $("#evaluation-filter").value !== "all" &&
      row.querySelector(".badge")?.textContent !==
        $("#evaluation-filter").value;
  const rq = $("#code-run-search").value.toLowerCase(),
    rs = $("#code-run-status").value,
    rows = [...document.querySelectorAll("#code-runs [data-run]")];
  for (const row of rows) {
    const run = codeState.runs.find((r) => r.id === row.dataset.run);
    row.hidden =
      !row.textContent.toLowerCase().includes(rq) ||
      (rs !== "all" && run?.status !== rs);
  }
  text(
    $("#code-run-count"),
    `${rows.filter((r) => !r.hidden).length} of ${rows.length} runs`,
  );
}
for (const id of ["environment-search", "activity-search", "code-run-search"])
  $("#" + id).addEventListener("input", filterViews);
for (const id of [
  "environment-status",
  "activity-type",
  "evaluation-filter",
  "code-run-status",
])
  $("#" + id).addEventListener("change", filterViews);
$("#evaluation-history").addEventListener("change", () => {
  state.report = state.reports.find(
    (r) => r.id === $("#evaluation-history").value,
  );
  renderEvaluations();
});
$("#activity-export").addEventListener("click", () =>
  save(
    [...state.audit].filter(
      (e) =>
        ($("#activity-type").value === "all" ||
          e.type === $("#activity-type").value) &&
        Object.values(e)
          .join(" ")
          .toLowerCase()
          .includes($("#activity-search").value.toLowerCase()),
    ),
    "workspace-events.json",
  ),
);
function decorate() {
  for (const table of document.querySelectorAll(".state-comparison")) {
    if (!table.parentElement.classList.contains("table-wrap")) {
      const wrap = document.createElement("div");
      wrap.className = "table-wrap";
      table.before(wrap);
      wrap.append(table);
    }
  }

  for (const el of document.querySelectorAll(
    ".empty-icon,.notice-symbol,.stat-decoration,.feedback-symbol,.dialog-icon,.check-icon,.example-button i,.pipeline-step i,button>span",
  )) {
    if (!el.children.length && glyphs[el.textContent.trim()])
      el.innerHTML = icon(glyphs[el.textContent.trim()]);
  }
  for (const pre of document.querySelectorAll(
    ".patch-output:not([data-highlighted])",
  )) {
    const code = pre.querySelector("code") || pre;
    const source = code.textContent;
    pre.dataset.highlighted = "true";
    code.innerHTML = source
      .split("\n")
      .map(
        (line) =>
          `<span class="diff-line ${line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ? "file" : line.startsWith("@@") ? "hunk" : line.startsWith("+") ? "added" : line.startsWith("-") ? "removed" : "context"}">${esc(line) || " "}</span>`,
      )
      .join("");
    pre.tabIndex = 0;
    pre.setAttribute("aria-label", "Verified source diff");
  }
  for (const tab of document.querySelectorAll("[role=tab]"))
    tab.tabIndex = tab.getAttribute("aria-selected") === "true" ? 0 : -1;
  if (state.config?.dataRetention === "ephemeral" && !$("#retention-notice")) {
    $("main").insertAdjacentHTML(
      "afterbegin",
      `<div id="retention-notice" class="mode-notice role-notice">${icon("info")}<span><strong>Hosted demo storage</strong> · State can reset when this instance restarts or redeploys. Export evidence you want to keep.</span></div>`,
    );
  }
  const agent = state.role === "agent";
  for (const el of document.querySelectorAll(
    "#review-button,[data-fault],#feedback-save,#evaluate-controls,#evaluate-interpreter,#refresh-environments",
  ))
    if (agent) {
      el.disabled = true;
      el.title = "Requires an operator credential";
    }
  text(
    $("#session-name"),
    state.role === "operator"
      ? "Workspace operator"
      : agent
        ? "Workspace agent"
        : "Local session",
  );
  text($("#session-scope"), agent ? "Inspect & propose" : "Review & execute");
  if (state.config?.authentication === "username-password") {
    $("#disconnect-workspace").hidden = true;
    $("#mobile-disconnect").hidden = true;
  }
  if (agent && !$("#role-notice")) {
    $("main").insertAdjacentHTML(
      "afterbegin",
      `<div id="role-notice" class="mode-notice role-notice">${icon("lock")}<span><strong>Agent access</strong> · Inspection and proposals. Execution, commits, evaluations and promotions require an operator.</span></div>`,
    );
  }
  if (!agent) $("#role-notice")?.remove();
  filterViews();
}
let scheduled = false;
new MutationObserver(() => {
  if (!scheduled) {
    scheduled = true;
    queueMicrotask(() => {
      scheduled = false;
      decorate();
    });
  }
}).observe($("main"), { childList: true, subtree: true });
document.addEventListener("workspace-connected", decorate);
decorate();
document.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    if (nav.open) nav.close();
    else openNav();
  }
  const tab = e.target.closest("[role=tab]");
  if (!tab || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key))
    return;
  const tabs = [
    ...tab.closest("[role=tablist]").querySelectorAll("[role=tab]"),
  ];
  let i = tabs.indexOf(tab);
  i =
    e.key === "Home"
      ? 0
      : e.key === "End"
        ? tabs.length - 1
        : (i + (e.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
  e.preventDefault();
  tabs[i].click();
});
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-tab]");
  if (b)
    document
      .querySelector(`[data-tab="${b.dataset.tab}"]`)
      ?.focus({ preventScroll: true });
});
$("#toast").addEventListener("click", () => {
  $("#toast").hidden = true;
});
