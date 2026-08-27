// 墨阁 · NovelForge Studio — 关系图谱模块 (P2)
// 依赖 app.js 提供的全局：API / authUrl / authHeaders / escapeHtml / showToast
// 力导向 SVG 渲染，零外部依赖。数据源：/api/graph（时间线事件 + 章节正文共现挖掘）

const GRAPH_COLORS = {
  character: '#d95f3b', location: '#2f6fd0', faction: '#7a4fd0',
  item: '#2f9e6e', system: '#2f9e6e', rule: '#2f9e6e', default: '#8a8f98'
};
const GRAPH_LABELS = {
  character: '人物', location: '地点', faction: '势力', item: '物品',
  system: '体系', rule: '规则', default: '其他'
};
// 势力色板（按出现顺序分配，同势力角色同色系 → 一眼识别阵营）
const GRAPH_FACTION_PALETTE = ['#e06060', '#5c9ee0', '#e0a65c', '#6fbf6f', '#b078e0', '#e078b0', '#4fc4c4', '#a0a85c'];

let graphData = null;            // 后端原始数据 {nodes, edges, chapters, stats}
let graphHighlight = null;       // 当前高亮节点 id
let graphView = { scale: 1, tx: 0, ty: 0 };  // 视图变换
let graphDrag = null;            // {id, dx, dy} 拖拽中的节点
let graphSimRunning = false;
let graphSimRaf = 0;
let graphSvg = null;
let graphZoomRoot = null;
let graphFactionList = [];       // 有势力归属的角色势力名（出现顺序）
let graphPlay = null;            // 章节演变动画 {idx, timer, playing}
let graphFilters = { types: {}, minMentions: 0, query: '' };

// ========== 数据加载 ==========

async function loadGraph() {
  const sel = document.getElementById('graph-book');
  const bookPath = sel ? sel.value : '';
  const canvas = document.getElementById('graph-canvas');
  const statsEl = document.getElementById('graph-stats');
  if (!bookPath) {
    if (canvas) canvas.innerHTML = '<div class="empty-hint">请先选择书籍</div>';
    if (statsEl) statsEl.textContent = '';
    renderDegreeList([]);
    return;
  }
  if (canvas) canvas.innerHTML = '<div class="empty-hint">正在挖掘共现关系…</div>';
  try {
    const res = await fetch(authUrl(API + '/api/graph?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '加载失败');
    graphData = data;
    graphHighlight = null;
    graphPlay = null;
    buildFactionMap();
    initGraphFilters();
    renderGraphLegend();
    renderGraphChips();
    setupGraphPlayBar();
    if (statsEl) statsEl.textContent =
      (data.stats.characters || 0) + ' 人物 · ' + (data.stats.worldEntities || 0) + ' 场景/势力 · ' + (data.stats.edges || 0) + ' 关系' +
      ((data.chapters && data.chapters.length) ? ' · ' + data.chapters.length + ' 章演进' : '');
    renderGraph();
    renderDegreeList(data.nodes || []);
  } catch (e) {
    graphPlay = null;
    setupGraphPlayBar();
    if (canvas) canvas.innerHTML = '<div class="empty-hint">图谱加载失败：' + escapeHtml(e.message) + '</div>';
    if (statsEl) statsEl.textContent = '';
    renderDegreeList([]);
  }
}

function relayoutGraph() {
  if (!graphData || !graphSvg) return;
  graphView = { scale: 1, tx: 0, ty: 0 };
  graphData.nodes.forEach(n => { n.x = undefined; n.y = undefined; n.pinned = false; });
  startGraphSim(true);
}

// ========== 渲染 ==========

function nodeRadius(n) {
  return 10 + Math.min(14, (n.degree || 0) * 1.6 + (n.mentions > 0 ? Math.log2(n.mentions + 1) : 0));
}

function graphCanvasSize() {
  const canvas = document.getElementById('graph-canvas');
  if (!canvas) return { W: 800, H: 600 };
  const w = canvas.clientWidth || (canvas.parentElement ? canvas.parentElement.clientWidth : 0) || 800;
  const h = canvas.clientHeight || (canvas.parentElement ? canvas.parentElement.clientHeight : 0) || 600;
  return { W: Math.max(320, w), H: Math.max(360, h) };
}

function renderGraph() {
  const canvas = document.getElementById('graph-canvas');
  if (!canvas || !graphData) return;
  const { W, H } = graphCanvasSize();
  canvas.innerHTML = '';

  const svgNS = 'http://www.w3.org/2000/svg';
  graphSvg = document.createElementNS(svgNS, 'svg');
  graphSvg.setAttribute('width', '100%');
  graphSvg.setAttribute('height', '100%');
  graphSvg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
  graphSvg.style.cursor = 'grab';
  graphZoomRoot = document.createElementNS(svgNS, 'g');
  graphSvg.appendChild(graphZoomRoot);

  // 背景（用于空白处拖拽平移）
  const bg = document.createElementNS(svgNS, 'rect');
  bg.setAttribute('x', -1e5); bg.setAttribute('y', -1e5);
  bg.setAttribute('width', 2e5); bg.setAttribute('height', 2e5);
  bg.setAttribute('fill', 'transparent');
  graphZoomRoot.appendChild(bg);

  // 初始位置（随机散布于画布中央区域）
  graphData.nodes.forEach(n => {
    if (n.x == null) { n.x = W / 2 + (Math.random() - 0.5) * W * 0.7; n.y = H / 2 + (Math.random() - 0.5) * H * 0.7; }
    if (n.vx == null) { n.vx = 0; n.vy = 0; }
    if (n.pinned == null) n.pinned = false;
  });

  // 滚轮缩放
  graphSvg.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = graphSvg.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const factor = e.deltaY < 0 ? 1.12 : 0.89;
    const ns = Math.min(3, Math.max(0.35, graphView.scale * factor));
    graphView.tx = mx - (mx - graphView.tx) * (ns / graphView.scale);
    graphView.ty = my - (my - graphView.ty) * (ns / graphView.scale);
    graphView.scale = ns;
    applyGraphView();
  }, { passive: false });

  // 画布拖拽平移 / 节点拖拽
  let panStart = null;
  graphSvg.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    if (e.target === bg || e.target === graphSvg) {
      panStart = { x: e.clientX, y: e.clientY, tx: graphView.tx, ty: graphView.ty };
      graphSvg.style.cursor = 'grabbing';
    } else if (e.target.closest && e.target.closest('.gnode')) {
      startNodeDrag(e.target.closest('.gnode').dataset.id, e);
    }
  });
  graphSvg.addEventListener('pointermove', e => {
    if (graphDrag) { moveNodeDrag(e); return; }
    if (panStart) {
      graphView.tx = panStart.tx + (e.clientX - panStart.x);
      graphView.ty = panStart.ty + (e.clientY - panStart.y);
      applyGraphView();
    }
  });
  const endPointer = () => {
    if (graphDrag) { graphDrag = null; if (graphSvg) graphSvg.style.cursor = 'grab'; }
    if (panStart) { panStart = null; if (graphSvg) graphSvg.style.cursor = 'grab'; }
  };
  graphSvg.addEventListener('pointerup', endPointer);
  graphSvg.addEventListener('pointercancel', endPointer);
  graphSvg.addEventListener('pointerleave', endPointer);
  graphSvg.addEventListener('click', e => {
    if (e.target === bg || e.target === graphSvg) { graphHighlight = null; paintGraph(); }
  });

  // 悬停提示（节点 / 边）
  graphSvg.addEventListener('mousemove', e => {
    const g = e.target.closest && e.target.closest('.gnode');
    if (g && !graphDrag) { showGraphTooltip(g.dataset.id, e); return; }
    const eg = e.target.closest && e.target.closest('.gedge');
    if (eg) {
      const edge = graphData.edges[Number(eg.dataset.idx)];
      if (edge) { showGraphEdgeTooltip(edge, e); return; }
    }
    hideGraphTooltip();
  });
  graphSvg.addEventListener('mouseleave', hideGraphTooltip);

  startGraphSim(false);
}

// ========== 力导向模拟 ==========

function startGraphSim(reset) {
  const { W, H } = graphCanvasSize();
  if (reset) {
    graphData.nodes.forEach(n => { n.vx = 0; n.vy = 0; });
  }
  if (graphSimRunning) cancelAnimationFrame(graphSimRaf);
  graphSimRunning = true;
  const nodeIndex = new Map(graphData.nodes.map((n, i) => [n.id, i]));
  const repulsion = 2400, springK = 0.018, restLen = 120, centerK = 0.012, damping = 0.82;

  const step = () => {
    const ns = graphData.nodes;
    // 斥力（O(n²) 对全节点）
    for (let i = 0; i < ns.length; i++) {
      const a = ns[i];
      if (a.pinned) continue;
      for (let j = i + 1; j < ns.length; j++) {
        const b = ns[j];
        let dx = a.x - b.x, dy = a.y - b.y;
        let d2 = dx * dx + dy * dy;
        if (d2 < 1) { dx = (Math.random() - 0.5); dy = (Math.random() - 0.5); d2 = 1; }
        const d = Math.sqrt(d2);
        const f = repulsion / (d2 + 1);
        const fx = (dx / d) * f, fy = (dy / d) * f;
        a.vx += fx; a.vy += fy; b.vx -= fx; b.vy -= fy;
      }
    }
    // 弹簧（边权重越大拉力越强）
    for (const e of graphData.edges) {
      const a = ns[nodeIndex.get(e.source)], b = ns[nodeIndex.get(e.target)];
      if (!a || !b) continue;
      let dx = b.x - a.x, dy = b.y - a.y;
      const d = Math.sqrt(dx * dx + dy * dy) || 1;
      const f = springK * Math.min(6, e.weight || 1) * (d - restLen);
      const fx = (dx / d) * f, fy = (dy / d) * f;
      if (!a.pinned) { a.vx += fx; a.vy += fy; }
      if (!b.pinned) { b.vx -= fx; b.vy -= fy; }
    }
    // 势力聚类引力（同势力向质心聚拢）+ 向心力 + 阻尼
    const factionCenters = graphFactionList.length ? graphFactionList.map(f => {
      let cx = 0, cy = 0, cnt = 0;
      for (const n of ns) { if (n.faction === f) { cx += n.x; cy += n.y; cnt++; } }
      return cnt > 0 ? { f: f, cx: cx / cnt, cy: cy / cnt } : null;
    }).filter(Boolean) : [];
    let energy = 0;
    for (const n of ns) {
      if (n.pinned) continue;
      if (n.faction) {
        for (const fc of factionCenters) {
          if (fc.f === n.faction) { n.vx += (fc.cx - n.x) * 0.006; n.vy += (fc.cy - n.y) * 0.006; break; }
        }
      }
      n.vx += (W / 2 - n.x) * centerK;
      n.vy += (H / 2 - n.y) * centerK;
      n.vx *= damping; n.vy *= damping;
      n.x += n.vx; n.y += n.vy;
      energy += Math.abs(n.vx) + Math.abs(n.vy);
    }
    paintGraph();
    if (energy > 0.5 && !reset) {
      graphSimRaf = requestAnimationFrame(step);
    } else {
      graphSimRunning = false;
      if (reset) startGraphSim(false);
    }
  };
  step();
}

function applyGraphView() {
  if (!graphZoomRoot) return;
  graphZoomRoot.setAttribute('transform',
    'translate(' + graphView.tx + ',' + graphView.ty + ') scale(' + graphView.scale + ')');
}

function startNodeDrag(id, e) {
  const n = graphData.nodes.find(x => x.id === id);
  if (!n) return;
  const rect = graphSvg.getBoundingClientRect();
  const mx = (e.clientX - rect.left - graphView.tx) / graphView.scale;
  const my = (e.clientY - rect.top - graphView.ty) / graphView.scale;
  graphDrag = { id: id, dx: n.x - mx, dy: n.y - my };
  n.pinned = true;
  graphSvg.style.cursor = 'grabbing';
}

function moveNodeDrag(e) {
  const n = graphData.nodes.find(x => x.id === graphDrag.id);
  if (!n) return;
  const rect = graphSvg.getBoundingClientRect();
  const mx = (e.clientX - rect.left - graphView.tx) / graphView.scale;
  const my = (e.clientY - rect.top - graphView.ty) / graphView.scale;
  n.x = mx + graphDrag.dx;
  n.y = my + graphDrag.dy;
  paintGraph();
}

// ========== 绘制 ==========

function paintGraph() {
  if (!graphSvg || !graphZoomRoot) return;
  while (graphZoomRoot.children.length > 1) graphZoomRoot.removeChild(graphZoomRoot.lastChild);
  applyGraphView();
  const svgNS = 'http://www.w3.org/2000/svg';
  const hullLayer = document.createElementNS(svgNS, 'g');
  const edgeLayer = document.createElementNS(svgNS, 'g');
  const edgeLabelLayer = document.createElementNS(svgNS, 'g');
  const nodeLayer = document.createElementNS(svgNS, 'g');
  const nodeIndex = new Map(graphData.nodes.map((n, i) => [n.id, i]));
  const highlight = graphHighlight;
  const activeEdgeSet = graphPlay ? graphActiveEdgeSet() : null;   // null = 全部可见
  const newEdgeSet = graphPlay ? graphNewEdgeSet() : new Set();

  // 势力包围圈（convex hull）：每帧随节点位置重建，成员 >= 2 才绘制
  paintFactionHulls(hullLayer, highlight);

  // 边
  graphData.edges.forEach((e, idx) => {
    const a = graphData.nodes[nodeIndex.get(e.source)];
    const b = graphData.nodes[nodeIndex.get(e.target)];
    if (!a || !b) return;
    if (a._visible === false || b._visible === false) return;   // 被过滤隐藏
    const key = e.source + '\u0000' + e.target;
    const shown = !activeEdgeSet || activeEdgeSet.has(key);     // 演变序列中尚未出现
    const isNew = newEdgeSet.has(key);                          // 当前章首次出现 → 高亮
    const active = (!highlight || highlight === e.source || highlight === e.target) && shown;
    const line = document.createElementNS(svgNS, 'line');
    line.setAttribute('x1', a.x); line.setAttribute('y1', a.y);
    line.setAttribute('x2', b.x); line.setAttribute('y2', b.y);
    const w = 0.8 + Math.min(5, Math.log2((e.weight || 1) + 1) * 1.5);
    if (isNew) {
      line.setAttribute('stroke', '#ff7a1a');
      line.setAttribute('stroke-width', String(Math.max(3, w + 1.5)));
      line.setAttribute('opacity', '1');
    } else if (!shown) {
      line.setAttribute('stroke', '#c9ccd4');
      line.setAttribute('stroke-width', '0.6');
      line.setAttribute('opacity', '0.08');
    } else {
      line.setAttribute('stroke', e.label ? '#b08968' : '#c9ccd4');
      line.setAttribute('stroke-width', String(w));
      line.setAttribute('opacity', active ? '0.75' : '0.12');
    }
    line.setAttribute('class', isNew ? 'gedge gedge-new' : 'gedge');
    line.setAttribute('data-idx', idx);
    edgeLayer.appendChild(line);
    // 关系标签（有推断且共现 >= 2 次才显示，避免噪点）
    if (e.label && active && shown && (e.weight || 0) >= 2) {
      const t = document.createElementNS(svgNS, 'text');
      t.setAttribute('x', (a.x + b.x) / 2);
      t.setAttribute('y', (a.y + b.y) / 2 - 4);
      t.setAttribute('text-anchor', 'middle');
      t.setAttribute('font-size', '10');
      t.setAttribute('fill', '#8a6d4f');
      t.textContent = e.label;
      edgeLabelLayer.appendChild(t);
    }
  });

  // 节点
  graphData.nodes.forEach(n => {
    if (n._visible === false) return;   // 被过滤隐藏
    const active = !highlight || highlight === n.id;
    const g = document.createElementNS(svgNS, 'g');
    g.setAttribute('class', 'gnode');
    g.setAttribute('data-id', n.id);
    g.setAttribute('opacity', active ? 1 : 0.18);
    const color = nodeColor(n);
    const r = nodeRadius(n);
    // 核心人物光环（degree >= 4）
    if (n.degree >= 4) {
      const ring = document.createElementNS(svgNS, 'circle');
      ring.setAttribute('cx', n.x); ring.setAttribute('cy', n.y);
      ring.setAttribute('r', r + 4);
      ring.setAttribute('fill', 'none');
      ring.setAttribute('stroke', color);
      ring.setAttribute('stroke-opacity', '0.35');
      ring.setAttribute('stroke-width', '2');
      g.appendChild(ring);
    }
    const circle = document.createElementNS(svgNS, 'circle');
    circle.setAttribute('cx', n.x); circle.setAttribute('cy', n.y);
    circle.setAttribute('r', r);
    circle.setAttribute('fill', color);
    circle.setAttribute('stroke', '#ffffff');
    circle.setAttribute('stroke-width', '1.6');
    circle.style.cursor = 'pointer';
    g.appendChild(circle);
    const t = document.createElementNS(svgNS, 'text');
    t.setAttribute('x', n.x); t.setAttribute('y', n.y + r + 13);
    t.setAttribute('text-anchor', 'middle');
    t.setAttribute('font-size', '11');
    t.setAttribute('fill', '#2c313a');
    t.setAttribute('pointer-events', 'none');
    t.textContent = n.label;
    g.appendChild(t);
    g.addEventListener('click', ev => {
      ev.stopPropagation();
      graphHighlight = graphHighlight === n.id ? null : n.id;
      paintGraph();
    });
    nodeLayer.appendChild(g);
  });

  graphZoomRoot.appendChild(hullLayer);
  graphZoomRoot.appendChild(edgeLayer);
  graphZoomRoot.appendChild(edgeLabelLayer);
  graphZoomRoot.appendChild(nodeLayer);
}

// ========== 提示 / 侧栏 ==========

function showGraphTooltip(id, e) {
  const n = graphData.nodes.find(x => x.id === id);
  if (!n) return;
  const tip = document.getElementById('graph-tooltip');
  if (!tip) return;
  tip.innerHTML = '<b>' + escapeHtml(n.label) + '</b> <span class="chip">' + (GRAPH_LABELS[n.group] || n.group) + '</span>' +
    (n.role ? '<div class="tip-role">' + escapeHtml(n.role) + '</div>' : '') +
    (n.desc ? '<div class="tip-desc">' + escapeHtml(n.desc) + '</div>' : '') +
    '<div class="tip-meta">提及 ' + (n.mentions || 0) + ' 次 · 关联 ' + (n.degree || 0) + ' 条关系</div>';
  tip.classList.remove('hidden');
  positionGraphTooltip(e, tip);
}

function showGraphEdgeTooltip(edge, e) {
  const tip = document.getElementById('graph-tooltip');
  if (!tip) return;
  tip.innerHTML = '<b>' + escapeHtml(edge.source) + ' ↔ ' + escapeHtml(edge.target) + '</b>' +
    '<div class="tip-meta">共现 ' + edge.weight + ' 次' + (edge.label ? ' · 关系：' + escapeHtml(edge.label) : '') + '</div>';
  tip.classList.remove('hidden');
  positionGraphTooltip(e, tip);
}

function positionGraphTooltip(e, tip) {
  const wrap = document.getElementById('graph-canvas') ? document.getElementById('graph-canvas').parentElement : null;
  if (!wrap) return;
  const r = wrap.getBoundingClientRect();
  tip.style.left = Math.min(e.clientX - r.left + 14, r.width - 260) + 'px';
  tip.style.top = Math.min(e.clientY - r.top + 14, r.height - 140) + 'px';
}

function hideGraphTooltip() {
  const tip = document.getElementById('graph-tooltip');
  if (tip) tip.classList.add('hidden');
}

function renderDegreeList(nodes) {
  const list = document.getElementById('graph-degree-list');
  const count = document.getElementById('graph-degree-count');
  if (!list) return;
  const sorted = (nodes || [])
    .filter(n => n.group === 'character')
    .sort((a, b) => (b.degree || 0) - (a.degree || 0) || (b.mentions || 0) - (a.mentions || 0));
  if (count) count.textContent = String(sorted.length);
  if (!sorted.length) { list.innerHTML = '<div class="empty-hint">暂无数据</div>'; return; }
  list.innerHTML = sorted.slice(0, 10).map(n => {
    const color = nodeColor(n);
    return '<div class="graph-degree-item" data-id="' + escapeHtml(n.id) + '" onclick="focusGraphNode(\'' + escapeHtml(n.id) + '\')">' +
      '<span class="legend-dot" style="background:' + color + '"></span>' +
      '<span class="gdi-name">' + escapeHtml(n.label) +
      (n.faction ? '<span class="gdi-faction">[' + escapeHtml(n.faction) + ']</span>' : '') + '</span>' +
      '<span class="gdi-meta">' + (n.degree || 0) + ' 关联 · ' + (n.mentions || 0) + ' 提及</span>' +
      '</div>';
  }).join('');
}

function focusGraphNode(id) {
  if (!graphData || !graphSvg) return;
  graphHighlight = id;
  const n = graphData.nodes.find(x => x.id === id);
  if (n) {
    const { W, H } = graphCanvasSize();
    graphView.scale = Math.max(0.9, graphView.scale);
    graphView.tx = W / 2 - n.x * graphView.scale;
    graphView.ty = H / 2 - n.y * graphView.scale;
  }
  paintGraph();
  showToast('已聚焦「' + id + '」', 'info');
}

// ========== 势力归属 / 聚类着色 ==========

function nodeColor(n) {
  if (n.group === 'character' && n.faction) return factionColor(n.faction);
  return GRAPH_COLORS[n.group] || GRAPH_COLORS.default;
}

function factionColor(f) {
  const i = graphFactionList.indexOf(f);
  return i >= 0 ? GRAPH_FACTION_PALETTE[i % GRAPH_FACTION_PALETTE.length] : GRAPH_COLORS.default;
}

function buildFactionMap() {
  graphFactionList = [];
  const seen = new Set();
  (graphData && graphData.nodes || []).forEach(n => {
    if (n.faction && !seen.has(n.faction)) { seen.add(n.faction); graphFactionList.push(n.faction); }
  });
}

// ---- 势力包围圈（convex hull + 胶囊）----

/** 单调链凸包（Monotone Chain），返回逆时针顶点序列。 */
function convexHull(pts) {
  if (pts.length < 3) return pts.slice();
  const sorted = pts.slice().sort((a, b) => a.x - b.x || a.y - b.y);
  const cross = (o, a, b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
  const lower = [], upper = [];
  for (const p of sorted) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop();
    lower.push(p);
  }
  for (let i = sorted.length - 1; i >= 0; i--) {
    const p = sorted[i];
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop();
    upper.push(p);
  }
  lower.pop(); upper.pop();
  return lower.concat(upper);
}

/** 顶点向外扩张 pad 后，用二次贝塞尔中点法生成平滑闭合曲线。 */
function hullPath(hull, pad) {
  let cx = 0, cy = 0;
  for (const p of hull) { cx += p.x; cy += p.y; }
  cx /= hull.length; cy /= hull.length;
  const ext = hull.map(p => {
    let dx = p.x - cx, dy = p.y - cy;
    const d = Math.sqrt(dx * dx + dy * dy) || 1;
    return { x: p.x + (dx / d) * pad, y: p.y + (dy / d) * pad };
  });
  let d = '';
  for (let i = 0; i < ext.length; i++) {
    const a = ext[i], b = ext[(i + 1) % ext.length];
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
    if (i === 0) d = 'M ' + mx + ' ' + my + ' ';
    d += 'Q ' + a.x + ' ' + a.y + ' ' + mx + ' ' + my + ' ';
  }
  return d + 'Z';
}

/** 两名成员势力的胶囊路径（两端半圆）。 */
function capsulePath(a, b, pad) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const d = Math.sqrt(dx * dx + dy * dy) || 1;
  const nx = (-dy / d) * pad, ny = (dx / d) * pad;
  return 'M ' + (a.x + nx) + ' ' + (a.y + ny) +
    ' L ' + (b.x + nx) + ' ' + (b.y + ny) +
    ' A ' + pad + ' ' + pad + ' 0 0 1 ' + (b.x - nx) + ' ' + (b.y - ny) +
    ' L ' + (a.x - nx) + ' ' + (a.y - ny) +
    ' A ' + pad + ' ' + pad + ' 0 0 1 ' + (a.x + nx) + ' ' + (a.y + ny) + ' Z';
}

/** 为每个有 >=2 个可见成员的势力绘制半透明包围圈 + 势力名标签。 */
function paintFactionHulls(layer, highlight) {
  if (!graphData || !graphData.nodes) return;
  const byFaction = {};
  graphData.nodes.forEach(n => {
    if (!n.faction || n._visible === false || n.x == null) return;
    (byFaction[n.faction] = byFaction[n.faction] || []).push(n);
  });
  const svgNS = 'http://www.w3.org/2000/svg';
  Object.keys(byFaction).forEach(f => {
    const members = byFaction[f];
    if (members.length < 2) return;
    const pad = 16;
    const path = members.length === 2
      ? capsulePath(members[0], members[1], pad)
      : hullPath(convexHull(members.map(m => ({ x: m.x, y: m.y }))), pad);
    const color = factionColor(f);
    // 高亮某节点时，非其所属势力包围圈降透明
    const dim = highlight && !members.some(m => m.id === highlight) ? 0.22 : 1;
    const hull = document.createElementNS(svgNS, 'path');
    hull.setAttribute('d', path);
    hull.setAttribute('class', 'graph-hull');
    hull.setAttribute('fill', color);
    hull.setAttribute('fill-opacity', String(0.10 * dim));
    hull.setAttribute('stroke', color);
    hull.setAttribute('stroke-opacity', String(0.38 * dim));
    hull.setAttribute('stroke-width', '1.2');
    hull.setAttribute('stroke-dasharray', '5 3');
    layer.appendChild(hull);
    // 势力名标签（几何中心上方）
    let cx = 0, cy = 0;
    members.forEach(m => { cx += m.x; cy += m.y; });
    cx /= members.length; cy /= members.length;
    const label = document.createElementNS(svgNS, 'text');
    label.setAttribute('x', cx);
    label.setAttribute('y', cy - pad - 6);
    label.setAttribute('text-anchor', 'middle');
    label.setAttribute('font-size', '11');
    label.setAttribute('font-weight', '600');
    label.setAttribute('fill', color);
    label.setAttribute('opacity', String(0.85 * dim));
    label.setAttribute('class', 'graph-hull-label');
    label.textContent = f;
    layer.appendChild(label);
  });
}

function renderGraphLegend() {
  const el = document.getElementById('graph-legend');
  if (!el) return;
  const typeHtml = [
    ['character', GRAPH_COLORS.character, '人物'],
    ['location', GRAPH_COLORS.location, '地点'],
    ['faction', GRAPH_COLORS.faction, '势力'],
    ['item', GRAPH_COLORS.item, '物品 / 体系']
  ].map(t => '<div class="legend-item"><span class="legend-dot" style="background:' + t[1] + '"></span>' + t[2] + '</div>').join('');
  let factionHtml = '';
  if (graphFactionList.length) {
    const members = {};
    (graphData && graphData.nodes || []).forEach(n => { if (n.faction) members[n.faction] = (members[n.faction] || 0) + 1; });
    factionHtml = '<div class="legend-divider">势力归属</div>' + graphFactionList.map(f =>
      '<div class="legend-item" title="' + escapeHtml(f) + ' 势力"><span class="legend-dot" style="background:' + factionColor(f) + '"></span>' +
      escapeHtml(f) + '<span class="legend-count">' + members[f] + '</span></div>').join('');
  }
  el.innerHTML = typeHtml + factionHtml;
}

// ========== 节点过滤（类型 / 提及数 / 搜索） ==========

function initGraphFilters() {
  graphFilters = { types: {}, minMentions: 0, query: '' };
  ['character', 'location', 'faction', 'item', 'system', 'rule'].forEach(g => graphFilters.types[g] = true);
  const range = document.getElementById('graph-mention-range');
  const val = document.getElementById('graph-mention-val');
  const query = document.getElementById('graph-filter-query');
  if (range) range.value = '0';
  if (val) val.textContent = '0';
  if (query) query.value = '';
}

function renderGraphChips() {
  const el = document.getElementById('graph-type-chips');
  if (!el) return;
  const order = [['character', '人物'], ['location', '地点'], ['faction', '势力'], ['item', '物品'], ['system', '体系'], ['rule', '规则']];
  el.innerHTML = order.map(function (t) {
    return '<button type="button" class="graph-chip' + (graphFilters.types[t[0]] !== false ? ' active' : '') +
      '" data-group="' + t[0] + '" onclick="toggleGraphType(\'' + t[0] + '\')">' + t[1] + '</button>';
  }).join('');
}

function toggleGraphType(g) {
  if (graphFilters.types[g] === false) graphFilters.types[g] = true; else graphFilters.types[g] = false;
  renderGraphChips();
  applyGraphFilters();
}

function applyGraphFilters() {
  if (!graphData) return;
  const range = document.getElementById('graph-mention-range');
  const query = document.getElementById('graph-filter-query');
  const minM = range ? (Number(range.value) || 0) : 0;
  const q = query ? (query.value || '').trim().toLowerCase() : '';
  graphFilters.minMentions = minM;
  graphFilters.query = q;
  const val = document.getElementById('graph-mention-val');
  if (val) val.textContent = String(minM);
  let visibleNodes = 0;
  (graphData.nodes || []).forEach(n => {
    n._visible = (graphFilters.types[n.group] !== false) &&
      (n.mentions || 0) >= minM &&
      (!q || (n.label || '').toLowerCase().includes(q) || (n.desc || '').toLowerCase().includes(q));
    if (n._visible) visibleNodes++;
  });
  let visibleEdges = 0;
  (graphData.edges || []).forEach(e => {
    const a = graphData.nodes.find(x => x.id === e.source);
    const b = graphData.nodes.find(x => x.id === e.target);
    if (a && b && a._visible !== false && b._visible !== false) visibleEdges++;
  });
  const statsEl = document.getElementById('graph-stats');
  if (statsEl) {
    const s = graphData.stats || {};
    statsEl.textContent = (s.characters || 0) + ' 人物 · ' + (s.worldEntities || 0) + ' 场景/势力 · ' + visibleEdges + ' 关系' +
      (visibleNodes < (graphData.nodes || []).length ? ' · 过滤 ' + visibleNodes + '/' + (graphData.nodes || []).length + ' 节点' : '');
  }
  paintGraph();
}

// ========== 章节演变动画 ==========

function graphActiveEdgeSet() {
  const set = new Set();
  if (!graphData || !graphData.chapters || !graphPlay) return set;
  const upto = Math.min(graphPlay.idx, graphData.chapters.length);
  for (let i = 0; i < upto; i++) {
    const added = graphData.chapters[i].added || [];
    for (const e of added) set.add(e.source + '\u0000' + e.target);
  }
  return set;
}

function graphNewEdgeSet() {
  const set = new Set();
  if (graphPlay && graphPlay.idx > 0 && graphData && graphData.chapters && graphData.chapters[graphPlay.idx - 1]) {
    const added = graphData.chapters[graphPlay.idx - 1].added || [];
    for (const e of added) set.add(e.source + '\u0000' + e.target);
  }
  return set;
}

function setupGraphPlayBar() {
  const max = graphData && graphData.chapters ? graphData.chapters.length : 0;
  const bar = document.getElementById('graph-play-bar');
  const slider = document.getElementById('graph-play-slider');
  const label = document.getElementById('graph-play-label');
  if (slider) { slider.max = String(max); slider.value = '0'; }
  if (label) label.textContent = '初始（仅节点）';
  if (bar) { if (max > 0) bar.classList.remove('hidden'); else bar.classList.add('hidden'); }
  if (graphPlay && graphPlay.timer) clearInterval(graphPlay.timer);
  graphPlay = max > 0 ? { idx: 0, timer: 0, playing: false } : null;
  updateGraphPlayBtn();
}

function setGraphPlayIdx(idx) {
  if (!graphPlay || !graphData || !graphData.chapters) return;
  graphPlay.idx = Math.max(0, Math.min(idx, graphData.chapters.length));
  const slider = document.getElementById('graph-play-slider');
  if (slider) slider.value = String(graphPlay.idx);
  const label = document.getElementById('graph-play-label');
  if (label) {
    if (graphPlay.idx === 0) {
      label.textContent = '初始（仅节点）';
    } else {
      const ch = graphData.chapters[graphPlay.idx - 1] || {};
      label.textContent = (ch.title || '') + ' · 新增 ' + ((ch.added || []).length) + ' 条关系';
    }
  }
  paintGraph();
}

function toggleGraphPlay() {
  if (!graphPlay || !graphData || !graphData.chapters || !graphData.chapters.length) {
    showToast('该书暂无章节数据', 'warn');
    return;
  }
  if (graphPlay.playing) {
    graphPlay.playing = false;
    clearInterval(graphPlay.timer);
    updateGraphPlayBtn();
    return;
  }
  graphPlay.playing = true;
  updateGraphPlayBtn();
  if (graphPlay.idx >= graphData.chapters.length) setGraphPlayIdx(0);
  graphPlay.timer = setInterval(function () {
    if (graphPlay.idx >= graphData.chapters.length) {
      graphPlay.playing = false;
      clearInterval(graphPlay.timer);
      updateGraphPlayBtn();
      return;
    }
    setGraphPlayIdx(graphPlay.idx + 1);
  }, 700);
}

function stopGraphPlay() {
  if (!graphPlay) return;
  if (graphPlay.timer) clearInterval(graphPlay.timer);
  graphPlay.playing = false;
  setGraphPlayIdx(0);
  updateGraphPlayBtn();
}

function onGraphPlaySlider(v) {
  if (!graphPlay) return;
  if (graphPlay.timer) clearInterval(graphPlay.timer);
  graphPlay.playing = false;
  setGraphPlayIdx(Number(v));
  updateGraphPlayBtn();
}

function updateGraphPlayBtn() {
  const btn = document.getElementById('graph-play-btn');
  if (btn) btn.textContent = graphPlay && graphPlay.playing ? '⏸ 暂停' : '▶ 演变播放';
}

// 窗口尺寸变化时自适应重绘
window.addEventListener('resize', () => {
  if (graphData && graphSvg && document.getElementById('panel-graph').classList.contains('active')) {
    const { W, H } = graphCanvasSize();
    graphSvg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    paintGraph();
  }
});
