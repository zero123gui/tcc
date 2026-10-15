"use strict";

const KIND_LABEL = {
  ELECTION: "ELEIÇÃO", LEADER: "LÍDER", STEP_DOWN: "RENÚNCIA", PG_UNHEALTHY: "PG FALHOU",
  PROMOTING: "PROMOVENDO", PROMOTED: "PROMOVIDO", PROMOTE_FAILED: "PROMOÇÃO FALHOU",
  SPLIT_BRAIN: "SPLIT-BRAIN", HEALED: "SELF-HEALING", HEAL_FAILED: "HEALING FALHOU",
};
const MILESTONES = [
  ["pg_unhealthy", "PostgreSQL do alvo UNHEALTHY"],
  ["step_down", "Líder renunciou"],
  ["first_election", "Primeira eleição"],
  ["leader_elected", "Novo líder eleito"],
  ["promoting", "Promoção iniciada"],
  ["promoted", "Promovido (log)"],
  ["primary_visible", "Novo PRIMARY visível"],
  ["split_brain", "Split-brain detectado"],
  ["healed", "Self-Healing concluído"],
];
const SCENARIO_LABEL = {
  "raft-stop": "parar raft-ha-manager", "pg-stop": "parar PostgreSQL", "partition-on": "partição de rede",
};
const MAX_LINES = 600;

const state = { actions: [], highlight: new Set(), nodes: [], experiments: new Map(), cards: new Map(), logBoxes: new Map() };
const $ = (sel, root = document) => root.querySelector(sel);

// ------------------------------------------------------------------ WebSocket

function connect() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => setConn(true);
  ws.onclose = () => { setConn(false); setTimeout(connect, 2000); };
  ws.onmessage = (ev) => handle(JSON.parse(ev.data));
}

function setConn(on) {
  const el = $("#conn");
  el.dataset.state = on ? "on" : "off";
  el.textContent = on ? "conectado" : "desconectado";
}

function handle(msg) {
  switch (msg.type) {
    case "snapshot": return onSnapshot(msg);
    case "status": return renderStatus(msg);
    case "log": return appendLog(msg.line, true);
    case "experiment": state.experiments.set(msg.experiment.id, msg.experiment); return renderExperiments();
  }
}

function onSnapshot(msg) {
  state.actions = msg.actions;
  state.highlight = new Set(msg.highlight);
  $("#mode").hidden = msg.mode !== "demo";
  state.experiments = new Map(msg.experiments.map((e) => [e.id, e]));
  buildNodes(msg.nodes);
  buildLogs(msg.nodes, msg.logs);
  renderStatus(msg);
  renderExperiments();
}

// ------------------------------------------------------------------ nós

function buildNodes(nodes) {
  const root = $("#nodes");
  root.replaceChildren();
  state.cards.clear();
  for (const n of nodes) {
    const card = $("#node-tpl").content.firstElementChild.cloneNode(true);
    $(".node-id", card).textContent = n.id;
    if (n.arbiter) {
      card.dataset.arbiter = "";
      const tag = document.createElement("span");
      tag.className = "arbiter-tag";
      tag.textContent = "árbitro";
      $(".node-id", card).append(tag);
    }
    $(".node-host", card).textContent = n.host;
    buildActions(card, n);
    root.append(card);
    state.cards.set(n.id, card);
  }
}

function buildActions(card, node) {
  const groups = [["Raft", "raft"], ["PostgreSQL", "pg"], ["Rede", "partition"]];
  const box = $(".actions", card);
  for (const [label, prefix] of groups) {
    const acts = state.actions.filter((a) => a.key.startsWith(prefix + "-") && !(a.needs_pg && node.arbiter));
    if (!acts.length) continue;
    const g = document.createElement("div");
    g.className = "action-group";
    const span = document.createElement("span");
    span.textContent = label;
    g.append(span);
    for (const a of acts) {
      const b = document.createElement("button");
      b.type = "button";
      b.dataset.action = a.key;
      b.textContent = shortLabel(a.key);
      b.title = a.label;
      if (a.disruptive) b.classList.add("danger");
      b.addEventListener("click", () => runAction(node.id, a, b));
      g.append(b);
    }
    box.append(g);
  }
}

function shortLabel(key) {
  return { "raft-stop": "Parar", "raft-start": "Iniciar", "pg-stop": "Parar", "pg-start": "Iniciar",
           "partition-on": "Isolar", "partition-off": "Reconectar" }[key] || key;
}

// confirm()/alert() nativos são suprimidos em navegadores embutidos: usa <dialog> e avisos na página.
function ask(text) {
  const dlg = $("#confirm-dlg");
  if (typeof dlg.showModal !== "function") return Promise.resolve(window.confirm(text));
  $("#confirm-text").textContent = text;
  return new Promise((resolve) => {
    dlg.addEventListener("close", () => resolve(dlg.returnValue === "ok"), { once: true });
    dlg.returnValue = "cancel";
    dlg.showModal();
  });
}

function toast(text, level = "ok", ms = 6000) {
  const el = document.createElement("div");
  el.className = "toast";
  el.dataset.level = level;
  el.textContent = text;
  $("#toasts").append(el);
  setTimeout(() => el.remove(), ms);
}

async function runAction(nodeId, action, button) {
  if (action.disruptive && !(await ask(`${action.label} em ${nodeId}? Isso pode provocar um failover.`))) return;
  button.setAttribute("aria-busy", "true");
  button.disabled = true;
  try {
    const res = await fetch(`/api/nodes/${encodeURIComponent(nodeId)}/actions/${encodeURIComponent(action.key)}`, {
      method: "POST", headers: { "X-HA-Dashboard": "1" },
    });
    const body = await res.json();
    if (!res.ok) toast(body.detail || `Erro ${res.status}`, "error", 10000);
    else if (body.exit !== 0) toast(`${action.label} falhou em ${nodeId} (código ${body.exit}).\n${body.stderr || body.stdout}`, "error", 12000);
    else toast(`${action.label}: ${nodeId} ✔`, "ok", 4000);
  } catch (e) {
    toast(`Falha ao contatar o painel: ${e}`, "error", 10000);
  } finally {
    button.removeAttribute("aria-busy");
    button.disabled = false;
    renderStatus({ nodes: state.nodes });
  }
}

function renderStatus(msg) {
  state.nodes = msg.nodes;
  if (msg.cluster) renderCluster(msg.cluster);
  for (const n of msg.nodes) {
    const card = state.cards.get(n.id);
    if (card) renderNode(card, n);
  }
}

function renderCluster(c) {
  $("#sum-leader").textContent = c.leader || "—";
  $("#sum-term").textContent = c.term ?? "—";
  $("#sum-primary").textContent = c.primary || "—";
  $("#alerts").replaceChildren(...c.alerts.map((a) => {
    const li = document.createElement("li");
    li.dataset.level = a.level;
    li.textContent = a.text;
    return li;
  }));
}

function renderNode(card, n) {
  const role = n.grpc_ok ? (n.role || "?") : "OFFLINE";
  card.dataset.role = role;
  card.toggleAttribute("data-offline", !n.grpc_ok);
  const roleEl = $(".role", card);
  roleEl.dataset.v = role;
  roleEl.textContent = role;
  roleEl.title = n.grpc_ok ? "" : `gRPC: ${n.grpc_error || "sem resposta"}`;

  $(".f-term", card).textContent = n.term ?? "—";
  $(".f-leader", card).textContent = n.leader_id || "—";
  $(".f-commit", card).textContent = n.commit_index ?? "—";

  const pg = $(".pg-state", card);
  pg.dataset.v = n.pg_state;
  pg.textContent = { SEM_CREDENCIAL: "sem senha", SENHA_INVALIDA: "senha recusada" }[n.pg_state] || n.pg_state;
  $(".pg-detail", card).textContent = pgDetail(n);

  const services = $(".services", card);
  const items = Object.entries(n.services).map(([name, st]) => {
    const li = document.createElement("li");
    li.dataset.ok = st === "active" ? "1" : "0";
    li.textContent = `${name}: ${st}`;
    return li;
  });
  const net = document.createElement("li");
  if (n.partitioned === true) { net.dataset.ok = "0"; net.textContent = "rede: isolado"; }
  else if (n.partitioned === false) { net.dataset.ok = "1"; net.textContent = "rede: conectado"; }
  else { net.dataset.warn = ""; net.textContent = "rede: ?"; net.title = "ha-partition ausente ou sudo não configurado (vm/setup-vm-access.sh)"; }
  items.push(net);
  if (!n.ssh_ok) {
    const li = document.createElement("li");
    li.dataset.ok = "0";
    li.textContent = "SSH indisponível";
    li.title = n.ssh_error || "";
    items.unshift(li);
  }
  services.replaceChildren(...items);

  for (const b of card.querySelectorAll("button[data-action]")) {
    if (b.getAttribute("aria-busy")) continue;
    b.disabled = !n.ssh_ok || !actionApplies(b.dataset.action, n);
  }
}

function actionApplies(key, n) {
  const raft = n.services["raft-ha-manager"];
  const pg = n.services["postgresql"];
  switch (key) {
    case "raft-stop": return raft === "active";
    case "raft-start": return raft !== "active";
    case "pg-stop": return pg === "active";
    case "pg-start": return pg !== "active";
    case "partition-on": return n.partitioned !== true;
    case "partition-off": return n.partitioned !== false;
  }
  return true;
}

function pgDetail(n) {
  if (n.pg_error) return n.pg_error;
  if (n.pg_state === "PRIMARY") {
    if (!n.replicas.length) return "nenhum standby conectado";
    return "standbys: " + n.replicas.map((r) => `${hostToId(r.addr)} (${r.state})`).join(", ");
  }
  if (n.pg_state === "STANDBY") {
    return n.upstream_host ? `replica de ${hostToId(n.upstream_host)} (${n.upstream_status})` : "sem conexão com um primary";
  }
  return "";
}

function hostToId(host) {
  return state.nodes.find((n) => n.host === host)?.id || host;
}

// ------------------------------------------------------------------ logs

function buildLogs(nodes, logs) {
  const root = $("#logs");
  root.replaceChildren();
  state.logBoxes.clear();
  $("#timeline").replaceChildren();
  for (const n of nodes) {
    const col = document.createElement("div");
    col.className = "log-col";
    const h = document.createElement("h3");
    h.textContent = `${n.id} · journalctl -u raft-ha-manager`;
    const box = document.createElement("div");
    box.className = "log-box";
    col.append(h, box);
    root.append(col);
    state.logBoxes.set(n.id, box);
    for (const line of logs[n.id] || []) appendLog(line, false);
    box.scrollTop = box.scrollHeight;
  }
}

function appendLog(line, live) {
  const box = state.logBoxes.get(line.node);
  if (!box) return;
  const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const div = document.createElement("div");
  div.className = "log-line" + (line.history ? " history" : "");
  if (line.level) div.dataset.level = line.level;
  if (line.kind) div.dataset.kind = line.kind;
  if (state.highlight.has(line.kind)) {
    div.dataset.hl = "";
    const k = document.createElement("span");
    k.className = "kind";
    k.textContent = KIND_LABEL[line.kind] || line.kind;
    div.append(k);
    if (!line.history) addTimeline(line);
  }
  div.append(document.createTextNode(line.text));
  box.append(div);
  while (box.childElementCount > MAX_LINES) box.firstElementChild.remove();
  if (stick || !live) box.scrollTop = box.scrollHeight;
}

function addTimeline(line) {
  const li = document.createElement("li");
  const t = document.createElement("time");
  t.textContent = clock(line.host_ts);
  const node = document.createElement("span");
  node.className = "tl-node";
  node.textContent = line.node;
  const what = document.createElement("span");
  what.textContent = KIND_LABEL[line.kind] || line.kind;
  li.append(t, node, what);
  li.title = line.text;
  const tl = $("#timeline");
  tl.prepend(li);
  while (tl.childElementCount > 100) tl.lastElementChild.remove();
}

function clock(ts) {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString("pt-BR", { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

$("#only-events").addEventListener("change", (e) => $("#logs").classList.toggle("only-events", e.target.checked));

// ------------------------------------------------------------------ experimentos

function renderExperiments() {
  const list = [...state.experiments.values()].sort((a, b) => b.id - a.id);
  const active = list.find((e) => e.active);
  renderActive(active);

  const done = list.filter((e) => !e.active);
  const tbody = $("#exp-history");
  if (!done.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">Nenhum experimento concluído ainda.</td></tr>';
    return;
  }
  tbody.replaceChildren(...done.map((e) => {
    const tr = document.createElement("tr");
    const cells = [
      e.id, SCENARIO_LABEL[e.scenario] || e.scenario, e.target, e.new_leader || "—",
      fmt(e.marks.first_election), fmt(e.marks.leader_elected), fmt(e.marks.promoted),
      fmt(e.marks.primary_visible), e.outcome,
    ];
    cells.forEach((v, i) => {
      const td = document.createElement("td");
      td.textContent = v;
      if (i >= 4 && i <= 7) td.className = "num";
      tr.append(td);
    });
    if (e.notes.length) tr.title = e.notes.join("\n");
    return tr;
  }));
}

let clockTimer = null;

function renderActive(e) {
  const box = $("#exp-active");
  clearInterval(clockTimer);
  if (!e) { box.replaceChildren(); return; }
  box.innerHTML = `
    <div class="exp-active">
      <div class="exp-active-head">
        <span class="exp-clock">0.0 s</span>
        <strong></strong>
        <button type="button" class="finish">Encerrar medição</button>
      </div>
      <ul class="milestones"></ul>
    </div>`;
  $("strong", box).textContent =
    `#${e.id} · ${SCENARIO_LABEL[e.scenario] || e.scenario} em ${e.target} (líder anterior: ${e.old_leader || "?"}, primary anterior: ${e.old_primary || "?"})`;
  $(".milestones", box).replaceChildren(...MILESTONES.map(([key, label]) => {
    const li = document.createElement("li");
    const v = e.marks[key];
    if (v != null) li.dataset.done = "";
    const a = document.createElement("span");
    a.textContent = label;
    const b = document.createElement("span");
    b.className = "ms-t";
    b.textContent = v != null ? `${v.toFixed(3)} s` : "—";
    li.append(a, b);
    return li;
  }));
  $(".finish", box).addEventListener("click", async () => {
    await fetch(`/api/experiments/${e.id}/finish`, { method: "POST", headers: { "X-HA-Dashboard": "1" } });
  });
  const clockEl = $(".exp-clock", box);
  const tick = () => { clockEl.textContent = `${(Date.now() / 1000 - e.started_at).toFixed(1)} s`; };
  tick();
  clockTimer = setInterval(tick, 100);
}

function fmt(v) { return v == null ? "—" : v.toFixed(1); }

connect();
