/* ===================== 分支剧情 / 互动小说 =====================
   后端读取 truth/branching.json（节点+选择支），或按章节自动生成骨架；
   本面板用 SVG 绘制剧情树（起点/场景/结局三色分层），支持点击编辑节点、
   增删选择支、保存结构与导出零依赖的互动阅读器（纯本地，零 AI 成本）。 */

let branchNodes = [];
let branchEdges = [];
let branchSel = null;
let branchBook = '';
let branchOutline = { present: false, gaps: [], volumes: [], volumeMap: {} };
let branchCollapsed = new Set();
let branchZoom = 1, branchPanX = 0, branchPanY = 0;
let branchColorByVolume = false;
let branchEdgeCond = new Set();
let branchState = null;
let branchStateDebugOn = false;
let branchSuggestions = [];

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
    branchOutline = j.outline || { present: false, gaps: [], volumes: [], volumeMap: {} };
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
    branchOutline = j.outline || { present: false, gaps: [], volumes: [], volumeMap: {} };
    renderBranching();
    updateBranchStats(j.stats || {});
    showToast('已按章节生成骨架（' + branchNodes.length + ' 个节点）', 'success');
  } catch (e) { showToast('生成骨架失败：' + e.message, 'error'); }
}

function updateBranchStats(s) {
  const st = document.getElementById('branching-stats');
  branchLastStats = s || {};
  if (!st) return;
  const shortEnd = (s.shortestToEnding == null || s.shortestToEnding < 0) ? '—' : s.shortestToEnding;
  st.innerHTML = `节点 ${s.nodes || 0} · 连线 ${s.edges || 0} · 起点 ${s.startCount || 0} · 结局 ${s.endingCount || 0}`
    + ` · 可达 ${s.reachable || 0}/${s.nodes || 0} · 纵深 ${s.depth || 0}`
    + ` · 最短通关 ${shortEnd} · 最长链 ${s.longestChain || 0} · 最大分叉 ${s.maxBranchWidth || 0} · 告警 ${s.warnings || 0}`;
  const info = document.getElementById('branching-statinfo');
  if (info) {
    const wd = (s.widthDist || []);
    const maxC = wd.reduce((m, d) => Math.max(m, d.count || 0), 0) || 1;
    let bars = wd.map(d => {
      const pct = Math.round((d.count / maxC) * 100);
      return `<div class="wd-row"><span class="wd-label">出度${d.outdeg}</span>`
        + `<span class="wd-bar"><i style="width:${pct}%"></i></span>`
        + `<span class="wd-count">${d.count}</span></div>`;
    }).join('');
    const hint = (s.startBranch || 0) >= 3 ? '<div class="wd-hint warn">⚠ 开局即分叉 ' + s.startBranch + ' 路，考虑先铺沉淀再分叉。</div>'
      : (s.longestChain || 0) >= 8 ? '<div class="wd-hint warn">⚠ 最长单链 ' + s.longestChain + ' 步偏长，注意中途给选择点。</div>' : '';
    info.innerHTML = (bars || hint) ? ('<div class="wd-title">分支宽度分布（出度→节点数）</div>' + bars + hint) : '';
  }
}

function renderBranching() {
  const box = document.getElementById('branching-body');
  if (!box) return;
  if (!branchNodes.length) {
    box.innerHTML = '<div class="empty-hint">暂无节点。点击「从章节生成骨架」或「新增节点」开始构建你的分支剧情。</div>';
    return;
  }
  box.innerHTML =
      '<div class="branching-graph-wrap">'
    + '<div class="branching-graph-bar">'
    + '<button class="btn-ghost btn-xs" onclick="branchZoomBy(1.2)">🔍＋</button>'
    + '<button class="btn-ghost btn-xs" onclick="branchZoomBy(1/1.2)">🔍－</button>'
    + '<button class="btn-ghost btn-xs" onclick="branchResetView()">↺ 视图</button>'
    + '<button class="btn-ghost btn-xs" id="btn-colorby" onclick="toggleBranchColorBy()">🎨 按卷着色</button>'
    + '<span class="branching-graph-tip">滚轮缩放 · 拖拽平移 · 点节点折叠子树</span>'
    + '</div>'
    + '<div id="branching-graph">' + renderBranchGraph() + '</div>'
    + '<div id="branching-statinfo" class="branching-statinfo"></div>'
    + '</div>'
    + '<div id="branching-outline" class="branching-outline"></div>'
    + '<div id="branching-warns" class="glossary-warns"></div>'
    + '<div id="branching-editor"></div>';
  renderBranchGraphEvents();
  renderBranchOutline();
  renderBranchWarnings();
  renderBranchEditor();
  updateBranchStats(branchLastStats || {});
}

let branchLastStats = null;
function renderBranchOutline() {
  const box = document.getElementById('branching-outline');
  if (!box) return;
  if (!branchOutline || !branchOutline.present) { box.innerHTML = ''; return; }
  const gaps = branchOutline.gaps || [];
  const vols = branchOutline.volumes || [];
  let html = '<div class="bo-card card"><div class="card-title">📑 大纲联动</div>';
  if (vols.length) html += '<div class="bo-vol">检测到卷：' + vols.map(v => `<span class="bo-vol-tag">${branchXmlEsc(v)}</span>`).join('') + '（按卷着色生效）</div>';
  if (!gaps.length) {
    html += '<div class="glossary-warn warn-ok"><span class="warn-tag">ok</span>大纲关键抉择点均已被剧情树覆盖。</div>';
  } else {
    html += '<div class="glossary-warn warn-warn" style="margin-top:8px"><span class="warn-tag">gap</span>大纲有 ' + gaps.length + ' 处抉择点未在剧情树覆盖：</div>';
    gaps.forEach(g => { html += '<div class="bo-gap">· ' + branchXmlEsc(g.point) + '</div>'; });
  }
  html += '</div>';
  box.innerHTML = html;
}

// ===================== 缩放 / 平移 / 折叠 =====================
function branchZoomBy(f) {
  branchZoom = Math.min(3, Math.max(0.3, branchZoom * f));
  refreshBranchTransform();
}
function branchResetView() { branchZoom = 1; branchPanX = 0; branchPanY = 0; branchCollapsed.clear(); renderBranching(); }
function toggleBranchColorBy() {
  branchColorByVolume = !branchColorByVolume;
  const b = document.getElementById('btn-colorby');
  if (b) b.classList.toggle('active', branchColorByVolume);
  renderBranching();
}
function toggleBranchCollapse(id) {
  if (branchCollapsed.has(id)) branchCollapsed.delete(id); else branchCollapsed.add(id);
  renderBranching();
}
function refreshBranchTransform() {
  const g = document.getElementById('branch-transform');
  if (g) g.setAttribute('transform', `translate(${branchPanX},${branchPanY}) scale(${branchZoom})`);
}
function renderBranchGraphEvents() {
  const svg = document.getElementById('branching-svg-el');
  if (!svg) return;
  svg.onwheel = (ev) => {
    ev.preventDefault();
    const rect = svg.getBoundingClientRect();
    const mx = (ev.clientX - rect.left) / branchZoom - branchPanX;
    const my = (ev.clientY - rect.top) / branchZoom - branchPanY;
    const f = ev.deltaY < 0 ? 1.12 : 1 / 1.12;
    branchZoom = Math.min(3, Math.max(0.3, branchZoom * f));
    branchPanX = (ev.clientX - rect.left) / branchZoom - mx;
    branchPanY = (ev.clientY - rect.top) / branchZoom - my;
    refreshBranchTransform();
  };
  let dragging = false, lx = 0, ly = 0;
  svg.onmousedown = (ev) => { dragging = true; lx = ev.clientX; ly = ev.clientY; };
  window.addEventListener('mousemove', (ev) => {
    if (!dragging) return;
    branchPanX += (ev.clientX - lx); branchPanY += (ev.clientY - ly);
    lx = ev.clientX; ly = ev.clientY; refreshBranchTransform();
  });
  window.addEventListener('mouseup', () => { dragging = false; });
}

function branchTypeLabel(t) {
  return t === 'start' ? '起点' : t === 'ending' ? '结局' : '场景';
}
function branchXmlEsc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const VOL_COLORS = ['#c0392b','#2e7d4f','#2e5a7d','#8a6d1e','#7d2e6e','#2e7d7a','#9c5a1e','#4a5a2e'];
function volumeColor(v) {
  const i = Math.max(0, (branchOutline.volumes || []).indexOf(v));
  return VOL_COLORS[i % VOL_COLORS.length];
}

/** SVG 分层剧情树（支持折叠子树 / 缩放平移 / 按卷着色） */
function renderBranchGraph() {
  const PADL = 60, PADR = 30, PADT = 28, PADB = 28, colW = 210, rowGap = 80, nodeW = 152, nodeH = 56;
  const byId = {};
  branchNodes.forEach(n => byId[n.id] = n);
  const starts = branchNodes.filter(n => n.type === 'start').map(n => n.id);
  if (!starts.length && branchNodes.length) starts.push(branchNodes[0].id);

  // 可见节点 = 从起点 BFS，跳过被折叠节点的子树
  const childrenOf = {};
  branchNodes.forEach(n => childrenOf[n.id] = []);
  branchEdges.forEach(e => { if (childrenOf[e.from]) childrenOf[e.from].push(e.to); });
  const visible = new Set(starts);
  const q = [...starts];
  while (q.length) {
    const cur = q.shift();
    if (branchCollapsed.has(cur)) continue;
    (childrenOf[cur] || []).forEach(c => { if (!visible.has(c)) { visible.add(c); q.push(c); } });
  }
  const visNodes = branchNodes.filter(n => visible.has(n.id));
  const visEdges = branchEdges.filter(e => visible.has(e.from) && visible.has(e.to));

  // BFS 分层（仅可见）
  const depth = {};
  starts.forEach(s => depth[s] = 0);
  const q2 = [...starts];
  while (q2.length) {
    const cur = q2.shift();
    const d = depth[cur];
    visEdges.filter(e => e.from === cur).forEach(e => {
      if (depth[e.to] === undefined) { depth[e.to] = d + 1; q2.push(e.to); }
    });
  }
  let maxD = 0; Object.values(depth).forEach(d => maxD = Math.max(maxD, d));
  const hasUnreach = visNodes.some(n => depth[n.id] === undefined);
  const lastLayer = maxD + (hasUnreach ? 1 : 0);
  const layers = {};
  visNodes.forEach(n => {
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

  let svg = `<svg id="branching-svg-el" viewBox="0 0 ${W} ${H}" class="branching-svg" preserveAspectRatio="xMidYMin meet">`;
  svg += '<defs><marker id="bg-arrow" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">'
       + '<path d="M0,0 L7,3 L0,6 Z" fill="#8b7355"/></marker></defs>';
  svg += `<g id="branch-transform" transform="translate(${branchPanX},${branchPanY}) scale(${branchZoom})">`;

  // 边
  visEdges.forEach(e => {
    const a = pos[e.from], b = pos[e.to];
    if (!a || !b) return;
    const mx = (a.x + b.x) / 2;
    let cls = 'bg-edge';
    let labelCls = 'bg-edge-label';
    if (branchStateDebugOn && branchState && branchState.edges) {
      const st = branchState.edges[e.from + '|' + e.to];
      if (st && st.status === 'unsatisfiable') { cls += ' bg-edge-unsat'; labelCls += ' bg-cond-label'; }
      else if (st && st.status === 'conditional') { cls += ' bg-edge-cond2'; labelCls += ' bg-cond-label'; }
      else if (st && st.status === 'guaranteed') { cls += ' bg-edge-guaranteed'; }
      else if (st && st.status === 'open') { cls += ' bg-edge-open'; }
    } else if (e.requires && Object.keys(e.requires).length) {
      cls += ' bg-edge-cond'; labelCls += ' bg-cond-label';
    }
    svg += `<path d="M${a.x},${a.y} C${mx},${a.y} ${mx},${b.y} ${b.x},${b.y}" class="${cls}" marker-end="url(#bg-arrow)"/>`;
    const my = (a.y + b.y) / 2;
    const label = branchXmlEsc(e.choice || '继续');
    svg += `<text x="${mx}" y="${my - 4}" text-anchor="middle" class="${labelCls}">${label}</text>`;
  });

  // 节点
  visNodes.forEach(n => {
    const p = pos[n.id];
    if (!p) return;
    const x = p.x - nodeW / 2, y = p.y - nodeH / 2;
    const sel = branchSel === n.id ? ' bg-sel' : '';
    const reach = depth[n.id] === undefined ? ' bg-unreach' : '';
    const hasKids = (childrenOf[n.id] || []).length > 0;
    const coll = branchCollapsed.has(n.id);
    const collapseBadge = hasKids ? `<text x="${x + nodeW - 4}" y="${y + 12}" text-anchor="end" class="bg-collapse" onclick="toggleBranchCollapse('${n.id}')">${coll ? '⊕' : '⊖'}</text>` : '';
    let rectStyle = '';
    if (branchColorByVolume && branchOutline.volumeMap && branchOutline.volumeMap[n.id]) {
      const c = volumeColor(branchOutline.volumeMap[n.id]);
      rectStyle = ` style="fill:${c}33;stroke:${c}"`;
    }
    svg += `<g class="bg-node bg-${n.type}${sel}${reach}" onclick="selectBranchNode('${n.id}')" style="cursor:pointer">`
        + `<rect x="${x}" y="${y}" width="${nodeW}" height="${nodeH}" rx="9" class="bg-rect"${rectStyle}/>`
        + `<text x="${p.x}" y="${p.y - 5}" text-anchor="middle" class="bg-title">${branchXmlEsc((n.title || n.id).slice(0, 12))}</text>`
        + `<text x="${p.x}" y="${p.y + 13}" text-anchor="middle" class="bg-type">${branchTypeLabel(n.type)}</text>`
        + collapseBadge
        + `</g>`;
  });

  svg += '</g></svg>';
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
      html += `<label class="bf-field"><span>卷（可选，用于按卷着色）</span><input id="bf-vol" class="input-field" value="${escapeHtml(n.volume || '')}" placeholder="如：第一卷" oninput="updateBranchField('${n.id}','volume',this.value)"></label>`;
      if (n.type === 'start') {
        const stJson = (n.state && typeof n.state === 'object') ? JSON.stringify(n.state) : '';
        html += `<label class="bf-field bf-col"><span>初始状态（JSON，如 {"flags":[],"attrs":{"gold":0}}）</span><textarea id="bf-state" class="input-field bf-cond-ta" placeholder='{"flags":[],"attrs":{}}' oninput="updateBranchField('${n.id}','state',this.value)">${escapeHtml(stJson)}</textarea></label>`;
      }
      html += `<label class="bf-field bf-col"><span>摘要</span><textarea id="bf-ex" class="input-field" oninput="updateBranchField('${n.id}','excerpt',this.value)">${escapeHtml(n.excerpt || '')}</textarea></label>`;
      const bodyPreview = (n.body && n.body.trim()) ? n.body : '';
      html += `<div class="bf-body-preview"><div class="bf-sub">正文预览（来自第 ${n.chapterRef || 0} 章，只读）</div>`
        + `<div class="bf-body-box">${bodyPreview ? escapeHtml(bodyPreview) : '<span class="empty">（该节点未关联章节或章节无正文）</span>'}</div></div>`;
      const outs = branchEdges.filter(e => e.from === n.id);
      html += '<div class="bf-edges"><div class="bf-sub">选择支（出边） ' + outs.length + '</div>';
      outs.forEach(e => {
        const tgt = branchNodes.find(x => x.id === e.to);
        const condBadge = (e.requires && Object.keys(e.requires).length) ? ' <span class="bf-cond-on">⚙有门槛</span>' : '';
        html += `<div class="bf-edge-row"><span class="bf-edge-choice">${escapeHtml(e.choice || '继续')}</span> → <span>${escapeHtml(tgt ? tgt.title : e.to)}</span>${condBadge}`
          + `<button class="btn-ghost btn-xs" onclick="deleteBranchEdge('${e.from}','${e.to}')">✕</button>`
          + `<button class="btn-ghost btn-xs" onclick="toggleEdgeCond('${e.from}','${e.to}')">⚙ 条件</button></div>`;
        const key = e.from + '|' + e.to;
        if (branchEdgeCond.has(key)) {
          const reqJson = (e.requires && typeof e.requires === 'object') ? JSON.stringify(e.requires) : '';
          const setJson = (e.sets && typeof e.sets === 'object') ? JSON.stringify(e.sets) : '';
          html += `<div class="bf-cond">`
            + `<div class="bf-sub">门槛 requires（满足才出现此选项）</div>`
            + `<textarea class="input-field bf-cond-ta" placeholder='{"flags":["信任"],"attrs":{"gold":">=10"}}' oninput="updateEdgeField('${e.from}','${e.to}','requires',this.value)">${escapeHtml(reqJson)}</textarea>`
            + `<div class="bf-sub" style="margin-top:6px">设置 sets（选择后生效）</div>`
            + `<textarea class="input-field bf-cond-ta" placeholder='{"flags":["armed"],"attrs":{"gold":"+5"}}' oninput="updateEdgeField('${e.from}','${e.to}','sets',this.value)">${escapeHtml(setJson)}</textarea>`
            + `<div class="bf-cond-hint">门槛：flags 需全部持有；attrs 支持 &gt;= / &gt; / &lt;= / &lt; / == / != 数值比较。设置：attrs 用 +5 / -3 表示增减，否则赋值。</div>`
            + `</div>`;
        }
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
  else if (field === 'state') {
    try { n.state = JSON.parse(value); } catch (e) { n.state = value; }
    return; // 不重渲染，保留 JSON 编辑焦点
  } else if (field === 'volume') {
    n.volume = value;
    return; // 不重渲染，保留输入焦点
  } else n[field] = value;
  renderBranching();
}

function toggleEdgeCond(from, to) {
  const k = from + '|' + to;
  if (branchEdgeCond.has(k)) branchEdgeCond.delete(k); else branchEdgeCond.add(k);
  renderBranchEditor();
}
function updateEdgeField(from, to, field, value) {
  const e = branchEdges.find(x => x.from === from && x.to === to);
  if (!e) return;
  if (field === 'requires' || field === 'sets') {
    if (!value || !value.trim()) e[field] = null;
    else { try { e[field] = JSON.parse(value); } catch (err) { e[field] = value; } }
  }
  // 不重渲染，避免编辑 JSON 时丢失焦点
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
    updateBranchStats(branchLastStats || {});
    renderBranching();
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
    nodes: branchNodes.map(n => ({ id: n.id, title: n.title, type: n.type, excerpt: n.excerpt, chapterRef: n.chapterRef, body: n.body || '', volume: n.volume || '', state: (n.state && typeof n.state === 'object') ? n.state : null })),
    edges: branchEdges.map(e => ({ from: e.from, to: e.to, choice: e.choice, requires: (e.requires && typeof e.requires === 'object') ? e.requires : null, sets: (e.sets && typeof e.sets === 'object') ? e.sets : null }))
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
  button:hover:not(:disabled){background:#3a2a1e;border-color:#7a5e3e}
  button:disabled{opacity:.45;cursor:not-allowed;border-style:dashed}
  .bar{margin-top:20px;display:flex;justify-content:space-between;font-size:13px;color:#8b7355}
  .empty{color:#8b7355;font-style:italic}
  .statebar{margin-top:14px;font-size:13px;color:#e6c45a;background:#1e1a12;border:1px dashed #5a4632;border-radius:8px;padding:8px 12px}
</style>
</head>
<body>
<div class="wrap"><div id="app">加载中…</div></div>
<script>
const DATA = ${json};
const STORY_TITLE = ${JSON.stringify(title).replace(/</g, '\\u003c')};
const byId = {}; DATA.nodes.forEach(n => byId[n.id] = n);
const starts = DATA.nodes.filter(n => n.type === 'start').map(n => n.id);
const FIRST = (starts[0] || (DATA.nodes[0] && DATA.nodes[0].id) || null);
let current = FIRST;
let history = [];           // 每步保存 {id, state} 快照，支持返回还原
let state = initState();

function initState(){
  const s = { flags: new Set(), attrs: {} };
  const sn = byId[FIRST];
  if (sn && sn.state){ if (sn.state.attrs) Object.assign(s.attrs, sn.state.attrs); if (Array.isArray(sn.state.flags)) sn.state.flags.forEach(f=>s.flags.add(f)); }
  return s;
}
function evalRequires(req){
  if (!req) return { ok:true, reason:'' };
  if (req.flags) for (const f of req.flags) if (!state.flags.has(f)) return { ok:false, reason:'需 Flag: '+f };
  if (req.attrs) for (const k in req.attrs){ const cmp=String(req.attrs[k]); const v=Number(state.attrs[k]||0); if (!cmpAttr(v, cmp)) return { ok:false, reason:k+' '+cmp }; }
  return { ok:true, reason:'' };
}
function cmpAttr(v, cmp){
  const m = /^([<>=!]+)\s*(-?\d+(?:\.\d+)?)$/.exec(cmp);
  if (!m) return String(v) === cmp;
  const op=m[1], num=parseFloat(m[2]);
  if (op==='>=') return v>=num; if (op==='>') return v>num; if (op==='<=') return v<=num; if (op==='<') return v<num; if (op==='==') return v===num; if (op==='!=') return v!==num; return false;
}
function applySets(sets){
  if (!sets) return;
  if (sets.flags) sets.flags.forEach(f=>state.flags.add(f));
  if (sets.attrs) for (const k in sets.attrs){ const raw=String(sets.attrs[k]); const m=/^([+-])\s*(\d+(?:\.\d+)?)$/.exec(raw); if (m){ state.attrs[k]=(Number(state.attrs[k]||0))+(m[1]==='+'?1:-1)*parseFloat(m[2]); } else { const num=parseFloat(raw); state.attrs[k]=isNaN(num)?raw:num; } }
}
function render() {
  const app = document.getElementById('app');
  const n = byId[current];
  if (!n) { app.innerHTML = '<h1>'+esc(STORY_TITLE)+'</h1><div class="scene empty">未找到节点。</div>'; return; }
  let tagTxt = n.type === 'start' ? '起点' : n.type === 'ending' ? '结局' : '场景';
  let html = '<h1>'+esc(STORY_TITLE)+'</h1>';
  html += '<div class="scene"><span class="tag ' + n.type + '">' + tagTxt + (n.chapterRef ? ' · 第' + n.chapterRef + '章' : '') + (n.volume ? ' · ' + esc(n.volume) : '') + '</span>';
  const bodyText = (n.body && n.body.trim()) ? n.body : (n.excerpt || '');
  html += '<div class="node-body">' + (bodyText ? esc(bodyText).replace(/\n/g, '<br>') : '<span class="empty">（无正文/摘要）</span>') + '</div>';
  const flagArr=[...state.flags], attrArr=Object.keys(state.attrs);
  if (flagArr.length || attrArr.length) html += '<div class="statebar">状态：' + attrArr.map(k=>esc(k)+'='+esc(state.attrs[k])).join('，') + (flagArr.length ? ('；'+flagArr.map(esc).join('，')) : '') + '</div>';
  const outs = DATA.edges.filter(e => e.from === n.id);
  if (n.type === 'ending') {
    html += '<div class="choices"><button onclick="restart()">↺ 重新开始</button></div>';
  } else if (outs.length) {
    html += '<div class="choices">';
    outs.forEach((e, i) => {
      const r = evalRequires(e.requires);
      const dis = !r.ok;
      html += '<button' + (dis ? ' disabled' : '') + ' onclick="go(\'' + e.to + '\')">' + (i + 1) + '. ' + esc(e.choice || '继续') + (dis ? '（未满足：' + esc(r.reason) + '）' : '') + '</button>';
    });
    html += '</div>';
  } else {
    html += '<div class="scene empty" style="margin-top:12px">（此节点尚无选择支，故事在此中断。）</div>';
  }
  html += '<div class="bar"><span>节点：' + esc(n.title) + '</span><span><button style="padding:4px 10px" onclick="back()">← 返回</button></span></div>';
  html += '</div>';
  app.innerHTML = html;
}
function go(id){
  const e = DATA.edges.find(x => x.from === current && x.to === id);
  if (!e) return;
  if (!evalRequires(e.requires).ok) return;
  history.push({ id: current, state: { flags: new Set(state.flags), attrs: Object.assign({}, state.attrs) } });
  applySets(e.sets);
  current = id; render();
}
function back(){ if(history.length){ const h = history.pop(); current = h.id; state = { flags: new Set(h.state.flags), attrs: Object.assign({}, h.state.attrs) }; render(); } }
function restart(){
  history = []; current = FIRST; state = initState();
  render();
}
function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
render();
<\/script>
</body>
</html>`;
}

// ===================== 分支增强①：状态机可视化调试 =====================
async function loadBranchState() {
  if (!branchBook) { showToast('请先选择书目并读取剧情树', 'warning'); return; }
  try {
    const r = await fetch(authUrl(API + '/api/branching/state?path=' + encodeURIComponent(branchBook)), { headers: authHeaders() });
    const j = await r.json();
    if (j && j.ok === false && j.error) { showToast('状态机调试失败：' + j.error, 'error'); return; }
    branchState = j;
    renderBranchStateDebug();
  } catch (e) { showToast('状态机调试请求失败：' + e.message, 'error'); }
}

async function toggleBranchStateDebug() {
  branchStateDebugOn = !branchStateDebugOn;
  const box = document.getElementById('branch-state-debug');
  const btn = document.querySelector('[onclick="toggleBranchStateDebug()"]');
  if (branchStateDebugOn) {
    box.classList.remove('hidden');
    if (btn) btn.classList.add('active');
    if (!branchState || branchState.empty) await loadBranchState(); else renderBranchStateDebug();
    renderBranching();
  } else {
    box.classList.add('hidden');
    if (btn) btn.classList.remove('active');
    renderBranching();
  }
}

function renderBranchStateDebug() {
  const box = document.getElementById('branch-state-body');
  if (!box) return;
  if (!branchState || branchState.empty) { box.innerHTML = '<div class="empty-hint">尚无分支结构，先「读取剧情树」或「从章节生成骨架」。</div>'; return; }
  let html = '';
  const unsat = branchState.unsatisfiableCount || 0;
  html += '<div class="glossary-warn ' + (unsat ? 'warn-error' : 'warn-ok') + '"><span class="warn-tag">' + (unsat ? 'state' : 'ok') + '</span>'
    + (unsat ? ('检测到 ' + unsat + ' 条「永不满足」的门槛边（requires 在任何路径都无法达成），读者将永远看不到该选项：') : '所有门槛边（requires）在状态机中均可达成。')
    + '</div>';
  (branchState.unsatisfiableEdges || []).forEach(b => {
    html += '<div class="bo-gap">· 「' + branchXmlEsc(b.fromTitle) + '」→「' + branchXmlEsc(b.toTitle) + '」：' + branchXmlEsc(b.reason) + '</div>';
  });
  let rows = '';
  branchNodes.forEach(n => {
    const st = branchState.nodes ? branchState.nodes[n.id] : null;
    const flags = st && st.flags ? st.flags : [];
    const attrs = st && st.attrs ? st.attrs : [];
    const stateTxt = (flags.length || attrs.length) ? ('Flags: ' + flags.join(', ') + (attrs.length ? ('；Attrs: ' + attrs.join(', ')) : '')) : '∅';
    rows += '<tr><td>' + escapeHtml(n.title || n.id) + '</td><td class="branch-state-cell">' + escapeHtml(stateTxt) + '</td></tr>';
  });
  if (rows) html += '<div class="bf-sub" style="margin-top:8px">各节点可达状态（任意路径可获得的 Flag / Attr）</div>'
    + '<table class="branch-state-table"><thead><tr><th>节点</th><th>可达状态</th></tr></thead><tbody>' + rows + '</tbody></table>';
  html += '<div class="bf-hint" style="margin-top:8px">图例中：<span style="color:#c0392b">红</span>=永不满足门槛，<span style="color:#d98f1e">橙</span>=条件满足，<span style="color:#2e9d5b">绿</span>=必满足，灰=无门槛。</div>';
  box.innerHTML = html;
}

// ===================== 分支增强②：大纲抉择点反向建议 =====================
async function loadBranchSuggest(useLlm) {
  const box = document.getElementById('branch-suggest');
  if (box) box.classList.remove('hidden');
  const body = document.getElementById('branch-suggest-body');
  if (!body) return;
  if (!branchBook) { showToast('请先选择书目', 'warning'); return; }
  body.innerHTML = '<div class="empty-hint">正在生成建议…</div>';
  try {
    const r = await fetch(authUrl(API + '/api/branching/suggest'), {
      method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: branchBook, useLlm: !!useLlm })
    });
    const j = await r.json();
    if (!j.ok) { body.innerHTML = '<div class="empty-hint">建议生成失败：' + escapeHtml(j.error || '') + '</div>'; return; }
    renderBranchSuggest(j);
  } catch (e) { body.innerHTML = '<div class="empty-hint">请求失败：' + escapeHtml(e.message) + '</div>'; }
}

function renderBranchSuggest(j) {
  const body = document.getElementById('branch-suggest-body');
  if (!body) return;
  branchSuggestions = j.edgeSuggestions || [];
  let html = '<div class="branch-suggest-actions">'
    + '<button class="btn-ghost btn-sm" onclick="loadBranchSuggest(false)">↻ 规则式重算</button>'
    + '<button class="btn-ink btn-sm" onclick="loadBranchSuggest(true)">✨ LLM 增强建议</button>'
    + '<span class="graph-stats">' + (j.llm ? 'LLM 模式' : '规则式') + ' · 大纲抉择点 ' + (j.decisionCount || 0) + '</span></div>';
  const schema = j.schema || {};
  const flags = schema.flags || [];
  const attrs = schema.attrs || [];
  if (flags.length || attrs.length) {
    html += '<div class="bf-sub">建议追踪的状态变量</div><div class="branch-chips">';
    flags.forEach(f => html += '<span class="branch-chip">🚩 ' + branchXmlEsc(f) + '</span>');
    attrs.forEach(a => html += '<span class="branch-chip branch-chip-attr">📊 ' + branchXmlEsc(a) + '</span>');
    html += '</div>';
  }
  const dec = j.decisions || [];
  if (dec.length) {
    html += '<div class="bf-sub">抉择点 → 状态变量映射</div>';
    dec.forEach(d => {
      html += '<div class="bo-gap">· 抉择「' + branchXmlEsc(d.point) + '」→ 建议 Flag「<b>' + branchXmlEsc(d.flag) + '</b>」<br><span class="branch-note">' + branchXmlEsc(d.note || '') + '</span></div>';
    });
  }
  const es = j.edgeSuggestions || [];
  if (es.length) {
    html += '<div class="bf-sub">建议的边（requires / sets）</div>';
    es.forEach((s, i) => {
      const sets = s.sets || {};
      const setsTxt = (sets.flags ? 'flags:[' + (sets.flags || []).join(',') + ']' : '') + (sets.attrs ? ' attrs:' + JSON.stringify(sets.attrs) : '');
      html += '<div class="branch-edge-sug">'
        + '<div class="branch-edge-sug-head">「' + branchXmlEsc(s.from) + '」→「' + branchXmlEsc(s.to) + '」'
        + '<button class="btn-ghost btn-xs" onclick="applyEdgeSuggestion(' + i + ')">应用 sets</button></div>'
        + '<div class="branch-note">' + branchXmlEsc(s.note || '') + '</div>'
        + '<code class="branch-code">sets: ' + branchXmlEsc(setsTxt) + '</code></div>';
    });
  } else {
    html += '<div class="empty-hint" style="margin-top:8px">当前剧情树中未找到与大纲抉择点匹配的出边；可先在树上为抉择点补充分支，再点「规则式重算」。</div>';
  }
  html += '<div class="branch-note" style="margin-top:8px">' + branchXmlEsc(j.note || '') + '</div>';
  body.innerHTML = html;
}

function applyEdgeSuggestion(i) {
  const s = branchSuggestions[i];
  if (!s) return;
  const e = branchEdges.find(x => x.from === s.from && x.to === s.to);
  if (!e) { showToast('未找到该边（id 不匹配），请检查剧情树节点 id', 'warning'); return; }
  if (s.sets && Object.keys(s.sets).length) e.sets = s.sets;
  if (s.requires && Object.keys(s.requires).length) e.requires = s.requires;
  renderBranching();
  showToast('已应用 sets 到「' + s.from + '→' + s.to + '」，记得点「保存结构」', 'success');
}

// ===================== 分支增强③：剧情树版本对比 =====================
function toggleBranchDiff() {
  const box = document.getElementById('branch-diff');
  const btn = document.querySelector('[onclick="toggleBranchDiff()"]');
  const on = box.classList.contains('hidden');
  if (on) { box.classList.remove('hidden'); if (btn) btn.classList.add('active'); renderBranchDiffUI(); }
  else { box.classList.add('hidden'); if (btn) btn.classList.remove('active'); }
}

function renderBranchDiffUI() {
  const body = document.getElementById('branch-diff-body');
  if (!body) return;
  let snaps = [];
  try { snaps = JSON.parse(localStorage.getItem('nf_branch_snaps') || '[]'); } catch (e) {}
  const snapOpts = snaps.map((s, i) => '<option value="' + i + '">' + branchXmlEsc(s.label) + ' (' + (s.nodes || 0) + '节点)</option>').join('');
  body.innerHTML = '<div class="form-grid">'
    + '<div class="form-group" style="grid-column:1/-1"><div class="bf-sub">版本 A（基准）</div>'
    + '<textarea id="diff-a" class="input-field" rows="6" placeholder="粘贴剧情树 JSON（含 nodes/edges），或点下方「载入当前树」"></textarea></div>'
    + '<div class="form-group" style="grid-column:1/-1"><div class="bf-sub">版本 B（对比）</div>'
    + '<textarea id="diff-b" class="input-field" rows="6" placeholder="粘贴另一版剧情树 JSON，或从下方快照选择"></textarea></div>'
    + '</div>'
    + '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:6px">'
    + '<button class="btn-ghost btn-sm" onclick="loadCurrentToDiffA()">⬇ 载入当前树到 A</button>'
    + '<button class="btn-ghost btn-sm" onclick="loadCurrentToDiffB()">⬇ 载入当前树到 B</button>'
    + '<button class="btn-ghost btn-sm" onclick="saveBranchSnapshot()">💾 保存当前树为快照</button>'
    + '<select id="diff-snap" class="input-field" onchange="loadSnapToDiffB(this.value)"><option value="">— 选择快照载入 B —</option>' + snapOpts + '</select>'
    + '<button class="btn-ink btn-sm" onclick="runBranchDiff()">🔍 对比</button>'
    + '</div>'
    + '<div id="branch-diff-result" style="margin-top:10px"></div>';
}

function currentTreeJson() { return JSON.stringify({ nodes: branchNodes, edges: branchEdges }); }
function loadCurrentToDiffA() { const t = document.getElementById('diff-a'); if (t) t.value = currentTreeJson(); showToast('已载入当前树到 A', 'success', 1000); }
function loadCurrentToDiffB() { const t = document.getElementById('diff-b'); if (t) t.value = currentTreeJson(); showToast('已载入当前树到 B', 'success', 1000); }

function saveBranchSnapshot() {
  let snaps = [];
  try { snaps = JSON.parse(localStorage.getItem('nf_branch_snaps') || '[]'); } catch (e) {}
  const label = prompt('快照名称（如 v1 / 修改前）：', '快照' + (snaps.length + 1));
  if (!label) return;
  snaps.push({ label: label, ts: Date.now(), nodes: branchNodes.length, edges: branchEdges.length, tree: { nodes: branchNodes, edges: branchEdges } });
  localStorage.setItem('nf_branch_snaps', JSON.stringify(snaps));
  showToast('已保存快照「' + label + '」', 'success');
  renderBranchDiffUI();
}

function loadSnapToDiffB(idx) {
  if (idx === '' || idx == null) return;
  let snaps = [];
  try { snaps = JSON.parse(localStorage.getItem('nf_branch_snaps') || '[]'); } catch (e) {}
  const s = snaps[Number(idx)];
  const t = document.getElementById('diff-b');
  if (s && t) t.value = JSON.stringify(s.tree);
}

async function runBranchDiff() {
  const ta = document.getElementById('diff-a'), tb = document.getElementById('diff-b');
  const box = document.getElementById('branch-diff-result');
  if (!ta || !tb) return;
  let a, b;
  try { a = JSON.parse(ta.value); } catch (e) { box.innerHTML = '<div class="empty-hint">版本 A 不是合法 JSON</div>'; return; }
  try { b = JSON.parse(tb.value); } catch (e) { box.innerHTML = '<div class="empty-hint">版本 B 不是合法 JSON</div>'; return; }
  try {
    const r = await fetch(authUrl(API + '/api/branching/diff'), {
      method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ treeA: a, treeB: b })
    });
    const j = await r.json();
    if (!j.ok) { box.innerHTML = '<div class="empty-hint">对比失败：' + escapeHtml(j.error || '') + '</div>'; return; }
    renderBranchDiffResult(j);
  } catch (e) { box.innerHTML = '<div class="empty-hint">请求失败：' + escapeHtml(e.message) + '</div>'; }
}

function renderBranchDiffResult(j) {
  const box = document.getElementById('branch-diff-result');
  if (!box) return;
  const s = j.summary || {};
  let html = '<div class="graph-stats">节点 +' + (s.nodesAdded || 0) + ' / -' + (s.nodesRemoved || 0) + ' / ~' + (s.nodesChanged || 0)
    + ' ｜ 连线 +' + (s.edgesAdded || 0) + ' / -' + (s.edgesRemoved || 0) + ' / ~' + (s.edgesChanged || 0) + '</div>';
  const nd = j.nodes || {}, ed = j.edges || {};
  const added = nd.added || [];
  if (added.length) { html += '<div class="bf-sub">新增节点</div>'; added.forEach(t => html += '<div class="bo-gap">＋ ' + branchXmlEsc(t) + '</div>'); }
  if ((nd.removed || []).length) { html += '<div class="bf-sub">删除节点</div>'; nd.removed.forEach(t => html += '<div class="bo-gap">－ ' + branchXmlEsc(t) + '</div>'); }
  if ((nd.changed || []).length) { html += '<div class="bf-sub">变更节点</div>'; nd.changed.forEach(c => { html += '<div class="bo-gap">~ ' + branchXmlEsc(c.title) + '：' + (c.fields || []).map(f => branchXmlEsc(f.field) + '(' + branchXmlEsc(f.from) + '→' + branchXmlEsc(f.to) + ')').join('，') + '</div>'; }); }
  if ((ed.added || []).length) { html += '<div class="bf-sub">新增连线</div>'; ed.added.forEach(e => html += '<div class="bo-gap">＋ ' + branchXmlEsc(e.from) + '→' + branchXmlEsc(e.to) + '「' + branchXmlEsc(e.choice) + '」</div>'); }
  if ((ed.removed || []).length) { html += '<div class="bf-sub">删除连线</div>'; ed.removed.forEach(e => html += '<div class="bo-gap">－ ' + branchXmlEsc(e.from) + '→' + branchXmlEsc(e.to) + '</div>'); }
  if ((ed.changed || []).length) { html += '<div class="bf-sub">变更连线</div>'; ed.changed.forEach(e => { html += '<div class="bo-gap">~ ' + branchXmlEsc(e.from) + '→' + branchXmlEsc(e.to) + '：' + (e.fields || []).map(f => branchXmlEsc(f.field)).join('，') + '</div>'; }); }
  if (!added.length && !(nd.removed||[]).length && !(nd.changed||[]).length && !(ed.added||[]).length && !(ed.removed||[]).length && !(ed.changed||[]).length)
    html += '<div class="empty-hint">两版剧情树完全一致。</div>';
  box.innerHTML = html;
}
