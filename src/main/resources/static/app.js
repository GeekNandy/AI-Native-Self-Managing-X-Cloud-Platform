const $ = (selector) => document.querySelector(selector);
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        character
      ],
  );
const pretty = (value) => JSON.stringify(value, null, 2);
const labels = {
  workbench: "Agent workbench",
  environments: "Environments",
  evaluations: "Evaluations",
  activity: "Activity log",
};
const state = {
  config: null,
  examples: [],
  run: null,
  plan: null,
  tab: "intent",
  artifact: 0,
  environments: [],
  audit: [],
  reports: [],
  feedback: [],
  report: null,
  busy: {},
  applied: new Set(),
  invalidated: new Set(),
  keys: new Map(),
};
let toastTimeout;

async function api(path, body) {
  let response;
  try {
    response = await fetch(
      `/api${path}`,
      body === undefined
        ? {}
        : {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          },
    );
  } catch {
    throw new Error(
      "The workbench is unreachable. Check the server, then retry. Apply retries reuse the same operation key.",
    );
  }
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(
      result.message || result.detail || `Request failed (${response.status}).`,
    );
    error.code = result.code;
    error.details = result.details || [];
    throw error;
  }
  return result;
}

function toast(message, error = false) {
  clearTimeout(toastTimeout);
  const element = $("#toast");
  element.textContent = message;
  element.classList.toggle("error", error);
  element.hidden = false;
  toastTimeout = setTimeout(
    () => {
      element.hidden = true;
    },
    error ? 10000 : 5000,
  );
}

function fail(error) {
  toast([error.message, ...(error.details || [])].join(" "), true);
}
function badge(value, tone = "neutral") {
  return `<span class="badge ${tone}">${esc(value)}</span>`;
}
function timestamp(value) {
  return new Date(value).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}
function format(value) {
  return typeof value === "boolean"
    ? value
      ? "Enabled"
      : "Disabled"
    : (value ?? "Unknown");
}

function viewFromHash() {
  const value = location.hash.slice(1);
  return labels[value] ? value : "workbench";
}
function showView() {
  const current = viewFromHash();
  for (const name of Object.keys(labels))
    $(`#view-${name}`).hidden = current !== name;
  for (const link of document.querySelectorAll("[data-view]"))
    link.classList.toggle("active", link.dataset.view === current);
  $("#breadcrumb-current").textContent = labels[current];
}

function renderStats() {
  const drifted = state.environments.filter(
    (environment) => environment.convergence === "DRIFT_DETECTED",
  ).length;
  const metrics = [
    ["ENVIRONMENTS", state.environments.length, "in the sandbox", "▦"],
    ["POLICY CHECKS", "8", "per proposal", "◈"],
    [
      "DRIFT DETECTED",
      drifted,
      drifted === 1 ? "environment" : "environments",
      "⌁",
    ],
    [
      "LATEST EVALUATION",
      state.report ? `${state.report.passed}/${state.report.total}` : "—",
      state.report ? "checks passed" : "ready to run",
      "◎",
    ],
  ];
  $("#stats").innerHTML = metrics
    .map(
      ([title, value, note, icon]) =>
        `<div class="stat"><div class="stat-label">${title}</div><div class="stat-row"><span class="stat-value">${value}</span><span class="stat-unit">${note}</span></div><span class="stat-decoration">${icon}</span></div>`,
    )
    .join("");
  $("#nav-environments").textContent = state.environments.length;
}

function renderConfig() {
  const live = state.config.provider === "gemini";
  $("#provider-badge").className = `badge ${live ? "success" : "purple"}`;
  $("#provider-badge").textContent = live
    ? `Gemini · ${state.config.model}`
    : "Offline example mode";
  $("#mode-notice").innerHTML =
    `<span class="notice-symbol">i</span><span>${live ? "<strong>Live model extraction</strong> · Proposals still pass independent validation and policy checks." : "<strong>Offline examples</strong> · Choose a curated request below. Configure Gemini to interpret new requests."} Every execution uses the local sandbox.</span>`;
  $("#connection-status").textContent = "Control plane connected";
  $("#evaluation-mode-label").textContent = live
    ? "Control-plane + live interpreter evaluation"
    : "Control-plane + fixture replay";
  $("#evaluate-interpreter").textContent = live
    ? "Evaluate Gemini interpreter ↗"
    : "Check example replay ↗";
  $("#workbench-boundary").textContent =
    "In-memory sandbox · No cloud resources created";
}

function renderExamples() {
  const prompt = $("#prompt").value;
  $("#examples").innerHTML = state.examples
    .map(
      (example, index) =>
        `<button class="example-button ${example.prompt === prompt ? "selected" : ""}" data-example="${index}" ${state.busy.compile ? "disabled" : ""}><i aria-hidden="true">${example.expected === "ALLOW" ? "◇" : example.expected === "DENY" ? "△" : "·"}</i>${esc(example.name)}</button>`,
    )
    .join("");
  $("#prompt-length").textContent = `${prompt.length.toLocaleString()} / 4,000`;
}

function renderPipeline() {
  const repair = state.plan?.kind === "RECONCILE";
  const names = repair
    ? ["Observe", "Detect", "Govern", "Review"]
    : ["Interpret", "Govern", "Compile", "Review"];
  let completed = state.plan ? (state.plan.status === "BLOCKED" ? 1 : 3) : 0;
  if (state.plan && state.applied.has(state.plan.id)) completed = 4;
  $("#pipeline").innerHTML = names
    .map(
      (name, index) =>
        `${index ? '<span class="pipeline-line"></span>' : ""}<span class="pipeline-step ${index < completed ? "done" : index === completed && (state.plan || state.run || state.busy.compile) ? "current" : ""}"><i>${index < completed ? "✓" : index + 1}</i>${name}</span>`,
    )
    .join("");
}

function renderIntent(spec) {
  const fields = [
    ["Runtime", "Java 21"],
    ["Environment", spec.tier],
    ["Region", spec.region],
    ["Classification", spec.dataClassification.toUpperCase()],
    ["Private network", spec.privateNetwork],
    ["Restricted egress", spec.restrictedEgress],
    ["Encryption required", spec.encryptedStorage],
    ["Zones", spec.zones],
    ["Replicas", spec.replicas],
    ["Monitoring", spec.monitoring],
    ["Rollback", spec.rollback],
    ["Execution", "Local sandbox"],
  ];
  return `<div class="intent-table">${fields.map(([key, value]) => `<div class="intent-field"><span>${esc(key)}</span><strong class="${value === true ? "enabled" : ""}">${esc(format(value))}</strong></div>`).join("")}</div>`;
}

function proposalBody(plan) {
  if (state.tab === "policy")
    return `<ul class="policy-list">${plan.policy.checks.map((check) => `<li class="policy-item"><span class="check-icon ${check.passed ? "" : "failed"}">${check.passed ? "✓" : "×"}</span><div><strong>${esc(check.id)}</strong><p>${esc(check.requirement)}</p></div></li>`).join("")}</ul>`;
  if (state.tab === "plan")
    return `<ol class="action-list">${plan.actions.map((action) => `<li class="action-item"><span class="action-index">${action.order}</span><div><strong>${esc(action.operation)} / ${esc(action.resource)}</strong><p>${esc(action.description)}</p>${action.dependsOn.length ? `<span class="action-dependency">Depends on ${esc(action.dependsOn.join(", "))}</span>` : ""}</div></li>`).join("")}</ol><p class="proposal-note">Simulator operations are atomic in memory. Generated files are review artifacts and are not applied to a cluster.</p>`;
  if (state.tab === "artifacts")
    return `<div class="artifact-bar"><label class="sr-only" for="artifact-select">Review artifact</label><select id="artifact-select">${plan.artifacts.map((artifact, index) => `<option value="${index}" ${index === state.artifact ? "selected" : ""}>${esc(artifact.path)}</option>`).join("")}</select><button class="button secondary small" data-download-artifact>Download ↓</button></div><pre id="artifact-code" class="code-preview"></pre>`;
  return renderIntent(plan.intent);
}

function renderProposal() {
  renderPipeline();
  const plan = state.plan;
  const applied = plan && state.applied.has(plan.id);
  const invalidated = plan && state.invalidated.has(plan.id);
  $("#plan-status").className =
    `badge ${applied ? "success" : invalidated ? "warning" : plan ? (plan.status === "BLOCKED" ? "danger" : "success") : "neutral"}`;
  $("#plan-status").textContent = applied
    ? "Applied to sandbox"
    : invalidated
      ? "Fresh plan required"
      : plan
        ? plan.status === "BLOCKED"
          ? "Policy denied"
          : "Policy checks passed"
        : state.busy.compile
          ? "Compiling"
          : state.run
            ? "More input required"
            : "Awaiting intent";
  if (state.busy.compile) {
    $("#proposal-content").innerHTML =
      '<div class="empty-proposal"><div class="empty-icon loading-pulse">◈</div><h3>Interpreting your request</h3><p>Constructing a candidate intent for deterministic validation and policy review.</p></div>';
    return;
  }
  if (!plan) {
    $("#proposal-content").innerHTML = state.run
      ? `<div class="alert"><h3>${state.run.status === "INVALID_INTENT" ? "Intent could not be validated" : "Clarify the requirements"}</h3>${state.run.messages.map((message) => `<p>${esc(message)}</p>`).join("")}</div><div class="empty-proposal"><div class="empty-icon">?</div><h3>No executable proposal</h3><p>Complete the requirements before creating a change plan.</p></div>`
      : '<div class="empty-proposal"><div class="empty-icon">◈</div><h3>A clear view of the proposed change.</h3><p>Your structured intent, policy decisions and generated artifacts will appear here.</p><div class="empty-contract"><span>VERSIONED INTENT</span><span>POLICY CHECKED</span><span>REVIEW FIRST</span></div></div>';
    $("#feedback-section").hidden = true;
    return;
  }
  const denied = plan.policy.checks.filter((check) => !check.passed);
  const tabs = [
    ["intent", "Intent"],
    ["policy", `Policy · ${plan.policy.checks.length}`],
    ["plan", `Plan · ${plan.actions.length}`],
    ["artifacts", `Artifacts · ${plan.artifacts.length}`],
  ];
  $("#proposal-content").innerHTML =
    `<div class="proposal-title"><strong>${esc(plan.intent.serviceName)}</strong>${badge(plan.kind.toLowerCase(), "purple")}</div><div class="proposal-meta"><span>Revision ${plan.baseRevision}</span><span>Expires ${esc(timestamp(plan.expiresAt))}</span><span>${state.run && plan.id === state.run.plan?.id ? state.run.interpretationMs.toFixed(1) + " ms interpretation" : "Approved desired state"}</span></div>${denied.length ? `<div class="alert error"><h3>${denied.length} requirement${denied.length === 1 ? "" : "s"} blocked this proposal</h3>${denied.map((check) => `<p>${esc(check.requirement)}</p>`).join("")}</div>` : ""}<div class="tabs" role="tablist" aria-label="Proposal details">${tabs.map(([name, label]) => `<button class="tab ${state.tab === name ? "active" : ""}" role="tab" aria-selected="${state.tab === name}" data-tab="${name}">${label}</button>`).join("")}</div><div class="proposal-tab">${proposalBody(plan)}</div><div class="plan-footer"><div><span class="digest-label">PLAN DIGEST · SHA-256</span><code class="digest-value" title="${plan.digest}">${plan.digest.slice(0, 12)}…${plan.digest.slice(-8)}</code></div><button class="button ${denied.length ? "secondary" : "primary"}" id="review-button" ${denied.length || applied || invalidated || state.busy.apply ? "disabled" : ""}>${applied ? "Applied ✓" : denied.length ? "Execution blocked" : invalidated ? "Replan required" : "Review & approve"} <span>↗</span></button></div><p class="proposal-note">${invalidated ? "The reviewed preconditions are no longer valid. Generate a fresh proposal." : plan.kind === "RECONCILE" ? "Remediation uses the same policy, approval and execution path as provisioning." : "Approval authorizes this exact proposal in the local sandbox."}</p>`;
  if (state.tab === "artifacts")
    $("#artifact-code").textContent = plan.artifacts[state.artifact].content;
  $("#feedback-section").hidden = !state.run;
}

async function refresh() {
  [state.environments, state.audit, state.reports, state.feedback] =
    await Promise.all([
      api("/environments"),
      api("/audit"),
      api("/evaluations"),
      api("/feedback"),
    ]);
  state.report = state.reports[0] || null;
  renderStats();
  renderEnvironments();
  renderActivity();
  renderEvaluations();
}

async function compile() {
  if (state.busy.compile || !$("#prompt").value.trim()) return;
  state.busy.compile = true;
  state.plan = null;
  state.run = null;
  $("#compile-button").disabled = true;
  $("#compile-button").textContent = "Compiling…";
  $("#prompt").disabled = true;
  $("#feedback-section").hidden = true;
  renderExamples();
  renderProposal();
  try {
    state.run = await api("/runs", { prompt: $("#prompt").value });
    state.plan = state.run.plan;
    state.tab = state.plan?.status === "BLOCKED" ? "policy" : "intent";
    state.artifact = 0;
    if (state.plan) {
      $("#feedback-spec").value = pretty(state.plan.intent);
      $("#feedback-outcome").value = state.plan.policy.decision;
      $("#feedback-note").value = "";
    }
    await refresh();
  } catch (error) {
    fail(error);
  } finally {
    state.busy.compile = false;
    $("#compile-button").disabled = false;
    $("#compile-button").innerHTML = "Compile intent <span>↗</span>";
    $("#prompt").disabled = false;
    renderExamples();
    renderProposal();
  }
}

function openApproval() {
  const plan = state.plan;
  if (
    !plan ||
    plan.status === "BLOCKED" ||
    state.applied.has(plan.id) ||
    state.invalidated.has(plan.id)
  )
    return;
  $("#approval-summary").innerHTML =
    `<div class="approval-summary"><div><span>Environment</span><strong>${esc(plan.environmentId)}</strong></div><div><span>Change</span><strong>${esc(plan.kind)} · ${plan.actions.length} operations</strong></div><div><span>Precondition</span><strong>Revision ${plan.baseRevision}</strong></div><div><span>Execution target</span><strong>In-memory sandbox</strong></div><code>${plan.digest}</code></div>`;
  $("#approval-checkbox").checked = false;
  $("#approval-confirm").disabled = true;
  $("#approval-dialog").showModal();
}

async function apply() {
  const plan = state.plan;
  if (state.busy.apply || !$("#approval-checkbox").checked) return;
  state.busy.apply = true;
  $("#approval-confirm").disabled = true;
  $("#approval-confirm").textContent = "Executing…";
  if (!state.keys.has(plan.id)) state.keys.set(plan.id, crypto.randomUUID());
  try {
    const result = await api(`/plans/${plan.id}/apply`, {
      digest: plan.digest,
      idempotencyKey: state.keys.get(plan.id),
    });
    state.applied.add(plan.id);
    $("#approval-dialog").close();
    toast(
      result.replayed
        ? "Retry deduplicated. The original operation receipt was returned."
        : `${plan.kind === "RECONCILE" ? "Recovery complete" : "Change applied"}. Sandbox state is ${result.receipt.environment.convergence.toLowerCase()}.`,
    );
    await refresh();
    location.hash = "environments";
  } catch (error) {
    if (
      [
        "STALE_PLAN",
        "PLAN_EXPIRED",
        "POLICY_CHANGED",
        "POLICY_DENIED",
        "ADAPTER_FAILED",
      ].includes(error.code)
    ) {
      state.invalidated.add(plan.id);
      $("#approval-dialog").close();
    }
    fail(error);
  } finally {
    state.busy.apply = false;
    $("#approval-confirm").textContent = "Approve & execute";
    $("#approval-confirm").disabled = !$("#approval-checkbox").checked;
    renderProposal();
  }
}

function renderEnvironments() {
  if (!state.environments.length) {
    $("#environments-content").innerHTML =
      '<div class="card environment-empty"><div class="empty-icon">▦</div><h2>Your sandbox is ready.</h2><p>Compile an intent and approve its plan to create the first environment.</p><a href="#workbench" class="button primary">Open agent workbench ↗</a></div>';
    return;
  }
  $("#environments-content").innerHTML =
    `<div class="environment-grid">${state.environments
      .map((environment) => {
        const unknown = environment.convergence === "UNKNOWN";
        const rows = [
          ["Runtime", "runtime"],
          ["Region", "region"],
          ["Replicas", "replicas"],
          ["Zones", "zones"],
          ["Private network", "privateNetwork"],
          ["Restricted egress", "restrictedEgress"],
          ["Encrypted storage required", "encryptedStorage"],
          ["Monitoring", "monitoring"],
        ];
        return `<section class="card environment-card"><header><h2>${esc(environment.id)}</h2>${badge(environment.convergence === "CONVERGED" ? "Converged" : unknown ? "Observation unknown" : `${environment.drift.length} drifted field${environment.drift.length === 1 ? "" : "s"}`, environment.convergence === "CONVERGED" ? "success" : unknown ? "neutral" : "warning")}</header><div class="environment-meta">Desired version ${environment.desiredVersion} · Revision ${environment.revision} · In-memory sandbox</div><table class="state-comparison"><thead><tr><th>FIELD</th><th>DESIRED</th><th>OBSERVED</th></tr></thead><tbody>${rows.map(([label, key]) => `<tr><td>${label}</td><td>${esc(format(environment.desired[key]))}</td><td class="${environment.observed && environment.desired[key] !== environment.observed[key] ? "different" : ""}">${esc(environment.observed ? format(environment.observed[key]) : "Unknown")}</td></tr>`).join("")}</tbody></table><div class="fault-actions"><button class="button secondary small" data-fault="replica-loss" data-env="${esc(environment.id)}" ${unknown || state.busy.environment ? "disabled" : ""}>Lose replicas</button><button class="button secondary small" data-fault="egress-open" data-env="${esc(environment.id)}" ${unknown || state.busy.environment ? "disabled" : ""}>Open egress</button><button class="button secondary small" data-fault="${unknown ? "observation-restored" : "observation-lost"}" data-env="${esc(environment.id)}" ${state.busy.environment ? "disabled" : ""}>${unknown ? "Restore observation" : "Lose observation"}</button></div><div class="environment-action"><span>${unknown ? "Recovery waits for reliable observation." : environment.drift.length ? "Restore approved desired state." : "Inject a fault to exercise the control loop."}</span><button class="button ${environment.drift.length ? "primary" : "secondary"} small" data-reconcile="${esc(environment.id)}" ${unknown || !environment.drift.length || state.busy.environment ? "disabled" : ""}>Plan recovery ↗</button></div></section>`;
      })
      .join("")}</div>`;
}

async function environmentAction(button) {
  if (state.busy.environment) return;
  state.busy.environment = true;
  renderEnvironments();
  try {
    if (button.dataset.fault) {
      await api(
        `/environments/${encodeURIComponent(button.dataset.env)}/faults`,
        { kind: button.dataset.fault },
      );
      toast(
        button.dataset.fault === "observation-restored"
          ? "Observation restored. Desired state is unchanged."
          : "Fault injected. Desired state is unchanged.",
      );
    } else {
      state.plan = await api(
        `/environments/${encodeURIComponent(button.dataset.reconcile)}/reconcile`,
        {},
      );
      state.tab = "plan";
      state.artifact = 0;
      renderProposal();
      location.hash = "workbench";
      toast(
        "Recovery proposed. Review and approve it through the same control boundary.",
      );
    }
    await refresh();
  } catch (error) {
    fail(error);
  } finally {
    state.busy.environment = false;
    renderEnvironments();
  }
}

function renderEvaluations() {
  const report = state.report;
  $("#evaluate-controls").disabled = !!state.busy.evaluation;
  $("#evaluate-interpreter").disabled = !!state.busy.evaluation;
  $("#evaluate-controls").innerHTML = state.busy.evaluation
    ? "Evaluating…"
    : "Run control-plane suite <span>↗</span>";
  if (!report) {
    $("#evaluation-content").innerHTML =
      '<div class="card evaluation-empty"><div class="empty-icon">◎</div><h2>Run the evidence.</h2><p>Check the compiler, policy outcomes and execution safety invariants in isolated sandboxes.</p><p>Feedback cases join the suite on the next run.</p></div>';
    return;
  }
  const stats = [
    [
      "CHECKS PASSED",
      `${report.passed} / ${report.total}`,
      "versioned scenarios + regressions",
    ],
    ["PASS RATE", `${report.passRate}%`, "of evaluated checks"],
    [
      "P50 CASE LATENCY",
      `${report.p50Ms.toFixed(2)} ms`,
      "local suite measurement",
    ],
    ["FEEDBACK CASES", report.feedbackCases, "reviewed regression cases"],
  ];
  $("#evaluation-content").innerHTML =
    `<div class="evaluation-score-grid">${stats.map(([title, value, note]) => `<div class="card evaluation-stat"><div class="stat-label">${title}</div><strong>${esc(value)}</strong><span>${note}</span></div>`).join("")}</div><div class="alert"><strong>${esc(report.mode)}</strong><br>${esc(report.limitation)}</div><section class="card"><div class="report-heading"><div><h2>Evaluation results</h2><small>${esc(timestamp(report.createdAt))} · Dataset ${report.datasetDigest.slice(0, 12)} · P95 ${report.p95Ms.toFixed(2)} ms</small></div><button class="button secondary small" id="download-report">Export evidence ↓</button></div><div class="table-wrap"><table class="report-table"><thead><tr><th>SCENARIO</th><th>CATEGORY</th><th>EXPECTED</th><th>ACTUAL</th><th>RESULT</th></tr></thead><tbody>${report.cases.map((item) => `<tr><td>${esc(item.name)}${!item.passed ? `<span class="report-detail">${esc(item.detail)}</span>` : ""}</td><td>${esc(item.category)}</td><td>${esc(item.expected)}</td><td>${esc(item.actual)}</td><td>${badge(item.passed ? "PASS" : "FAIL", item.passed ? "success" : "danger")}</td></tr>`).join("")}</tbody></table></div><div class="report-footnote">The suite checks declared behavior. It does not establish cloud reliability, production throughput or autonomous learning. Exported evidence includes the dataset digest, per-case timing and outcomes.</div></section>`;
}

async function evaluate(mode) {
  if (state.busy.evaluation) return;
  state.busy.evaluation = true;
  renderEvaluations();
  try {
    const report = await api("/evaluations", { mode });
    await refresh();
    state.report = report;
    renderStats();
    renderEvaluations();
    toast(
      `${report.passed}/${report.total} checks passed. ${report.feedbackCases} feedback regression cases included.`,
    );
  } catch (error) {
    fail(error);
  } finally {
    state.busy.evaluation = false;
    renderEvaluations();
  }
}

async function saveFeedback() {
  if (!state.run || state.busy.feedback || state.busy.evaluation) return;
  const note = $("#feedback-note").value.trim();
  if (!note) {
    $("#feedback-note").focus();
    toast("Add a note describing what this regression should protect.", true);
    return;
  }
  let spec;
  try {
    spec = JSON.parse($("#feedback-spec").value);
  } catch {
    toast("The expected intent must be valid JSON.", true);
    return;
  }
  state.busy.feedback = true;
  $("#feedback-save").disabled = true;
  try {
    await api("/feedback", {
      runId: state.run.id,
      note,
      expectedSpec: spec,
      expectedDecision: $("#feedback-outcome").value,
    });
    location.hash = "evaluations";
    await evaluate("control-plane");
  } catch (error) {
    fail(error);
  } finally {
    state.busy.feedback = false;
    $("#feedback-save").disabled = false;
  }
}

function renderActivity() {
  if (!state.audit.length) {
    $("#activity-content").innerHTML =
      '<div class="card environment-empty"><div class="empty-icon">≋</div><h2>The next change starts the story.</h2><p>Compile an intent to see correlated events here.</p></div>';
    return;
  }
  $("#activity-content").innerHTML =
    `<section class="card"><div class="report-heading"><h2>Control-plane events</h2><small>${state.audit.length} retained events</small></div><div class="table-wrap"><table class="report-table"><thead><tr><th>TIME / SEQ.</th><th>EVENT</th><th>ACTOR / ENVIRONMENT</th><th>DETAIL</th></tr></thead><tbody>${[
      ...state.audit,
    ]
      .reverse()
      .map(
        (event) =>
          `<tr><td>${esc(timestamp(event.timestamp))}<br><span class="footnote">#${event.sequence}</span></td><td><span class="event-type">${esc(event.type)}</span></td><td>${esc(event.actor)}<br><span class="footnote">${esc(event.environmentId || "workspace")}</span></td><td class="activity-detail">${esc(event.detail)}<br><span class="footnote">${esc(event.correlationId)}</span></td></tr>`,
      )
      .join("")}</tbody></table></div></section>`;
}

function download(content, filename, type = "text/plain") {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button || button.disabled) return;
  if (button.dataset.example !== undefined) {
    $("#prompt").value = state.examples[Number(button.dataset.example)].prompt;
    renderExamples();
  }
  if (button.dataset.tab) {
    state.tab = button.dataset.tab;
    renderProposal();
  }
  if (button.id === "review-button") openApproval();
  if (button.dataset.fault || button.dataset.reconcile)
    void environmentAction(button);
  if (button.hasAttribute("data-download-artifact")) {
    const artifact = state.plan.artifacts[state.artifact];
    download(artifact.content, artifact.path);
  }
  if (button.id === "download-report")
    download(
      pretty(state.report),
      `evaluation-${state.report.id}.json`,
      "application/json",
    );
});
$("#prompt").addEventListener("input", renderExamples);
$("#compile-button").addEventListener("click", compile);
$("#approval-checkbox").addEventListener("change", () => {
  $("#approval-confirm").disabled =
    !$("#approval-checkbox").checked || !!state.busy.apply;
});
$("#approval-confirm").addEventListener("click", apply);
$("#about-button").addEventListener("click", () =>
  $("#about-dialog").showModal(),
);
$("#refresh-environments").addEventListener("click", () =>
  refresh().catch(fail),
);
$("#refresh-activity").addEventListener("click", () => refresh().catch(fail));
$("#evaluate-controls").addEventListener("click", () =>
  evaluate("control-plane"),
);
$("#evaluate-interpreter").addEventListener("click", () =>
  evaluate("interpreter"),
);
$("#feedback-save").addEventListener("click", saveFeedback);
document.addEventListener("change", (event) => {
  if (event.target.id === "artifact-select") {
    state.artifact = Number(event.target.value);
    renderProposal();
  }
});
window.addEventListener("hashchange", showView);

async function initialize() {
  showView();
  renderStats();
  renderProposal();
  renderEnvironments();
  renderEvaluations();
  renderActivity();
  try {
    [state.config, state.examples] = await Promise.all([
      api("/config"),
      api("/examples"),
    ]);
    renderConfig();
    if (state.examples.length) $("#prompt").value = state.examples[0].prompt;
    renderExamples();
    await refresh();
  } catch (error) {
    $("#connection-status").textContent = "Connection unavailable";
    $("#connection-error").hidden = false;
    $("#connection-error").textContent = error.message;
    $("#compile-button").disabled = true;
  }
}
void initialize();
