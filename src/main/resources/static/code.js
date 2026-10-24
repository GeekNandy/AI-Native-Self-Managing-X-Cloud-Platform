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
  files: {},
  file: null,
  provenance: null,
  benchmarks: [],
  policies: null,
  modelCalls: [],
  publication: null,
  publicationPreview: null,
};
let poll;
const terminal = (status) =>
  !["QUEUED", "RUNNING", "APPLYING"].includes(status);
const active = () =>
  state.runs.some((r) => !terminal(r.status)) ||
  state.comparisons.some((c) => ["QUEUED", "RUNNING"].includes(c.status)) || state.benchmarks.some(b => ["QUEUED", "RUNNING"].includes(b.status));

function download(value, name) {
  const url = URL.createObjectURL(new Blob([value], { type: "text/plain" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}
function diffView(patch) {
  let before = 0, after = 0;
  return `<div class="diff-view patch-output" data-highlighted="true" tabindex="0" role="region" aria-label="Candidate source diff">${patch.split("\n").map(line => {
    let old = "", next = "", kind = "";
    const hunk = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)/);
    if (hunk) { before = Number(hunk[1]); after = Number(hunk[2]); kind = "diff-header"; }
    else if (line.startsWith("---") || line.startsWith("+++")) kind = "diff-header";
    else if (line.startsWith("-")) { old = before++; kind = "diff-remove"; }
    else if (line.startsWith("+")) { next = after++; kind = "diff-add"; }
    else if (line.startsWith(" ")) { old = before++; next = after++; }
    else kind = "diff-header";
    return `<div class="diff-row ${kind}"><span aria-hidden="true">${old}</span><span aria-hidden="true">${next}</span><code>${esc(line)}</code></div>`;
  }).join("")}</div>`;
}
function renderSource() {
  const paths = Object.keys(state.files).sort();
  if (!paths.includes(state.file)) state.file = paths.find(p => p.startsWith("src/main/")) || paths[0] || null;
  $("#code-files").innerHTML = paths.length ? paths.map(path => `<button class="code-file" data-source-file="${esc(path)}" aria-current="${path === state.file}">${esc(path.split("/").pop())}<small>${path.startsWith("src/test/") ? "TEST · IMMUTABLE" : path === "pom.xml" ? "BUILD · IMMUTABLE" : "SOURCE"}</small></button>`).join("") : '<p class="footnote">No files loaded</p>';
  $("#code-file-name").textContent = state.file || "Select a file";
  $("#code-file-access").textContent = state.file?.startsWith("src/main/") ? "Source · read only" : "Immutable input";
  $("#code-source").innerHTML = state.file ? state.files[state.file].split("\n").map((line, i) => `<span class="source-line"><span aria-hidden="true">${i + 1}</span><code>${esc(line) || " "}</code></span>`).join("") : '<code>Source, tests and build configuration appear here.</code>';
}
async function loadSource() {
  const id = $("#code-repository").value;
  if (!id) { state.files = {}; state.provenance = null; renderSource(); return; }
  const [files, provenance] = await Promise.all([api(`/code/repositories/${id}/files`), api(`/code/repositories/${id}/provenance`)]);
  if (id !== $("#code-repository").value) return;
  state.files = files; state.provenance = provenance;
  $("#code-profile").textContent = provenance.profile || (files["pom.xml"] ? "java21-junit6-v1" : "java21-main-v1");
  const repo = state.repositories.find(r => r.id === id);
  $("#code-repository-meta").textContent = provenance.pinnedCommit ? `${provenance.repository} @ ${provenance.pinnedCommit.slice(0, 12)} · ${provenance.subdirectory || "root"} · ${Object.keys(files).length} files · tests/build ${provenance.immutableInputsDigest.slice(0, 12)}` : `${repo?.name || "Managed repository"} · HEAD ${repo?.head.slice(0, 12)} · ${Object.keys(files).length} files · candidate changes appear below`;
  renderSource();
}
function renderRunner() {
  const runner = state.config.sandbox;
  $("#code-notice").innerHTML = runner.available ? `Isolated runner ready · ${esc(runner.mode === "remote" ? "Authenticated remote workers" : "Local Docker")} · No network or host mounts · 1 CPU · Policy ${esc(state.config.activePolicy.version)}` : runner.mode === "remote" ? `No worker connected. Start a bounded execution window, then this workspace reconnects automatically. <a class="worker-link" href="https://github.com/GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform/actions/workflows/repair-worker.yml" target="_blank" rel="noopener noreferrer">Start worker on GitHub ↗</a>` : "Docker runner unavailable. Preload the Java 21 image to run isolated repairs.";
  $("#code-start").disabled = state.busy || !runner.available;
  $("#code-compare").disabled = state.busy || !runner.available || state.role !== "operator";
  $("#code-benchmark-start").disabled = state.busy || !runner.available || state.role !== "operator" || state.benchmarks.some(b => ["QUEUED","RUNNING"].includes(b.status));
  $("#code-policy-derive").disabled = state.role !== "operator";
}
function renderRun() {
  const r = state.run;
  if (!r) return;
  $("#code-result").innerHTML =
    `<div class="section-heading"><h2>${esc(r.status.replaceAll("_", " ").toLowerCase())}</h2><span class="badge ${r.status === "APPROVED" ? "success" : "purple"}">${r.attempts}/${r.maxAttempts} attempts</span></div>
    <p>${esc(r.detail)}</p><div class="run-evidence"><div class="${r.baseline?.status === "FAIL" ? "evidence-fail" : ""}"><small>Baseline</small><strong>${esc(r.baseline?.status || "Pending")}</strong><small>${r.baseline ? (r.baseline.durationMs / 1000).toFixed(2) + " seconds" : "Isolated test run"}</small></div><div class="${r.verification?.passed || r.verification?.status === "PASS" ? "evidence-pass" : ""}"><small>Verification</small><strong>${esc(r.verification?.status || "Pending")}</strong><small>${r.verification ? (r.verification.durationMs / 1000).toFixed(2) + " seconds" : "After patch validation"}</small></div><div><small>Run budget</small><strong>${r.timeoutSeconds} s</strong><small>${r.attempts} / ${r.maxAttempts} attempts used</small></div></div><dl class="code-facts"><div><dt>Base commit</dt><dd><code>${esc(r.baseCommit.slice(0, 12))}</code></dd></div><div><dt>Provider / policy</dt><dd>${esc(r.provider)} / ${esc(r.policyVersion)}</dd></div><div><dt>Baseline → verification</dt><dd>${esc(r.baseline?.status || "Pending")} → ${esc(r.verification?.status || "Pending")}</dd></div></dl>
    ${r.patch ? `<h3>Source diff</h3>${diffView(r.patch)}<h3>Reviewed digest</h3><code class="digest">${esc(r.digest)}</code><div class="code-actions"><button id="code-review" class="button primary" ${r.status !== "AWAITING_REVIEW" || state.role !== "operator" ? "disabled" : ""}>Review & commit patch</button><button id="code-download" class="button secondary">Download patch</button></div>` : ""}
    ${r.approvedCommit ? `<p class="badge success">Committed ${esc(r.approvedCommit.slice(0, 12))}</p><div class="code-actions"><button id="code-publish-preview" class="button secondary" ${!state.config.publication?.enabled || state.role !== "operator" ? "disabled" : ""}>Review draft PR publication</button>${state.publication?.pullRequestUrl ? `<a class="button primary" href="${esc(state.publication.pullRequestUrl)}" target="_blank" rel="noopener noreferrer">Open draft pull request ↗</a>` : ""}</div><p class="footnote">Publication requires a pinned GitHub import, a fixed destination and a private write credential.</p>` : ""}
    ${modelUsage()}
    ${!terminal(r.status) && state.role === "operator" ? '<button id="code-cancel" class="button secondary">Cancel repair</button>' : ""}
    <h3>Operation trace</h3><div class="tool-timeline">${r.steps.length ? r.steps.map((step) => `<details class="code-step" ${step.outcome === "FAIL" ? "open" : ""}><summary>${step.sequence}. ${esc(step.tool)} · ${esc(step.outcome)} · ${step.durationMs} ms</summary><pre class="code-output" tabindex="0" role="region" aria-label="${step.sequence}. ${esc(step.tool)} output">${esc(step.output || "Completed")}</pre></details>`).join("") : '<p class="footnote">Waiting for the first tool operation.</p>'}</div>`;
}
function modelUsage() {
  if (!state.modelCalls.length) return "";
  const count = n => n < 0 ? "Unreported" : n.toLocaleString();
  return `<h3>Inference receipts</h3><div class="table-scroll model-usage"><table><thead><tr><th>Call</th><th>Requested / reported model</th><th>Input / output tokens</th><th>Duration</th><th>Cost</th></tr></thead><tbody>${state.modelCalls.map(c => `<tr><td>${c.sequence}</td><td>${esc(c.usage.requestedModel)}<br>${esc(c.usage.reportedModel)}</td><td>${count(c.usage.inputTokens)} / ${count(c.usage.outputTokens)}</td><td>${(c.durationMs / 1000).toFixed(2)} s</td><td>Unpriced</td></tr>`).join("")}</tbody></table></div>`;
}
async function loadPolicies(candidate) {
  state.policies = await api("/code/agent-policies");
  const options = state.policies.policies.map(p => `<option value="${esc(p.version)}">${esc(p.version)} · ${p.trainingRunIds.length} feedback cases</option>`).join("");
  const before = $("#benchmark-candidate-policy").value;
  $("#benchmark-baseline-policy").innerHTML = options;
  $("#benchmark-candidate-policy").innerHTML = options;
  $("#benchmark-baseline-policy").value = state.policies.active.version;
  $("#benchmark-candidate-policy").value = candidate || before || state.policies.active.version;
  $("#code-policy-rollback").innerHTML = state.policies.previous.length ? state.policies.previous.map(p => `<option>${esc(p)}</option>`).join("") : '<option value="">No previous active version</option>';
  $("#code-policy-rollback-button").disabled = !state.policies.previous.length || state.role !== "operator";
}
function renderBenchmarks() {
  const container = $("#code-benchmark-reports");
  if (!state.benchmarks.length) { container.innerHTML = '<p class="footnote">No paired runs recorded. Results appear only after real execution.</p>'; return; }
  container.innerHTML = state.benchmarks.map(b => {
    const expected = 20 * b.configuration.repetitions;
    const variant = name => {
      const trials = b.trials.filter(t => t.variant === name), passed = trials.filter(t => t.outcome === "PASS").length;
      const times = trials.map(t => t.durationMs).sort((a,c) => a-c), p95 = times.length ? times[Math.ceil(times.length * .95)-1] / 1000 : null;
      const tokens = trials.some(t => t.totalTokens < 0) ? "Unreported" : trials.reduce((sum,t) => sum+t.totalTokens,0).toLocaleString();
      return `<div><span class="eyebrow">${name.toUpperCase()} · ${esc(name === "baseline" ? b.configuration.baselineProvider : b.configuration.candidateProvider)}</span><strong>${passed} / ${expected} passed</strong><meter min="0" max="${expected}" value="${passed}" aria-label="${name} passing trials">${passed}</meter><p class="footnote">${trials.length} completed · ${tokens} reported tokens · P95 ${p95 === null ? "Pending" : p95.toFixed(1) + " s"}</p></div>`;
    };
    return `<article class="comparison"><div class="section-heading"><h3>${esc(b.status)} · ${esc(b.baseline.version)} → ${esc(b.candidate.version)}</h3><span class="badge ${b.promotable ? "success" : "neutral"}">${b.promotable ? "Promotion gate passed" : "Active policy unchanged"}</span></div><p>${esc(b.detail)}</p><p class="footnote">${esc(b.datasetVersion)} · ${esc(b.datasetDigest.slice(0,16))} · ${esc(b.harnessVersion)} · ${esc(b.contextPolicy)}</p><div class="benchmark-summary">${variant("baseline")}${variant("candidate")}</div><ul class="benchmark-gates" aria-label="Promotion gates">${Object.entries(b.safety).map(([gate,pass]) => `<li>${pass ? "PASS" : "FAIL"} · ${esc(gate)}</li>`).join("")}</ul><details><summary>Inspect ${b.trials.length} recorded trials</summary><div class="benchmark-trials"><table><thead><tr><th>Task</th><th>Variant</th><th>Result</th><th>Attempts</th><th>Time</th><th>Evidence</th></tr></thead><tbody>${b.trials.map(t => `<tr><td>${esc(t.taskId)} · ${t.repetition}</td><td>${esc(t.variant)}</td><td>${esc(t.outcome)}</td><td>${t.attempts}</td><td>${(t.durationMs/1000).toFixed(1)} s</td><td><button class="button text-button" data-benchmark-run="${esc(t.runId)}">Open run</button></td></tr>`).join("")}</tbody></table></div></details><div class="code-actions"><button class="button secondary" data-benchmark-export="${esc(b.id)}">Export benchmark</button>${["QUEUED","RUNNING"].includes(b.status) && state.role === "operator" ? `<button class="button secondary" data-benchmark-cancel="${esc(b.id)}">Cancel benchmark</button>` : ""}${b.promotable && state.role === "operator" ? `<button class="button primary" data-benchmark-promote="${esc(b.id)}">Promote evaluated agent policy</button>` : ""}</div></article>`;
  }).join("");
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
  [state.runs, state.comparisons, state.config, state.benchmarks] = await Promise.all([
    api("/code/runs"), api("/code/comparisons"),
    state.config?.sandbox.mode === "remote" ? api("/code/config") : Promise.resolve(state.config), api("/code/benchmarks"),
  ]);
  renderRunner();
  if (state.run)
    state.run = state.runs.find((r) => r.id === state.run.id) || state.run;
  else state.run = state.runs[0] || null;
  if (state.run) [state.modelCalls, state.publication] = await Promise.all([api(`/code/runs/${state.run.id}/model-calls`), api(`/code/runs/${state.run.id}/publication`)]);
  renderRun();
  renderHistory();
  renderComparisons();
  renderBenchmarks();
  if (active() || state.config?.sandbox.mode === "remote") poll = setTimeout(() => refresh().catch(fail), active() ? 1500 : 5000);
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
  await loadSource();
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
    renderRunner();
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
    $("#code-engine").value = state.config.providers.find(p => p !== "mutation-search") || "mutation-search";
    $("#code-engine").dispatchEvent(new Event("change"));
    renderRunner();
    $("#code-github-import").disabled = state.role !== "operator";
    $("#code-load-sample").disabled = state.role !== "operator";
    $("#code-create").disabled = state.role !== "operator";
    $("#code-import-file").disabled = state.role !== "operator";
    $("#code-compare").disabled =
      !state.config.sandbox.available || state.role !== "operator";
    for (const name of ["baseline", "candidate"]) $("#benchmark-"+name+"-provider").innerHTML = state.config.providers.map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join("");
    $("#benchmark-candidate-provider").value = state.config.providers.find(p => p !== "mutation-search") || "mutation-search";
    await loadPolicies();
    await loadRepositories();
    await refresh();
  } catch (error) {
    fail(error);
  }
}
document.addEventListener("workspace-connected", initialize);
$("#code-repository").addEventListener("change", () => loadSource().catch(fail));
$("#code-load-sample").addEventListener("click", () => $("#code-github-import").click());
$("#code-github-import").addEventListener("click", () => operation(async () => {
  $("#code-github-import").disabled = true;
  try {
    const repository = await api("/code/repositories/github", {repository: $("#code-github-repo").value.trim(), ref: $("#code-github-ref").value.trim(), subdirectory: $("#code-github-dir").value.trim(), testClass: $("#code-github-test").value.trim()});
    await loadRepositories(repository.id);
    $("#code-instruction").value = "Fix the failing regression while preserving the existing tests and build configuration.";
    toast("Pinned revision imported and verified.");
  } finally { $("#code-github-import").disabled = state.role !== "operator"; }
}));
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
  if (button.dataset.sourceFile) { state.file = button.dataset.sourceFile; renderSource(); }
  if (button.dataset.run || button.dataset.benchmarkRun) void operation(async () => {
    state.run = await api(`/code/runs/${button.dataset.run || button.dataset.benchmarkRun}`);
    $("#code-repository").value = state.run.repositoryId;
    await loadSource(); await refresh();
    $("#code-result").scrollIntoView({behavior:"smooth",block:"start"});
  });
  if (button.dataset.benchmarkExport) download(pretty(state.benchmarks.find(b => b.id === button.dataset.benchmarkExport)),"paired-repair-benchmark.json");
  if (button.dataset.benchmarkCancel) void operation(async () => { await api(`/code/benchmarks/${button.dataset.benchmarkCancel}/cancel`,{}); await refresh(); });
  if (button.dataset.benchmarkPromote) void operation(async () => { await api(`/code/benchmarks/${button.dataset.benchmarkPromote}/promote`,{}); await loadPolicies(); await initialize(); toast("Evaluated agent policy promoted."); });
  if (button.id === "code-publish-preview") void operation(async () => {
    state.publicationPreview = await api(`/code/runs/${state.run.id}/publication-preview`);
    const p = state.publicationPreview;
    $("#code-publication-destination").textContent = `${p.destination} · ${p.headBranch} → ${p.baseBranch} · pinned ${p.pinnedBase.slice(0,12)}`;
    $("#code-publication-digest").textContent = p.approvalDigest;
    $("#code-publication-check").checked = false; $("#code-publication-confirm").disabled = true;
    $("#code-publication-dialog").showModal();
  });
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

$("#code-benchmark-start").addEventListener("click", () => operation(async () => {
  await api("/code/benchmarks",{baselineProvider:$("#benchmark-baseline-provider").value,candidateProvider:$("#benchmark-candidate-provider").value,baselinePolicy:$("#benchmark-baseline-policy").value,candidatePolicy:$("#benchmark-candidate-policy").value,repetitions:Number($("#benchmark-repetitions").value),maxAttempts:Number($("#benchmark-attempts").value),timeoutSeconds:Number($("#benchmark-timeout").value)}); await refresh();
}));
$("#code-policy-derive").addEventListener("click", () => operation(async () => { const p = await api("/code/agent-policies/candidate",{}); await loadPolicies(p.version); toast("Feedback candidate recorded. Paired evaluation is required before promotion."); }));
$("#code-policy-rollback-button").addEventListener("click", () => operation(async () => { await api("/code/agent-policies/rollback",{target:$("#code-policy-rollback").value,expectedActive:state.policies.active.version}); await loadPolicies(); await initialize(); toast("Previous agent policy restored."); }));
$("#code-publication-check").addEventListener("change", () => { $("#code-publication-confirm").disabled = !$("#code-publication-check").checked; });
$("#code-publication-confirm").addEventListener("click", () => operation(async () => { $("#code-publication-confirm").disabled = true; state.publication = await api(`/code/runs/${state.run.id}/publish`,{digest:state.publicationPreview.approvalDigest}); $("#code-publication-dialog").close(); await refresh(); toast("Reviewed draft pull request created."); }));

export { state as codeState };
