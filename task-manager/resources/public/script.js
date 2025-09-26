//  Helpers / API 
const API_BASE = location.origin.startsWith("file:")
  ? "http://localhost:3000"
  : location.origin;

async function API(path, opts = {}) {
  const res = await fetch(API_BASE + path, {
    headers: { "Content-Type": "application/json" },
    ...opts
  });
  let data = null;
  try { data = await res.json(); } catch (_) { /* body may be empty */ }
  if (!res.ok) {
    const msg = (data && data.error) || res.statusText || "Request failed";
    throw new Error(msg);
  }
  return data;
}

const el = sel => document.querySelector(sel);
const byId = id => document.getElementById(id);

function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;")
    .replaceAll('"',"&quot;").replaceAll("'","&#039;");
}

//  Summary 
async function loadSummary() {
  try {
    const s = await API("/summary");
    byId("summary").innerHTML =
      `Ukupno: <b>${s.total}</b> • Status: ${fmtFreq(s["by-status"])} • ` +
      `Prioritet: ${fmtFreq(s["by-priority"])} • Overdue: <b>${s.overdue}</b>`;
  } catch (e) {
    console.error("Summary error:", e);
    byId("summary").textContent = "Greška pri učitavanju pregleda.";
  }
}
function fmtFreq(obj) {
  if (!obj) return "-";
  return Object.entries(obj).map(([k, v]) => `${k}:${v}`).join(", ");
}

//  Filters 
function readFilters() {
  const params = new URLSearchParams();
  const S = el("#filterStatus")?.value?.trim();
  const P = el("#filterPriority")?.value?.trim();
  const T = el("#filterTag")?.value?.trim();
  const Q = el("#filterQ")?.value?.trim();
  const D = el("#filterDueBefore")?.value;

  if (S) params.set("status", S);
  if (P) params.set("priority", P);
  if (T) params.set("tag", T);
  if (Q) params.set("q", Q);
  if (D) params.set("due_before", D);

  const qs = params.toString();
  console.log("Filter QS ->", qs || "(none)");
  return qs;
}

//  helpers 
function prioRank(p){ return {high:3, medium:2, low:1}[p] || 0; }
function statusRank(s){ return {todo:1, doing:2, blocked:3, done:4}[s] || 0; }
function toDateOrMax(s){ return s ? new Date(s) : new Date("9999-12-31"); }
function sortTasks(tasks, mode){
  const arr = [...tasks];
  switch(mode){
    case "due-asc":  return arr.sort((a,b)=> toDateOrMax(a.due)-toDateOrMax(b.due));
    case "due-desc": return arr.sort((a,b)=> toDateOrMax(b.due)-toDateOrMax(a.due));
    case "prio-desc":return arr.sort((a,b)=> prioRank(b.priority)-prioRank(a.priority));
    case "prio-asc": return arr.sort((a,b)=> prioRank(a.priority)-prioRank(b.priority));
    case "status":   return arr.sort((a,b)=> statusRank(a.status)-statusRank(b.status) || prioRank(b.priority)-prioRank(a.priority));
    case "created-asc":  return arr.sort((a,b)=> new Date(a["created-at"]) - new Date(b["created-at"]));
    case "created-desc":
    default:         return arr.sort((a,b)=> new Date(b["created-at"]) - new Date(a["created-at"]));
  }
}
function isOverdue(t){
  if(!t.due || t.status === "done") return false;
  const today = new Date(); today.setHours(0,0,0,0);
  return new Date(t.due) < today;
}

//  Tasks List / Render 
async function loadTasks() {
  try {
    const qs = readFilters();
    const url = qs ? `/tasks?${qs}` : "/tasks";
    const tasks = await API(url);

    const mode = byId("sortSelect")?.value || "created-desc";
    const sorted = sortTasks(tasks, mode);

    renderTasks(sorted);
    byId("resultsCount").innerHTML =
      `<span class="count-highlight">${sorted.length}</span> rezultata`;
  } catch (e) {
    console.error("Load tasks error:", e);
    byId("tasks").innerHTML = `<div class="empty">Greška: ${escapeHtml(e.message)}</div>`;
    byId("resultsCount").textContent = "0 rezultata";
  }
}

function badge(cls, text) {
  return `<span class="badge ${cls}">${escapeHtml(text)}</span>`;
}

function renderTasks(tasks) {
  const wrap = byId("tasks");
  if (!tasks || !tasks.length) {
    wrap.innerHTML = `<div class="empty">Nema taskova za prikaz.</div>`;
    return;
  }
  wrap.innerHTML = tasks.map(t => {
    const tags = (t.tags || []).map(x => `<span class="tag">${escapeHtml(x)}</span>`).join(" ");
    const due = t.due ? `• Rok: <b>${escapeHtml(t.due)}</b>` : "";
    const overdueChip = isOverdue(t) ? `<span class="overdue-chip">OVERDUE</span>` : "";
    const classes = `task${isOverdue(t) ? " overdue" : ""}`;
    return `
      <div class="${classes}" data-id="${t.id}">
        <div class="row" style="justify-content:space-between;align-items:center;">
          <div>
            <div><b>${escapeHtml(t.title)}</b></div>
            <div class="meta">
              ${badge(t.status, t.status)}
              ${badge(t.priority, `priority:${t.priority}`)}
              ${due} ${overdueChip}
            </div>
          </div>
          <div class="actions">
            ${t.status !== 'done' ? `<button onclick="completeTask(${t.id})">Complete</button>` : ""}
            <button class="ghost" onclick="editTask(${t.id})">Edit</button>
            <button class="danger" onclick="deleteTask(${t.id})">Delete</button>
          </div>
        </div>
        ${t.desc ? `<div class="muted">${escapeHtml(t.desc)}</div>` : ""}
        <div>${tags}</div>
      </div>
    `;
  }).join("");
}

//  Actions 
async function createTask(e) {
  e.preventDefault();
  const f = e.target;
  const payload = {
    title: f.title.value.trim(),
    desc: f.desc.value.trim(),
    priority: f.priority.value,
    due: f.due.value || null,
    tags: f.tags.value.split(",").map(s => s.trim()).filter(Boolean)
  };
  if (!payload.title) { alert("Naslov je obavezan."); return; }

  try {
    await API("/tasks", { method: "POST", body: JSON.stringify(payload) });
    f.reset();
    await Promise.all([loadTasks(), loadSummary()]);
  } catch (e2) {
    console.error("Create error:", e2);
    alert("Greška: " + e2.message);
  }
}

async function completeTask(id) {
  try {
    await API(`/tasks/${id}/complete`, { method: "POST" });
    await Promise.all([loadTasks(), loadSummary()]);
  } catch (e) {
    console.error("Complete error:", e);
    alert("Greška: " + e.message);
  }
}

async function deleteTask(id) {
  if (!confirm("Obrisati task?")) return;
  try {
    await API(`/tasks/${id}/delete`, { method: "POST" });
    await Promise.all([loadTasks(), loadSummary()]);
  } catch (e) {
    console.error("Delete error:", e);
    alert("Greška: " + e.message);
  }
}

async function editTask(id) {
  const title = prompt("Novi naslov (enter preskoči)", "");
  const desc = prompt("Novi opis (enter preskoči)", "");
  const status = prompt("Status (todo/doing/blocked/done) – enter preskoči", "");
  const priority = prompt("Prioritet (low/medium/high) – enter preskoči", "");
  const due = prompt("Rok (YYYY-MM-DD) – enter preskoči", "");
  const tags = prompt("Tagovi (zarez) – enter preskoči", "");

  const patch = {};
  if (title) patch.title = title;
  if (desc) patch.desc = desc;
  if (status) patch.status = status;
  if (priority) patch.priority = priority;
  if (due) patch.due = due;
  if (tags) patch.tags = tags.split(",").map(s => s.trim()).filter(Boolean);

  if (Object.keys(patch).length === 0) return;

  try {
    await API(`/tasks/${id}/update`, { method: "POST", body: JSON.stringify(patch) });
    await Promise.all([loadTasks(), loadSummary()]);
  } catch (e) {
    console.error("Update error:", e);
    alert("Greška: " + e.message);
  }
}

async function resetDb() {
  if (!confirm("Reset baze? (svi podaci će biti izbrisani)")) return;
  try {
    await API("/reset-db", { method: "POST" });
    await Promise.all([loadTasks(), loadSummary()]);
  } catch (e) {
    console.error("Reset error:", e);
    alert("Greška: " + e.message);
  }
}

//  Wiring 
function wire() {
  byId("newTaskForm")?.addEventListener("submit", createTask);

  byId("btnApply")?.addEventListener("click", loadTasks);
  byId("btnClear")?.addEventListener("click", () => {
    const ids = ["#filterStatus", "#filterPriority", "#filterTag", "#filterQ", "#filterDueBefore"];
    ids.forEach(s => { const n = el(s); if (n) n.value = ""; });
    loadTasks();
  });
  byId("btnResetDb")?.addEventListener("click", resetDb);
  byId("sortSelect")?.addEventListener("change", loadTasks);

  ["#filterStatus","#filterPriority","#filterTag","#filterQ","#filterDueBefore"]
    .forEach(s => el(s)?.addEventListener("change", loadTasks));
}

window.addEventListener("DOMContentLoaded", async () => {
  wire();
  await loadSummary();
  await loadTasks();
});


