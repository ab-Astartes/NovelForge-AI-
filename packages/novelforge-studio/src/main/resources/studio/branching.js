/* ===================== 分支剧情 / 互动小说 =====================
   后端读取 truth/branching.json（节点+选择支），或按章节自动生成骨架；
   本面板用 SVG 绘制剧情树（起点/场景/结局三色分层），支持点击编辑节点、
   增删选择支、保存结构与导出零依赖的互动阅读器（纯本地，零 AI 成本）。 */

let branchNodes = [];
let branchEdges = [];
let branchSel = null;
let branchBook = '';

async function loadBranching() {
  const book = document.getElementById('branching-book')?.value?.trim();
  const box = document.getElementById('branching-body');
  if (!box) return;
  if (!book) { box.innerHTML = '<div class="empty-hint">请先在上方选择书目。</div>'; return; }
  branchBook = book;
  box.innerHTML = '<div class="empty-hint">正在读取剧情树…</div>';
  try {
    const r = await fetch(authUrl(API + '/api/branching?path=' + encodeURIComponent(book)), { headers: authHeaders() });
    const j = await r.json();
    if (!j.ok) { box.innerHTML = `<div class="empty-hint">读取失败：${escapeHtml(j.error || '未知')}</div>`; return; }
    branchNodes = (j.nodes || []).map(n => ({ ...n, chapterRef: Number(n.chapterRef) || 0 }));
    branchEdges = (j.edges || []).map(e => ({ ...e }));
    branchSel = null;
    if (j.scaffolded) showToast('尚无保存的分支结构，已按章节生成骨架，编辑后点「保存」', 'info', 2600);
    renderBranching();
    updateBranchStats(j.stats || {});
  } catch (e) {
    box.innerHTML = `<div class="empty-hint">请求失败：${escapeHtml(e.message)}</div>`;
  }
}

async function scaffoldBranching() {
  const book = branchBook || (document.getElementById('branching-book')?.value?.trim() || '');
  if (!book) { showToast('请先选择书目', 'warning'); return; }
  branchBook = book;
  try {
    const r = await fetch(authUrl(API + '/api/branching?path=' + encodeURIComponent(book) + '&scaffold=1'), { headers: authHeaders() });
    const j = await r.json();
    if (!j.ok) { showToast('生成骨架失败：' + (j.error || ''), 'error'); return; }
    branchNodes = (j.nodes || []).map(n => ({ ...n, chapterRef: Number(n.chapterRef) || 0 }));
    branchEdges = (j.edges || []).map(e => ({ ...e }));
    branchSel = null;
    renderBranching();
    updateBranchStats(j.stats || {});
    showToast('已按章节生成骨架（' + branchNodes.length + ' 个节点）', 'success');
  } catch (e) { showToast('生成骨架失败：' + e.message, 'error'); }
}

function updateBranchStats(s) {
  const st = document.getElementById('branching-stats');
  if (!st) return;
  st.textContent = `节点 ${s.nodes || 0} · 连线 ${s.edges || 0} · 起点 ${s.startCount || 0} · 结局 ${s.endingCount || 0} · 可达 ${s.reachable || 0}/${s.nodes || 0} · 纵深 ${s.depth || 0} · 告警 ${s.warnings || 0}`;
}

function renderBranching() {
  const box = document.getElementById('branching-body');
  if (!box) return;
  if (!branchNodes.length) {
    box.innerHTML = '<div class="empty-hint">暂无节点。点击「从章节生成骨架」或「新增节点」开始构建你的分支剧情。</div>';
    return;
  }
  box.innerHTML =
      '<div class="branching-graph-wrap"><div id="branching-graph">' + renderBranchGraph() + '</div></div>'
    + '<div id="branching-warns" class="glossary-warns"></div>'
    + '<div id="branching-editor"></div>';
  renderBranchWarnings();
  renderBranchEditor();
}

function branchTypeLabel(t) {
  return t === 'start' ? '起点' : t === 'ending' ? '结局' : '场景';
}
function branchXmlEsc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** SVG 分层剧情树 */
function renderBranchGraph() {
  const PADL = 60, PADR = 30, PADT = 28, PADB = 28, colW = 210, rowGap = 80, nodeW = 152, nodeH = 56;
  const byId = {};
  branchNodes.forEach(n => byId[n.id] = n);
  const starts = branchNodes.filter(n => n.type === 'start').map(n => n.id);
  if (!starts.length && branchNodes.length) starts.push(branchNodes[0].id);

  // BFS 分层
  const depth = {};
  starts.forEach(s => depth[s] = 0);
  const q = [...starts];
  while (q.length) {
    const cur = q.shift();
    const d = depth[cur];
    branchEdges.filter(e => e.from === cur).forEach(e => {
      if (depth[e.to] === undefined) { depth[e.to] = d + 1; q.push(e.to); }
    });
  }
  let maxD = 0; Object.values(depth).forEach(d => maxD = Math.max(maxD, d));
  const hasUnreach = branchNodes.some(n => depth[n.id] === undefined);
  const lastLayer = maxD + (hasUnreach ? 1 : 0);
  const layers = {};
  branchNodes.forEach(n => {
    const d = depth[n.id] !== undefined ? depth[n.id] : lastLayer;
    (layers[d] = layers[d] || []).push(n);
  });
  const maxCount = Math.max(1, ...Object.values(layers).map(a => a.length));
  const H = PADT + maxCount * rowGap + PADB;
  const W = PADL + lastLayer * colW + nodeW + PADR;
  const cx = d => PADL + d * colW + nodeW / 2;
  const cyOf = i => PADT + nodeH / 2 + i * rowGap;

  const pos = {};
  Object.keys(layers).forEach(d => {
    layers[d].forEach((n, i) => { pos[n.id] = { x: cx(Number(d)), y: cyOf(i) }; });
  });

  let svg = `<svg viewBox="0 0 ${W} ${H}" class="branching-svg" preserveAspectRatio="xMidYMin meet">`;
  svg += '<defs><marker id="bg-arrow" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">'
       + '<path d="M0,0 L7,3 L0,6 Z" fill="#8b7355"/></marker></defs>';

  // 边
  branchEdges.forEach(e => {
    const a = pos[e.from], b = pos[e.to];
    if (!a || !b) return;
    const mx = (a.x + b.x) / 2;
    svg += `<path d="M${a.x},${a.y} C${mx},${a.y} ${mx},${b.y} ${b.x},${b.y}" class="bg-edge" marker-end="url(#bg-arrow)"/>`;
    const my = (a.y + b.y) / 2;
    const label = branchXmlEsc(e.choice || '继续');
    svg += `<text x="${mx}" y="${my - 4}" text-anchor="middle" class="bg-edge-label">${label}</text>`;
  });

  // 节点
  branchNodes.forEach(n => {
    const p = pos[n.id];
    if (!p) return;
    const x = p.x - nodeW / 2, y = p.y - nodeH / 2;
    const sel = branchSel === n.id ? ' bg-sel' : '';
    const reach = depth[n.id] === undefined ? ' bg-unreach' : '';
    svg += `<g class="bg-node bg-${n.type}${sel}${reach}" onclick="selectBranchNode('${n.id}')" style="cursor:pointer">`
        + `<rect x="${x}" y="${y}" width="${nodeW}" height="${nodeH}" rx="9" class="bg-rect"/>`
        + `<text x="${p.x}" y="${p.y - 5}" text-anchor="middle" class="bg-title">${branchXmlEsc((n.title || n.id).slice(0, 12))}</text>`
        + `<text x="${p.x}" y="${p.y + 13}" text-anchor="middle" class="bg-type">${branchTypeLabel(n.type)}</text>`
        + `</g>`;
  });

  svg += '</svg>';
  return svg;
}

function renderBranchWarnings() {
  const box = document.getElementById('branching-warns');
  if (!box) return;
  const warns = computeBranchWarnings();
  if (!warns.length) {
    box.innerHTML = '<div class="glossary-warn warn-ok"><span class="warn-tag">ok</span>结构健康：起点唯一、每条分支都能抵达结局。</div>';
    return;
  }
  let html = '';
  warns.forEach(w => {
    const cls = w.level === 'error' ? 'warn-error' : w.level === 'warn' ? 'warn-warn' : 'warn-info';
    html += `<div class="glossary-warn ${cls}"><span class="warn-tag">${branchXmlEsc(w.type)}</span>${branchXmlEsc(w.message)}</div>`;
  });
  box.innerHTML = html;
}

function renderBranchEditor() {
  const wrap = document.getElementById('branching-editor');
  if (!wrap) return;
  if (!branchNodes.length) { wrap.innerHTML = ''; return; }

  let html = '<div class="branching-cols">';
  // 节点列表
  html += '<div class="branching-nodelist card"><div class="card-title">📋 节点（' + branchNodes.length + '）</div><div class="branching-nodes">';
  branchNodes.forEach(n => {
    html += `<div class="bg-node-row ${branchSel === n.id ? 'row-sel' : ''}" onclick="selectBranchNode('${n.id}')">`
        + `<span class="bg-dot bg-${n.type}"></span>`
        + `<span class="bg-row-title">${escapeHtml(n.title || n.id)}</span>`
        + `<span class="bg-row-type">${branchTypeLabel(n.type)}</span></div>`;
  });
  html += '</div></div>';

  // 编辑区
  html += '<div class="branching-edit card"><div class="card-title">✎ 编辑节点</div>';
  if (!branchSel) {
    html += '<div class="card-hint">点击上方节点或左侧剧情树中的节点进行编辑；或在工具栏「新增节点」。</div>';
  } else {
    const n = branchNodes.find(x => x.id === branchSel);
    if (n) {
      html += `<label class="bf-field"><span>标题</span><input id="bf-title" class="input-field" value="${escapeHtml(n.title)}" oninput="updateBranchField('${n.id}','title',this.value)"></label>`;
      html += `<label class="bf-field"><span>类型</span><select id="bf-type" class="input-field" onchange="updateBranchField('${n.id}','type',this.value)">`
        + `<option value="start" ${n.type === 'start' ? 'selected' : ''}>起点</option>`
        + `<option value="scene" ${n.type === 'scene' ? 'selected' : ''}>场景</option>`
        + `<option value="ending" ${n.type === 'ending' ? 'selected' : ''}>结局</option></select></label>`;
      html += `<label class="bf-field"><span>章节引用</span><input id="bf-ch" class="input-field" type="number" value="${n.chapterRef || 0}" oninput="updateBranchField('${n.id}','chapterRef',this.value)"></label>`;
      html += `<label class="bf-field bf-col"><span>摘要</span><textarea id="bf-ex" class="input-field" oninput="updateBranchField('${n.id}','excerpt',this.value)">${escapeHtml(n.excerpt || '')}</textarea></label>`;
      const outs = branchEdges.filter(e => e.from === n.id);
      html += '<div class="bf-edges"><div class="bf-sub">选择支（出边） ' + outs.length + '</div>';
      outs.forEach(e => {
        const tgt = branchNodes.find(x => x.id === e.to);
        html += `<div class="bf-edge-row"><span class="bf-edge-choice">${escapeHtml(e.choice || '继续')}</span> → <span>${escapeHtml(tgt ? tgt.title : e.to)}</span>`
          + `<button class="btn-ghost btn-xs" onclick="deleteBranchEdge('${e.from}','${e.to}')">✕</button></div>`;
      });
      html += `<div class="bf-addedge"><input id="bf-choice" class="input-field" placeholder="选择支文案，如：东行" style="flex:1;min-width:80px">`
        + `<select id="bf-target" class="input-field">${branchNodes.filter(x => x.id !== n.id).map(x => `<option value="${x.id}">${escapeHtml(x.title || x.id)}</option>`).join('')}</select>`
        + `<button class="btn-ink btn-xs" onclick="addBranchEdge('${n.id}')">＋ 连线</button></div>`;
      html += '</div>';
      html += `<div class="btn-row mt-2"><button class="btn-ghost btn-sm" onclick="deleteBranchNode('${n.id}')">🗑 删除节点</button></div>`;
    }
  }
  html += '</div></div>';
  wrap.innerHTML = html;
}

function selectBranchNode(id) {
  branchSel = id;
  renderBranching();
}

function updateBranchField(id, field, value) {
  const n = branchNodes.find(x => x.id === id);
  if (!n) return;
  if (field === 'chapterRef') n.chapterRef = Number(value) || 0;
  else n[field] = value;
  renderBranching();
}

function addBranchEdge(fromId) {
  const choice = document.getElementById('bf-choice')?.value?.trim() || '继续';
  const to = document.getElementById('bf-target')?.value;
  if (!to) { showToast('请选择连线目标', 'warning'); return; }
  if (to === fromId) { showToast('不能连接到自身', 'warning'); return; }
  if (branchEdges.some(e => e.from === fromId && e.to === to)) { showToast('该选择支已存在', 'info'); return; }
  branchEdges.push({ from: fromId, to: to, choice: choice });
  renderBranching();
  showToast('已添加选择支：' + choice, 'success', 1200);
}

function deleteBranchEdge(from, to) {
  branchEdges = branchEdges.filter(e => !(e.from === from && e.to === to));
  renderBranching();
}

function deleteBranchNode(id) {
  branchNodes = branchNodes.filter(n => n.id !== id);
  branchEdges = branchEdges.filter(e => e.from !== id && e.to !== id);
  if (branchSel === id) branchSel = null;
  renderBranching();
  showToast('已删除节点', 'info', 1200);
}

function addBranchNode() {
  let max = 0;
  branchNodes.forEach(n => { const m = /^n(\d+)$/.exec(n.id); if (m) max = Math.max(max, Number(m[1])); });
  const id = 'n' + (max + 1);
  branchNodes.push({ id: id, title: '新节点', type: 'scene', chapterRef: 0, excerpt: '' });
  branchSel = id;
  renderBranching();
}

async function saveBranching() {
  if (!branchBook) { showToast('请先选择书目', 'warning'); return; }
  if (!branchNodes.length) { showToast('剧情树为空，无法保存', 'warning'); return; }
  try {
    const r = await fetch(authUrl(API + '/api/branching'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: branchBook, nodes: branchNodes, edges: branchEdges })
    });
    const j = await r.json();
    if (!j.ok) { showToast('保存失败：' + (j.error || ''), 'error'); return; }
    showToast('已保存分支剧情结构（' + j.nodes + ' 节点 / ' + j.edges + ' 连线）', 'success');
    updateBranchStats({ nodes: j.nodes, edges: j.edges });
  } catch (e) { showToast('保存失败：' + e.message, 'error'); }
}

/** 客户端实时校验（与后端逻辑一致），保证编辑即有反馈 */
function computeBranchWarnings() {
  const warns = [];
  if (!branchNodes.length) {
    warns.push({ level: 'warn', type: 'empty', node: '', message: '尚未定义任何剧情节点，请在工具栏「新增节点」或「从章节生成骨架」。' });
    return warns;
  }
  const adj = {}; branchNodes.forEach(n => adj[n.id] = []);
  branchEdges.forEach(e => { if (adj[e.from]) adj[e.from].push(e.to); });
  const indeg = {}, outdeg = {};
  branchNodes.forEach(n => { indeg[n.id] = 0; outdeg[n.id] = 0; });
  branchEdges.forEach(e => { if (outdeg[e.from] !== undefined) outdeg[e.from]++; if (indeg[e.to] !== undefined) indeg[e.to]++; });
  const starts = branchNodes.filter(n => n.type === 'start').map(n => n.id);
  if (!starts.length && branchNodes.length) starts.push(branchNodes[0].id);
  const reach = new Set(starts);
  const q = [...starts];
  while (q.length) { const c = q.shift(); (adj[c] || []).forEach(nx => { if (reach.add(nx)) q.push(nx); }); }
  let startC = starts.length, endC = 0;
  branchNodes.forEach(n => {
    if (n.type === 'ending') endC++;
    const od = outdeg[n.id] || 0, idg = indeg[n.id] || 0;
    if (n.type !== 'ending' && od === 0)
      warns.push({ level: 'error', type: 'deadend', node: n.id, message: `节点「${n.title || n.id}」是死胡同：无出边且非结局，读者将无路可走。` });
    if (idg === 0 && od === 0)
      warns.push({ level: 'warn', type: 'isolated', node: n.id, message: `节点「${n.title || n.id}」是孤立节点：既无入边也无出边。` });
    if (!reach.has(n.id))
      warns.push({ level: 'warn', type: 'unreachable', node: n.id, message: `节点「${n.title || n.id}」从起点不可达，当前剧情树无法到达。` });
  });
  if (endC === 0) warns.push({ level: 'error', type: 'noending', node: '', message: '剧情树缺少结局节点（type=ending），读者永远无法通关。' });
  if (startC > 1) warns.push({ level: 'info', type: 'multistart', node: '', message: `检测到 ${startC} 个起点，互动小说通常只有一个入口。` });
  if (branchHasCycle()) warns.push({ level: 'info', type: 'cycle', node: '', message: '剧情树检测到环路（分支会回到已走过的节点）。' });
  return warns;
}

function branchHasCycle() {
  const adj = {}; branchNodes.forEach(n => adj[n.id] = []);
  branchEdges.forEach(e => { if (adj[e.from]) adj[e.from].push(e.to); });
  const vis = {}, stk = {};
  function dfs(u) {
    vis[u] = 1; stk[u] = 1;
    for (const v of (adj[u] || [])) {
      if (!vis[v]) { if (dfs(v)) return true; }
      else if (stk[v]) return true;
    }
    stk[u] = 0; return false;
  }
  for (const n of branchNodes) if (!vis[n.id] && dfs(n.id)) return true;
  return false;
}

/** 导出零依赖的互动阅读器（自包含 HTML，可直接浏览器打开） */
function exportBranching() {
  if (!branchNodes.length) { showToast('请先加载或生成剧情树', 'warning'); return; }
  const title = (branchBook.split(/[\\/]/).pop() || '互动小说');
  const data = {
    title: title,
    nodes: branchNodes.map(n => ({ id: n.id, title: n.title, type: n.type, excerpt: n.excerpt, chapterRef: n.chapterRef })),
    edges: branchEdges.map(e => ({ from: e.from, to: e.to, choice: e.choice }))
  };
  const json = JSON.stringify(data).replace(/</g, '\\u003c');
  const html = buildInteractiveHtml(title, json);
  downloadFile('\uFEFF' + html, `互动小说_${title}.html`, 'text/html;charset=utf-8');
  showToast('互动阅读器已导出（零依赖 HTML）', 'success');
}

function buildInteractiveHtml(title, json) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${branchXmlEsc(title)} · 互动小说</title>
<style>
  body{font-family:"Noto Serif SC",serif;background:#1a1410;color:#e8d5b7;margin:0;padding:24px;line-height:1.9}
  .wrap{max-width:720px;margin:0 auto}
  h1{color:#c0392b;border-bottom:1px solid #3a2e22;padding-bottom:10px}
  .scene{background:#241b14;border:1px solid #3a2e22;border-radius:12px;padding:20px;margin:16px 0}
  .tag{display:inline-block;font-size:12px;padding:2px 10px;border-radius:20px;margin-bottom:10px}
  .tag.start{background:#1e3a24;color:#7fd99a}.tag.scene{background:#1e2a3a;color:#7fb6d9}.tag.ending{background:#3a2e10;color:#e6c45a}
  .choices{margin-top:18px;display:flex;flex-direction:column;gap:10px}
  button{font-family:inherit;font-size:15px;background:#2e2118;color:#e8d5b7;border:1px solid #5a4632;border-radius:8px;padding:12px 16px;cursor:pointer;text-align:left}
  button:hover{background:#3a2a1e;border-color:#7a5e3e}
  .bar{margin-top:20px;display:flex;justify-content:space-between;font-size:13px;color:#8b7355}
  .empty{color:#8b7355;font-style:italic}
</style>
</head>
<body>
<div class="wrap"><div id="app">加载中…</div></div>
<script>
const DATA = ${json};
const byId = {}; DATA.nodes.forEach(n => byId[n.id] = n);
const starts = DATA.nodes.filter(n => n.type === 'start').map(n => n.id);
let current = (starts[0] || (DATA.nodes[0] && DATA.nodes[0].id) || null);
let history = [];
function render() {
  const app = document.getElementById('app');
  const n = byId[current];
  if (!n) { app.innerHTML = '<h1>${branchXmlEsc(title)}</h1><div class="scene empty">未找到节点。</div>'; return; }
  let tagTxt = n.type === 'start' ? '起点' : n.type === 'ending' ? '结局' : '场景';
  let html = '<h1>${branchXmlEsc(title)}</h1>';
  html += '<div class="scene"><span class="tag ' + n.type + '">' + tagTxt + (n.chapterRef ? ' · 第' + n.chapterRef + '章' : '') + '</span>';
  html += '<div>' + (n.excerpt ? escapeHtml(n.excerpt) : '<span class="empty">（无摘要）</span>') + '</div>';
  const outs = DATA.edges.filter(e => e.from === n.id);
  if (n.type === 'ending') {
    html += '<div class="choices"><button onclick="restart()">↺ 重新开始</button></div>';
  } else if (outs.length) {
    html += '<div class="choices">';
    outs.forEach((e, i) => { html += '<button onclick="go(\'' + e.to + '\')">' + (i + 1) + '. ' + escapeHtml(e.choice || '继续') + '</button>'; });
    html += '</div>';
  } else {
    html += '<div class="scene empty" style="margin-top:12px">（此节点尚无选择支，故事在此中断。）</div>';
  }
  html += '<div class="bar"><span>节点：' + escapeHtml(n.title) + '</span><span><button style="padding:4px 10px" onclick="back()">← 返回</button></span></div>';
  html += '</div>';
  app.innerHTML = html;
}
function go(id){ if(byId[id]){ history.push(current); current = id; render(); } }
function back(){ if(history.length){ current = history.pop(); render(); } }
function restart(){ history = []; current = (DATA.nodes.filter(n=>n.type==='start')[0]||DATA.nodes[0]).id; render(); }
function escapeHtml(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
render();
<\/script>
</body>
</html>`;
}
