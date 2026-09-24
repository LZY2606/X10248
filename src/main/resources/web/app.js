"use strict";
const api = (p, opt) => fetch(p, opt).then(async r => {
  const t = await r.text();
  const j = t ? JSON.parse(t) : {};
  if (!r.ok) throw Object.assign(new Error(j.error || r.status), { status: r.status, body: j });
  return j;
});

let state = null;
let branch = "br-main";
let baseRevision = 0;       // what this window last saw (optimistic concurrency)
let selectedEvent = null;
let selectedCandidate = 0;
let candidateData = null;

const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const fmt = (v, d=4) => v == null ? "—" : (typeof v === "number" ? (Math.abs(v) >= 1e5 || (Math.abs(v) < 1e-4 && v !== 0) ? v.toExponential(2) : v.toFixed(d)) : v);

async function load() {
  state = await api(`/api/state?branch=${encodeURIComponent(branch)}`);
  baseRevision = state.branch.revision;
  render();
}

function render() {
  $("revBadge").textContent = `修订号 ${state.branch.revision}`;
  $("ruleBadge").textContent = state.ruleVersion;
  const branches = [...state.branches].sort((a,b)=>a.branchId.localeCompare(b.branchId));
  $("branchSelect").innerHTML = branches.map(b =>
    `<option value="${b.branchId}" ${b.branchId===branch?"selected":""}>${esc(b.name)} @${b.revision}</option>`).join("");
  renderEvents();
  renderSources();
  renderDecisions();
  renderTabs();
}

function renderEvents() {
  const evs = state.events;
  if (!selectedEvent && evs.length) selectedEvent = evs[evs.length - 1].eventId;
  $("events").innerHTML = evs.map(e => {
    const st = e.payload.status || (e.eventType === "DECISION" ? e.payload.verdict : "");
    return `<div class="card ${e.eventId===selectedEvent?"selected":""}" data-ev="${e.eventId}">
      <div class="summary"><span class="tag ${st}">${esc(e.eventType)}${st?` · ${esc(st)}`:""}</span>${esc(e.payload.summary||"")}</div>
      <div class="id">${esc(e.eventId)}</div>
      <div class="id">fp ${esc(e.fingerprint)}</div>
      <div class="refchain">${(e.sourceRefs||[]).slice(0,4).map(esc).join("<br>")}${(e.sourceRefs||[]).length>4?" …":""}</div>
      ${e.invalidates && e.invalidates.length ? `<div class="id">失效范围: ${e.invalidates.map(esc).join(", ")}</div>`:""}
    </div>`;
  }).join("");
  document.querySelectorAll("#events .card").forEach(c => c.onclick = () => {
    selectedEvent = c.dataset.ev; selectedCandidate = 0; render();
  });
}

function renderSources() {
  const kinds = {};
  for (const s of state.sources) (kinds[s.kind] ||= []).push(s);
  $("sources").innerHTML = Object.entries(kinds).map(([k, arr]) => {
    const a = arr[arr.length - 1];
    return `<div class="card"><div class="summary">${esc(k)} <span class="tag">v${a.version}</span></div>
      <div class="id">${esc(a.sourceId)}</div>
      <div class="id">sha256 ${esc(a.sha256)}</div>
      ${arr.length>1?`<div class="id small">历史版本: ${arr.map(x=>`v${x.version}`).join(", ")}</div>`:""}
    </div>`;
  }).join("");
}

function renderDecisions() {
  $("decisions").innerHTML = (state.decisions||[]).map(d =>
    `<div class="card"><div class="summary"><span class="tag ${d.verdict}">${d.verdict}</span>${esc(d.targetKind)} ${esc(d.targetId)}</div>
      <div class="id">${esc(d.decisionId)} @ ${esc(d.eventId)}</div>
      <div class="small">${esc(d.rationale)}</div></div>`).join("");
}

function currentDerived() {
  return (state.derived || []).find(d => d.eventId === selectedEvent);
}

// ---------- SVG helpers ----------
function svg(tag, attrs, text) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k,v] of Object.entries(attrs||{})) el.setAttribute(k, v);
  if (text != null) el.textContent = text;
  return el;
}
function hbar(value, max, color, label) {
  const w = 360;
  const frac = Math.max(0, Math.min(1, value / max));
  const g = svg("svg", { width: w + 120, height: 22 });
  g.appendChild(svg("rect", { x: 110, y: 4, width: w, height: 14, fill: "#0f1420", stroke: "#33415f" }));
  g.appendChild(svg("rect", { x: 110, y: 4, width: w * frac, height: 14, fill: color }));
  g.appendChild(svg("text", { x: 6, y: 15, fill: "#c8d6ef", "font-size": 11 }, label));
  g.appendChild(svg("text", { x: 110 + w + 6, y: 15, fill: "#9fb0cc", "font-size": 11 }, fmt(value, 3)));
  return g;
}

function heatmap(m4) {
  const size = 46, gap = 4;
  const g = svg("svg", { width: 4*(size+gap)+120, height: 4*(size+gap)+30 });
  let mx = 1e-12;
  m4.forEach(r => r.forEach(v => mx = Math.max(mx, Math.abs(v))));
  for (let i=0;i<4;i++) for (let j=0;j<4;j++) {
    const v = m4[i][j], t = Math.abs(v)/mx;
    const hue = v >= 0 ? 215 : 8;
    const x = 90 + j*(size+gap), y = 10 + i*(size+gap);
    g.appendChild(svg("rect", { x, y, width: size, height: size, fill: `hsl(${hue} 60% ${20+t*45}%)`, stroke:"#33415f"}));
    const txt = svg("text", { x: x+size/2, y: y+size/2+4, "text-anchor":"middle", fill:"#e7ecf5","font-size":10 }, fmt(v,3));
    g.appendChild(txt);
  }
  g.appendChild(svg("text", {x:6,y:24,fill:"#9fb0cc","font-size":11},"M_ij"));
  return g;
}

function renderTabs() {
  const ev = state.events.find(e => e.eventId === selectedEvent);
  const d = currentDerived();
  const design = $("tab-design"), matrix = $("tab-matrix"), physics = $("tab-physics"), decomp = $("tab-decomp");
  design.innerHTML = ""; matrix.innerHTML = ""; physics.innerHTML = ""; decomp.innerHTML = "";
  if (!ev) { design.textContent = "选择一个事件"; return; }

  // ---- design condition ----
  const cond = ev.payload.conditionNumber;
  const rank = ev.payload.rank, nullity = ev.payload.nullity;
  const logC = cond === "Infinity" ? 16 : Math.min(16, Math.log10(Math.max(1, cond)));
  design.appendChild(hbar(logC, 16, logC > 10 ? "#e08a4c" : "#5b8cff", "log10 cond"));
  design.appendChild(hbar(rank, 16, "#7fe0ad", "rank"));
  design.appendChild(hbar(nullity, 4, nullity ? "#e08a4c" : "#7fe0ad", "nullity"));
  const note = document.createElement("p");
  note.className = "small";
  note.textContent = `${ev.payload.status} — ${ev.payload.message || "唯一满秩解"}；奇异值: ${(ev.payload.singularValues||[]).map(v=>fmt(v,3)).join(", ")}`;
  design.appendChild(note);
  design.appendChild(idLine("event", ev.eventId, ev.fingerprint));
  if (d) design.appendChild(idLine("derived", d.derivedId, d.identity));

  // ---- matrix ----
  if (d && d.value.mueller) {
    matrix.appendChild(heatmap(d.value.mueller.m));
    matrix.appendChild(tableWithIds(d.value.mueller.m, d.eventId));
    if (d.value.covariance) {
      const p = document.createElement("p");
      p.className = "small";
      const diag = d.value.covariance.map((r,i)=>Math.sqrt(Math.max(0,r[i])));
      p.textContent = "元素 1σ: " + diag.map(v=>fmt(v,4)).join(", ") + `  · χ²=${fmt(d.value.chi2,3)}`;
      matrix.appendChild(p);
    }
  } else if (d && d.value.candidates) {
    matrix.appendChild(p("设计矩阵奇异/近奇异：不强行求逆。下列是保留的候选族成员（每个有独立稳定身份）。"));
  }

  // ---- physics + candidates ----
  renderCandidates(d, physics, matrix, decomp);
}

function p(text) { const e=document.createElement("p"); e.className="small"; e.textContent=text; return e; }

function idLine(kind, id, fp) {
  const div = document.createElement("div");
  div.className = "identity";
  div.textContent = `${kind}: ${id}  ·  identity: ${fp}`;
  return div;
}

function tableWithIds(m4, eventId) {
  const t = document.createElement("table");
  let html = "<tr><th></th><th>j=0</th><th>j=1</th><th>j=2</th><th>j=3</th><th>稳定身份</th></tr>";
  for (let i=0;i<4;i++) {
    html += `<tr><th>i=${i}</th>`;
    for (let j=0;j<4;j++) html += `<td>${fmt(m4[i][j])}</td>`;
    html += `<td class="identity">${eventId}:M${i}*</td></tr>`;
  }
  t.innerHTML = html;
  return t;
}

function renderCandidates(d, physics, matrix, decomp) {
  if (!d) return;
  const cands = d.value.candidates || [];
  if (!cands.length) return;
  cands.forEach((c, idx) => {
    const box = document.createElement("div");
    box.className = `cand ${idx===selectedCandidate?"chosen":""}`;
    const head = document.createElement("div");
    head.innerHTML = `<span class="tag ${c.physical?"OK":"OK_NONPHYSICAL"}">${c.physical?"物理可行":"违约"}</span>
      <b>${esc(c.kind)}</b> <span class="small">${esc(c.note)}</span>`;
    box.appendChild(head);
    if (idx === selectedCandidate) {
      const wrap = document.createElement("div");
      wrap.appendChild(heatmap(c.mueller.m));
      const vi = c.violations.filter(x=>!x.ok);
      if (vi.length) {
        const ul = document.createElement("ul");
        vi.forEach(v => {
          const li = document.createElement("li");
          li.className = "residual-bad";
          li.textContent = `${v.constraint}: 残差 ${fmt(v.observed)} > 容差 ${fmt(v.bound)} （只标记，不投影）`;
          ul.appendChild(li);
        });
        wrap.appendChild(ul);
      } else {
        wrap.appendChild(p("所有物理约束残差在容差内。"));
      }
      box.appendChild(wrap);
    }
    box.onclick = () => { selectedCandidate = idx; render(); };
    matrix.appendChild(box.cloneNode(true));
    physics.appendChild(box);
  });
  // residual bars for chosen candidate
  const chosen = cands[selectedCandidate];
  if (chosen) {
    chosen.violations.forEach(v => {
      const max = Math.max(1e-6, Math.abs(v.observed), v.bound*10);
      physics.appendChild(hbar(Math.max(0,v.observed), max, v.ok ? "#7fe0ad" : "#f0a2ac", v.constraint));
    });
    loadDecomposition(chosen.mueller.m, decomp);
  }
}

async function loadDecomposition(m4, decomp) {
  if (!selectedEvent) return;
  try {
    candidateData = await api(`/api/candidates/${encodeURIComponent(selectedEvent)}`);
  } catch (e) { decomp.textContent = "分解不可用（候选无唯一矩阵）"; return; }
  const decs = candidateData.decompositions || [];
  decomp.innerHTML = "";
  decs.forEach(dc => {
    const box = document.createElement("div");
    box.className = "cand chosen";
    box.innerHTML = `<div><b>${esc(dc.kind)}</b> <span class="tag ${dc.feasible?"OK":"OK_NONPHYSICAL"}">${dc.feasible?"可行":"标记"}</span>
      <div class="small">${esc(dc.note)}</div></div>`;
    const t = document.createElement("table");
    let rows = "";
    for (const [k,v] of Object.entries(dc.params)) {
      if (k.startsWith("m")) continue;
      rows += `<tr><th>${esc(k)}</th><td>${v.map(x=>fmt(x)).join(", ")}</td></tr>`;
    }
    t.innerHTML = rows;
    box.appendChild(t);
    decomp.appendChild(box);
  });
}

// ---------- actions ----------
function toast(msg) {
  const t = $("toast"); t.textContent = msg; t.classList.add("show");
  setTimeout(() => t.classList.remove("show"), 3500);
}
function showConflict(err) {
  const box = $("conflictBox");
  box.classList.remove("hidden");
  const d = err.body.diff || {};
  const theirs = (d.serverEventsAfterBase || []).map(e => `#${e.seq} ${e.eventId} ${e.type}`).join("\n");
  box.innerHTML = `<b>版本冲突 HTTP 409</b><br>你的基线修订号 ${d.clientBaseRevision} → 服务器 ${d.serverRevision}
    <pre>${esc(theirs)}</pre>原始数据未被改写；重新载入后可基于最新修订号提交。`;
  toast("提交被拒绝：版本冲突，已显示双方差异");
}

async function verdict(v) {
  const d = currentDerived();
  if (!d) return toast("先选择一个估计事件");
  const body = {
    branch, verdict: v,
    targetKind: d.kind, targetId: d.name,
    rationale: $("rationale").value,
    baseRevision
  };
  try {
    await api("/api/decide", { method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify(body) });
    $("rationale").value = ""; $("conflictBox").classList.add("hidden");
    toast(`${v} 已作为新事件追加（可撤销）`);
    await load();
  } catch (e) {
    if (e.status === 409) showConflict(e); else toast("错误: " + e.message);
  }
}

$("acceptBtn").onclick = () => verdict("ACCEPT");
$("rejectBtn").onclick = () => verdict("REJECT");

$("undoBtn").onclick = async () => {
  try {
    await api("/api/undo", { method:"POST", headers:{"Content-Type":"application/json"},
      body: JSON.stringify({ branch, baseRevision })});
    toast("已撤销，恢复到前一分支状态");
    await load();
  } catch (e) {
    if (e.status === 409) showConflict(e); else toast("错误: " + e.message);
  }
};

$("forkBtn").onclick = async () => {
  const name = prompt("新分支名称（对同一材料的重新解释，旧分支保留）", "reinterpret-" + Date.now());
  if (!name) return;
  const r = await api(`/api/branches/${encodeURIComponent(branch)}/fork?name=${encodeURIComponent(name)}`, {method:"POST"});
  branch = r.branchId; selectedEvent = null; await load();
};

$("branchSelect").onchange = async e => {
  branch = e.target.value; selectedEvent = null; $("conflictBox").classList.add("hidden"); await load();
};

document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => {
  document.querySelectorAll(".tabs button").forEach(x=>x.classList.remove("active"));
  document.querySelectorAll(".tab").forEach(x=>x.classList.remove("active"));
  b.classList.add("active");
  $(`tab-${b.dataset.tab}`).classList.add("active");
});

document.querySelectorAll(".replay button[data-step]").forEach(b => b.onclick = async () => {
  try {
    await api(`/api/replay/step/${b.dataset.step}`, {method:"POST", body:"{}", headers:{"Content-Type":"application/json"}});
    selectedEvent = null; await load(); toast("重放步骤已作为新事件追加");
  } catch (e) { toast("重放错误: " + e.message); }
});

$("conflictDemo").onclick = async () => {
  const d = currentDerived();
  if (!d) return;
  const base = baseRevision;
  const post = verdict => api("/api/decide", {method:"POST", headers:{"Content-Type":"application/json"},
    body: JSON.stringify({branch, verdict, targetKind:d.kind, targetId:d.name,
      rationale:"conflict demo "+verdict, baseRevision: base})});
  await post("ACCEPT");                 // window A commits
  try {
    await post("REJECT");              // window B stale revision
  } catch (e) {
    if (e.status === 409) { showConflict(e); await load(); return; }
  }
  toast("意外：未检测到冲突");
};

let exported = null;
$("exportBtn").onclick = async () => {
  exported = await api("/api/export");
  toast("快照已导出（身份/顺序/来源链均在内），并保存到 export-bundle.json");
};
$("importBtn").onclick = async () => {
  if (!exported) exported = await api("/api/export");
  if (!confirm("将请求服务器删除本地数据库文件后从快照重建，继续？")) return;
  const body = JSON.stringify(exported);
  const r = await fetch("/api/rebuild", {method:"POST", headers:{"Content-Type":"application/json"}, body});
  if (!r.ok) return toast("导入失败: 本次变更回滚，无半份记录");
  selectedEvent = null; await load();
  toast("删除后重新导入完成：身份、顺序、来源关系保持一致");
};

load().catch(e => toast("载入失败: " + e));
