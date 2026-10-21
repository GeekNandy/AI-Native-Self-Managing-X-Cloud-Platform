import { api, esc, pretty, toast, fail } from "/app.js";
const $ = (selector) => document.querySelector(selector);
const state = {
  config: null,
  tasks: [],
  repositories: [],
  runs: [],
  comparisons: [],
  run: null,
  role: null,
  busy: false,
};
let poll;
const terminal = (status) =>
  !["QUEUED", "RUNNING", "APPLYING"].includes(status);
const active = () =>
  state.runs.some((r) => !terminal(r.status)) ||
  state.comparisons.some((c) => ["QUEUED", "RUNNING"].includes(c.status));

function download(value, name) {
  const url = URL.createObjectURL(new Blob([value], { type: "text/plain" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}
function renderRun() {
  const r = state.run;
  if (!r) return;
  $("#code-result").innerHTML =
    `<div class="section-heading"><h2>${esc(r.status.replaceAll("_", " ").toLowerCase())}</h2><span class="badge ${r.status === "APPROVED" ? "success" : "purple"}">${r.attempts}/${r.maxAttempts} attempts</span></div>
    <p>${esc(r.detail)}</p><dl class="code-facts"><div><dt>Base commit</dt><dd><code>${esc(r.baseCommit.slice(0, 12))}</code></dd></div><div><dt>Provider / policy</dt><dd>${esc(r.provider)} / ${esc(r.policyVersion)}</dd></div><div><dt>Baseline → verification</dt><dd>${esc(r.baseline?.status || "Pending")} → ${esc(r.verification?.status || "Pending")}</dd></div></dl>
    ${r.patch ? `<h3>Source diff</h3><pre class="code-output patch-output"><code>${esc(r.patch)}</code></pre><h3>Reviewed digest</h3><code class="digest">${esc(r.digest)}</code><div class="code-actions"><button id="code-review" class="button primary" ${r.status !== "AWAITING_REVIEW" || state.role !== "operator" ? "disabled" : ""}>Review & commit patch</button><button id="code-download" class="button secondary">Download patch</button></div>` : ""}
    ${r.approvedCommit ? `<p class="badge success">Committed ${esc(r.approvedCommit.slice(0, 12))}</p>` : ""}
    ${!terminal(r.status) && state.role === "operator" ? '<button id="code-cancel" class="button secondary">Cancel repair</button>' : ""}
    <h3>Operation trace</h3>${r.steps.length ? r.steps.map((step) => `<details class="code-step" ${step.outcome === "FAIL" ? "open" : ""}><summary>${step.sequence}. ${esc(step.tool)} · ${esc(step.outcome)} · ${step.durationMs} ms</summary><pre class="code-output">${esc(step.output || "Completed")}</pre></details>`).join("") : '<p class="footnote">Waiting for the first tool operation.</p>'}`;
}
function renderComparisons() {
  $("#code-comparisons").innerHTML = state.comparisons
    .map(
      (c) =>
        `<article class="comparison"><div class="section-heading"><strong>${esc(c.status)} · ${esc(c.candidate.version)}</strong><span class="badge ${c.promotable ? "success" : "neutral"}">${c.promotable ? "Promotion gate passed" : "Promotion unavailable"}</span></div><p>${esc(c.detail)}</p>${c.trials.length ? `<div class="table-scroll"><table><thead><tr><th>Task</th><th>Variant</th><th>Result</th><th>Attempts</th><th>Time</th></tr></thead><tbody>${c.trials.map((t) => `<tr><td>${esc(t.taskId)}</td><td>${esc(t.variant)}</td><td>${esc(t.outcome)}</td><td>${t.attempts}</td><td>${(t.durationMs / 1000).toFixed(1)} s</td></tr>`).join("")}</tbody></table></div>` : ""}<p class="footnote">${Object.entries(
          c.safety,
        )
          .map(([key, value]) => `${esc(key)}: ${value ? "PASS" : "FAIL"}`)
          .join(
            " · ",
          )}</p><div class="code-actions"><button class="button secondary" data-comparison-export="${esc(c.id)}">Export comparison</button>${c.promotable && state.role === "operator" ? `<button class="button primary" data-promote="${esc(c.id)}">Promote evaluated candidate</button>` : ""}</div></article>`,
    )
    .join("");
}
function renderHistory() {
  $("#code-runs").innerHTML = state.runs.length
    ? state.runs
        .map(
          (r) =>
            `<button class="code-run-row" data-run="${esc(r.id)}"><span>${esc(r.instruction.slice(0, 90))}<small>${esc(r.provider)} · ${new Date(r.createdAt).toLocaleString()}</small></span><span class="badge ${r.status === "APPROVED" ? "success" : "neutral"}">${esc(r.status.replaceAll("_", " "))}</span></button>`,
        )
        .join("")
    : '<p class="footnote">No repository runs yet.</p>';
}
async function refresh() {
  clearTimeout(poll);
  [state.runs, state.comparisons] = await Promise.all([
    api("/code/runs"),
    api("/code/comparisons"),
  ]);
  if (state.run)
    state.run = state.runs.find((r) => r.id === state.run.id) || state.run;
  else state.run = state.runs[0] || null;
  renderRun();
  renderHistory();
  renderComparisons();
  if (active()) poll = setTimeout(() => refresh().catch(fail), 1500);
}
async function loadRepositories(selected) {
  state.repositories = await api("/code/repositories");
  const previous = selected || $("#code-repository").value;
  $("#code-repository").innerHTML =
    '<option value="">Select a repository</option>' +
    state.repositories
      .map(
        (r) =>
          `<option value="${esc(r.id)}">${esc(r.name)} · ${esc(r.head.slice(0, 8))}</option>`,
      )
      .join("");
  $("#code-repository").value = previous;
}
async function operation(action) {
  if (state.busy) return;
  state.busy = true;
  $("#code-start").disabled = true;
  try {
    await action();
  } catch (error) {
    fail(error);
  } finally {
    state.busy = false;
    $("#code-start").disabled = !state.config?.sandbox.available;
  }
}
async function initialize() {
  try {
    [state.config, state.tasks, { role: state.role }] = await Promise.all([
      api("/code/config"),
      api("/code/tasks"),
      api("/session"),
    ]);
    $("#code-task").innerHTML = state.tasks
      .map((t) => `<option value="${esc(t.id)}">${esc(t.name)}</option>`)
      .join("");
    $("#code-instruction").value = state.tasks[0].instruction;
    $("#code-engine").innerHTML = state.config.providers
      .map(
        (p) =>
          `<option value="${esc(p)}">${p === "gemini" ? "Gemini structured patch" : p === "nebius" ? "Nebius structured patch" : "Bounded mutation search"}</option>`,
      )
      .join("");
    $("#code-notice").textContent = state.config.sandbox.available
      ? `Isolated runner ready · No network · 384 MiB · 1 CPU · Active search policy: ${state.config.activePolicy.version}`
      : "Docker runner unavailable. Start Docker and run: docker pull eclipse-temurin:21-jdk. You can inspect task repositories now; execution requires the isolated runner.";
    $("#code-start").disabled = !state.config.sandbox.available;
    $("#code-create").disabled = state.role !== "operator";
    $("#code-import-file").disabled = state.role !== "operator";
    $("#code-compare").disabled =
      !state.config.sandbox.available || state.role !== "operator";
    await loadRepositories();
    await refresh();
  } catch (error) {
    fail(error);
  }
}
document.addEventListener("workspace-connected", initialize);
$("#code-task").addEventListener("change", () => {
  $("#code-instruction").value = state.tasks.find(
    (t) => t.id === $("#code-task").value,
  ).instruction;
});
$("#code-engine").addEventListener("change", () => {
  const live = $("#code-engine").value !== "mutation-search";
  $("#code-attempts").max = live ? "3" : "12";
  if (live) $("#code-attempts").value = "2";
  $("#code-provider").textContent = live
    ? $("#code-engine").value === "nebius"
      ? "Nebius proposals"
      : "Gemini proposals"
    : "Mutation search";
});
$("#code-create").addEventListener("click", () =>
  operation(async () => {
    const r = await api("/code/repositories/catalog", {
      taskId: $("#code-task").value,
    });
    await loadRepositories(r.id);
    toast("Task repository created. The original tests are unchanged.");
  }),
);
$("#code-template").addEventListener("click", () => {
  const t = state.tasks.find((t) => t.id === $("#code-task").value);
  download(
    pretty({ name: t.name, files: t.files, testClass: t.testClass }),
    "source-snapshot.json",
  );
});
$("#code-import-file").addEventListener("change", (event) =>
  operation(async () => {
    const f = event.target.files[0];
    if (!f) return;
    if (f.size > 250000) throw new Error("Source snapshot exceeds 250 KB.");
    const r = await api("/code/repositories", JSON.parse(await f.text()));
    await loadRepositories(r.id);
    event.target.value = "";
    toast("Source snapshot imported into a managed Git repository.");
  }),
);
$("#code-start").addEventListener("click", () =>
  operation(async () => {
    if (!$("#code-repository").value)
      throw new Error("Create or select a task repository first.");
    state.run = await api("/code/runs", {
      repositoryId: $("#code-repository").value,
      instruction: $("#code-instruction").value,
      provider: $("#code-engine").value,
      maxAttempts: Number($("#code-attempts").value),
      timeoutSeconds: Number($("#code-timeout").value),
    });
    await refresh();
  }),
);
$("#code-refresh").addEventListener("click", () =>
  operation(async () => {
    await initialize();
  }),
);
$("#code-compare").addEventListener("click", () =>
  operation(async () => {
    await api("/code/comparisons", {});
    await refresh();
  }),
);
$("#code-approval-check").addEventListener("change", () => {
  $("#code-approval-confirm").disabled = !$("#code-approval-check").checked;
});
$("#code-approval-confirm").addEventListener("click", () =>
  operation(async () => {
    $("#code-approval-confirm").disabled = true;
    state.run = await api(`/code/runs/${state.run.id}/approve`, {
      digest: state.run.digest,
    });
    $("#code-approval").close();
    await loadRepositories();
    await refresh();
    toast("Reviewed patch committed.");
  }),
);
document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button || button.disabled) return;
  if (button.dataset.run) {
    state.run = state.runs.find((r) => r.id === button.dataset.run);
    renderRun();
  }
  if (button.id === "code-cancel")
    void operation(async () => {
      await api(`/code/runs/${state.run.id}/cancel`, {});
      await refresh();
    });
  if (button.id === "code-review") {
    $("#code-approval-digest").textContent = state.run.digest;
    $("#code-approval-check").checked = false;
    $("#code-approval-confirm").disabled = true;
    $("#code-approval").showModal();
  }
  if (button.id === "code-download")
    download(state.run.patch, `repair-${state.run.id}.patch`);
  if (button.dataset.comparisonExport)
    download(
      pretty(
        state.comparisons.find((c) => c.id === button.dataset.comparisonExport),
      ),
      "repair-comparison.json",
    );
  if (button.dataset.promote)
    void operation(async () => {
      await api(`/code/comparisons/${button.dataset.promote}/promote`, {});
      toast("Evaluated search policy promoted.");
      await initialize();
    });
});

export { state as codeState };
