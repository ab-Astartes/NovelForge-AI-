// 墨阁 · NovelForge Studio — 实力分布地图模块 (P4)
// 依赖 app.js 提供的全局：API / authUrl / authHeaders / escapeHtml / showToast
// Voronoi 色块地图渲染（Sutherland-Hodgman 半平面裁剪），零外部依赖。
// 数据源：/api/faction-map（角色势力归属聚合 + 正文下辖/关系挖掘）

const FM_COLORS = {
  bg: '#f4f1e8',            // 地图底色（羊皮纸感）
  grid: '#e3dfd2',          // 网格线
  border: '#8b8574',        // 色块描边
  borderDark: '#6b6555',
  relHostile: '#c0392b',    // 敌对关系线
  relAlly: '#27ae60',       // 同盟关系线
  text: '#4a4233',          // 地图文字
  textStrong: '#2f2a20'
};
// 势力色板（与关系图谱同源，保证跨面板一致）
const FM_FACTION_PALETTE = ['#e06060', '#5c9ee0', '#e0a65c', '#6fbf6f', '#b078e0', '#e078b0', '#4fc4c4', '#a0a85c', '#d88a4a', '#7f9fbf', '#c4b05c', '#9a7fb8'];

let fmData = null;          // 后端原始数据 {factions, relations, stats}
let fmSelected = null;      // 当前选中势力名
let fmSeedPts = [];         // Voronoi 种子点 [{x,y,faction}]
let fmCells = [];           // 计算好的单元 [{points:[[x,y],...], faction, cx, cy}]
let fmDragMode = false;     // 拖拽调整势力中心模式
let fmDragging = null;      // 当前正在拖拽的势力名
let fmSeedOverride = new Map();   // faction -> {x,y} 手动调整的势力中心
const FM_W = 920, FM_H = 620;   // 地图画布逻辑尺寸

// ========== 数据加载 ==========

async function loadFactionMap() {
  const sel = document.getElementById('factionmap-book');
  const bookPath = sel ? sel.value : '';
  const canvas = document.getElementById('factionmap-canvas');
  const statsEl = document.getElementById('factionmap-stats');
  const detailEl = document.getElementById('factionmap-detail');
  if (!bookPath) {
    if (canvas) canvas.innerHTML = '<div class="empty-hint">请先选择书籍</div>';
    if (statsEl) statsEl.textContent = '';
    if (detailEl) detailEl.innerHTML = '<div class="card-hint">选择势力查看详情</div>';
    return;
  }
  if (canvas) canvas.innerHTML = '<div class="empty-hint">正在绘制实力分布地图…</div>';
  try {
    const res = await fetch(authUrl(API + '/api/faction-map?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '加载失败');
    fmData = data;
    fmSelected = null;
    fmSeedOverride = new Map();   // 新数据重置手动调整
    fmDragMode = false;
    if (statsEl) {
      const s = data.stats || {};
      statsEl.textContent =
        (s.factions || 0) + ' 势力 · ' + (s.covered || 0) + '/' + (s.characters || 0) + ' 角色归属' +
        ' · ' + (s.subordinates || 0) + ' 下辖 · ' + (s.relations || 0) + ' 关系' +
        ' · 扫描 ' + (s.chaptersScanned || 0) + ' 章';
    }
    renderFactionMap();
  } catch (e) {
    if (canvas) canvas.innerHTML = '<div class="empty-hint">地图加载失败：' + escapeHtml(e.message) + '</div>';
    if (statsEl) statsEl.textContent = '';
  }
}

function relayoutFactionMap() {
  if (!fmData) return;
  fmSelected = null;
  renderFactionMap();
}

// ========== Voronoi 布局 ==========

/** 单调链凸包（供包围圈与单元渲染复用，与 graph.js 同算法）。 */
function fmConvexHull(pts) {
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

/** 计算线段交点（裁剪辅助）。 */
function fmLineIntersect(p, q, a, b) {
  const d1x = q.x - p.x, d1y = q.y - p.y, d2x = b.x - a.x, d2y = b.y - a.y;
  const den = d1x * d2y - d1y * d2x;
  if (Math.abs(den) < 1e-9) return null;
  const t = ((a.x - p.x) * d2y - (a.y - p.y) * d2x) / den;
  return { x: p.x + t * d1x, y: p.y + t * d1y };
}

/** Sutherland–Hodgman：用垂直平分线半平面裁剪多边形，得到 Voronoi 凸单元。
 *  p 距 seed 更近 ⟺ |p-seed|² ≤ |p-other|² ⟺ a·p.x + b·p.y ≤ c，其中 (a,b)=other-seed，c=(|other|²-|seed|²)/2。 */
function fmClipPoly(poly, seed, other) {
  const a = other.x - seed.x, b = other.y - seed.y;
  const c = (other.x * other.x + other.y * other.y - seed.x * seed.x - seed.y * seed.y) / 2;
  const inside = pt => a * pt.x + b * pt.y <= c + 1e-9;
  // 垂直平分线上两点：中点 mid 与 mid + 线方向（法向旋转 90°）
  const mx = (seed.x + other.x) / 2, my = (seed.y + other.y) / 2;
  const pA = { x: mx, y: my };
  const pB = { x: mx - b, y: my + a };
  let out = [];
  for (let i = 0; i < poly.length; i++) {
    const cur = poly[i], prev = poly[(i + poly.length - 1) % poly.length];
    const curIn = inside(cur), prevIn = inside(prev);
    if (curIn) {
      if (!prevIn) {
        const ip = fmLineIntersect(prev, cur, pA, pB);
        if (ip) out.push(ip);
      }
      out.push(cur);
    } else if (prevIn) {
      const ip = fmLineIntersect(prev, cur, pA, pB);
      if (ip) out.push(ip);
    }
  }
  return out;
}

function fmCellCenter(pts) {
  let sx = 0, sy = 0;
  for (const p of pts) { sx += p.x; sy += p.y; }
  return { x: sx / pts.length, y: sy / pts.length };
}

/** 默认格点布局（重要势力居中）+ 权重控抖动（权重高=块大稳定）。 */
function fmBuildSeeds(factions) {
  const n = factions.length;
  if (n === 0) return [];
  const cols = Math.max(1, Math.ceil(Math.sqrt(n * 1.6)));
  const rows = Math.max(1, Math.ceil(n / cols));
  const grid = [];
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      if (grid.length >= n) break;
      const gx = FM_W * (0.10 + 0.80 * (c + 0.5) / cols);
      const gy = FM_H * (0.12 + 0.76 * (r + 0.5) / rows);
      grid.push({ x: gx, y: gy, d: Math.hypot(gx - FM_W / 2, gy - FM_H / 2) });
    }
  }
  grid.sort((a, b) => a.d - b.d);
  const rng = mulberry32(factions.reduce((s, f) => s + (f.name ? f.name.length : 1), 7));
  return factions.map((f, i) => {
    const g = grid[Math.min(i, grid.length - 1)];
    const jitter = Math.max(0, 1 - (f.weight || 1) / 6) * 90;   // 权重越高扰动越小 → 色块越大越稳
    return {
      x: Math.max(30, Math.min(FM_W - 30, g.x + (rng() - 0.5) * jitter)),
      y: Math.max(30, Math.min(FM_H - 30, g.y + (rng() - 0.5) * jitter)),
      faction: f.name, weight: f.weight || 1
    };
  });
}

/** 生成 Voronoi 单元；override 为手动拖拽调整后的势力中心（faction -> {x,y}）。 */
function computeFactionCells(factions, override) {
  const n = factions.length;
  if (n === 0) return [];
  const base = fmBuildSeeds(factions);
  const seeds = base.map(s => {
    const o = override && override.get(s.faction);
    return o ? { x: o.x, y: o.y, faction: s.faction, weight: s.weight } : s;
  });
  // Voronoi 裁剪（Sutherland–Hodgman 半平面求交）
  const rect = [
    { x: 0, y: 0 }, { x: FM_W, y: 0 }, { x: FM_W, y: FM_H }, { x: 0, y: FM_H }
  ];
  const cells = [];
  for (const s of seeds) {
    let poly = rect.slice();
    for (const o of seeds) {
      if (o === s) continue;
      poly = fmClipPoly(poly, s, o);
      if (poly.length < 3) break;
    }
    if (poly.length < 3) continue;
    const hull = fmConvexHull(poly);
    if (hull.length < 3) continue;
    cells.push({ pts: hull, faction: s.faction, cx: s.x, cy: s.y, center: fmCellCenter(hull) });
  }
  return cells;
}

/** 简易确定性伪随机（mulberry32）。 */
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    let t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

// ========== 渲染 ==========

function fmColorFor(name, idx) {
  const i = (fmData && fmData.factions || []).findIndex(f => f.name === name);
  return FM_FACTION_PALETTE[(i >= 0 ? i : idx) % FM_FACTION_PALETTE.length];
}

function renderFactionMap() {
  const canvas = document.getElementById('factionmap-canvas');
  if (!canvas || !fmData) return;
  const factions = (fmData.factions || []).slice().sort((a, b) => (b.weight || 0) - (a.weight || 0));
  if (factions.length === 0) {
    canvas.innerHTML = '<div class="empty-hint">未识别到势力。请在书阁 → 人物台账补全角色描述（含"萧家/天机阁"等势力词），或补全世界设定后重试。</div>';
    renderFactionLegend([]);
    return;
  }
  const cells = computeFactionCells(factions, fmSeedOverride);
  fmCells = cells;
  fmSeedPts = cells.map(c => ({ x: c.cx, y: c.cy, faction: c.faction }));

  const svgNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + FM_W + ' ' + FM_H);
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  svg.setAttribute('class', 'fm-svg');

  // 背景 + 网格线（标准地图感）
  const bg = document.createElementNS(svgNS, 'rect');
  bg.setAttribute('x', 0); bg.setAttribute('y', 0);
  bg.setAttribute('width', FM_W); bg.setAttribute('height', FM_H);
  bg.setAttribute('fill', FM_COLORS.bg);
  svg.appendChild(bg);
  const gridG = document.createElementNS(svgNS, 'g');
  for (let x = 40; x < FM_W; x += 40) {
    const l = document.createElementNS(svgNS, 'line');
    l.setAttribute('x1', x); l.setAttribute('y1', 0); l.setAttribute('x2', x); l.setAttribute('y2', FM_H);
    l.setAttribute('stroke', FM_COLORS.grid); l.setAttribute('stroke-width', 0.6);
    gridG.appendChild(l);
  }
  for (let y = 40; y < FM_H; y += 40) {
    const l = document.createElementNS(svgNS, 'line');
    l.setAttribute('x1', 0); l.setAttribute('y1', y); l.setAttribute('x2', FM_W); l.setAttribute('y2', y);
    l.setAttribute('stroke', FM_COLORS.grid); l.setAttribute('stroke-width', 0.6);
    gridG.appendChild(l);
  }
  svg.appendChild(gridG);

  // 关系连线（先画在色块之下）
  const relG = document.createElementNS(svgNS, 'g');
  const relByFac = new Map();
  for (const r of (fmData.relations || [])) {
    if (!relByFac.has(r.source)) relByFac.set(r.source, []);
    if (!relByFac.has(r.target)) relByFac.set(r.target, []);
    relByFac.get(r.source).push(r);
    relByFac.get(r.target).push(r);
  }
  const cellByFac = new Map(cells.map(c => [c.faction, c]));
  for (const r of (fmData.relations || [])) {
    const a = cellByFac.get(r.source), b = cellByFac.get(r.target);
    if (!a || !b) continue;
    const hostile = r.type === '敌对' || r.type === '竞争' || r.type === '仇视';
    const line = document.createElementNS(svgNS, 'line');
    line.setAttribute('x1', a.cx); line.setAttribute('y1', a.cy);
    line.setAttribute('x2', b.cx); line.setAttribute('y2', b.cy);
    line.setAttribute('stroke', hostile ? FM_COLORS.relHostile : FM_COLORS.relAlly);
    line.setAttribute('stroke-width', hostile ? 2.2 : 1.6);
    line.setAttribute('stroke-dasharray', hostile ? '6,4' : '');
    line.setAttribute('stroke-opacity', 0.55);
    line.setAttribute('class', 'fm-rel-line');
    line.setAttribute('data-type', r.type);
    const mid = { x: (a.cx + b.cx) / 2, y: (a.cy + b.cy) / 2 };
    line.setAttribute('data-mx', mid.x); line.setAttribute('data-my', mid.y);
    line.setAttribute('data-rel', r.type);
    relG.appendChild(line);
  }
  svg.appendChild(relG);

  // 势力色块
  const cellG = document.createElementNS(svgNS, 'g');
  const idxByName = new Map(factions.map((f, i) => [f.name, i]));
  cells.forEach((c, ci) => {
    const poly = document.createElementNS(svgNS, 'polygon');
    const ptsStr = c.pts.map(p => Math.round(p.x * 10) / 10 + ',' + Math.round(p.y * 10) / 10).join(' ');
    poly.setAttribute('points', ptsStr);
    const base = fmColorFor(c.faction, idxByName.get(c.faction) ?? ci);
    const selected = fmSelected === c.faction;
    const dim = fmSelected && !selected;
    poly.setAttribute('fill', base);
    poly.setAttribute('fill-opacity', selected ? 0.9 : (dim ? 0.35 : 0.72));
    poly.setAttribute('stroke', selected ? FM_COLORS.borderDark : FM_COLORS.border);
    poly.setAttribute('stroke-width', selected ? 2.5 : 1.4);
    poly.setAttribute('data-faction', c.faction);
    poly.setAttribute('class', 'fm-cell');
    cellG.appendChild(poly);
  });
  svg.appendChild(cellG);

  // 势力名标注（质心，两行：名称 + 下辖数/风格短标）
  const labelG = document.createElementNS(svgNS, 'g');
  cells.forEach(c => {
    const f = (fmData.factions || []).find(x => x.name === c.faction);
    if (!f) return;
    const t1 = document.createElementNS(svgNS, 'text');
    t1.setAttribute('x', c.center.x); t1.setAttribute('y', c.center.y - 2);
    t1.setAttribute('text-anchor', 'middle');
    t1.setAttribute('class', 'fm-label-main');
    t1.setAttribute('fill', FM_COLORS.textStrong);
    t1.setAttribute('font-size', '15');
    t1.setAttribute('font-weight', '700');
    t1.setAttribute('font-family', 'sans-serif');
    t1.textContent = f.name;
    labelG.appendChild(t1);
    const sub = document.createElementNS(svgNS, 'text');
    sub.setAttribute('x', c.center.x); sub.setAttribute('y', c.center.y + 16);
    sub.setAttribute('text-anchor', 'middle');
    sub.setAttribute('class', 'fm-label-sub');
    sub.setAttribute('fill', FM_COLORS.text);
    sub.setAttribute('font-size', '11');
    sub.setAttribute('font-family', 'sans-serif');
    const subTxt = [];
    if (f.domain) subTxt.push(f.domain);
    if (f.subordinates && f.subordinates.length) subTxt.push('辖' + f.subordinates.length);
    if (f.members && f.members.length) subTxt.push(f.members.length + '人');
    sub.textContent = subTxt.join(' · ');
    labelG.appendChild(sub);
  });
  svg.appendChild(labelG);

  // 拖拽调整模式：在每个势力中心渲染可拖动手柄
  if (fmDragMode) {
    cells.forEach(c => {
      const dot = document.createElementNS(svgNS, 'circle');
      dot.setAttribute('cx', c.cx); dot.setAttribute('cy', c.cy);
      dot.setAttribute('r', 7);
      dot.setAttribute('class', 'fm-drag-dot');
      dot.setAttribute('data-faction', c.faction);
      dot.addEventListener('mousedown', (e) => { e.stopPropagation(); e.preventDefault(); fmDragging = c.faction; });
      svg.appendChild(dot);
    });
  }

  // 交互：hover 高亮 + tooltip；点击选中
  const tooltip = document.getElementById('factionmap-tooltip');
  const hit = (evt) => {
    const target = evt.target;
    if (target && target.getAttribute && target.getAttribute('data-faction')) {
      return target.getAttribute('data-faction');
    }
    return null;
  };
  svg.addEventListener('mousemove', (evt) => {
    const fname = hit(evt);
    if (fname && tooltip) {
      const f = (fmData.factions || []).find(x => x.name === fname);
      if (f) {
        const rect = svg.getBoundingClientRect();
        const sx = (evt.clientX - rect.left) / rect.width * FM_W;
        const sy = (evt.clientY - rect.top) / rect.height * FM_H;
        tooltip.style.left = Math.min(FM_W - 260, sx + 16) + 'px';
        tooltip.style.top = Math.max(8, sy - 40) + 'px';
        tooltip.innerHTML = '<div class="fm-tip-title">' + escapeHtml(f.name) + '</div>' +
          (f.domain ? '<div class="fm-tip-row">📍 ' + escapeHtml(f.domain) + '</div>' : '') +
          '<div class="fm-tip-row">' + (f.members || []).length + ' 名成员 · 提及 ' + (f.mentions || 0) + ' 次</div>' +
          (f.subordinates && f.subordinates.length ? '<div class="fm-tip-row">下辖：' + escapeHtml(f.subordinates.join('、')) + '</div>' : '');
        tooltip.classList.remove('hidden');
        // hover 提亮
        cellG.querySelectorAll('.fm-cell').forEach(p => {
          const isTarget = p.getAttribute('data-faction') === fname;
          const dim2 = fmSelected && fmSelected !== fname;
          p.setAttribute('fill-opacity', isTarget ? 0.9 : (dim2 ? 0.35 : 0.72));
        });
      }
    } else if (tooltip) {
      tooltip.classList.add('hidden');
      cellG.querySelectorAll('.fm-cell').forEach(p => {
        const isSel = fmSelected === p.getAttribute('data-faction');
        p.setAttribute('fill-opacity', isSel ? 0.9 : (fmSelected ? 0.35 : 0.72));
      });
    }
  });
  svg.addEventListener('mouseleave', () => {
    if (tooltip) tooltip.classList.add('hidden');
    cellG.querySelectorAll('.fm-cell').forEach(p => {
      const isSel = fmSelected === p.getAttribute('data-faction');
      p.setAttribute('fill-opacity', isSel ? 0.9 : (fmSelected ? 0.35 : 0.72));
    });
  });
  svg.addEventListener('click', (evt) => {
    if (fmDragMode) return;   // 拖拽模式下点击不触发选中
    const fname = hit(evt);
    if (fname) {
      fmSelected = fmSelected === fname ? null : fname;
      renderFactionMap();
      renderFactionDetail(fname);
      renderFactionLegend((fmData.factions || []).map(f => f.name), fname);
    } else {
      fmSelected = null;
      renderFactionMap();
      renderFactionDetail(null);
      renderFactionLegend((fmData.factions || []).map(f => f.name), null);
    }
  });

  canvas.innerHTML = '';
  canvas.appendChild(svg);
  renderFactionLegend(factions.map(f => f.name), fmSelected);
  if (!fmSelected) renderFactionDetail(null);
}

// ========== 图例与详情 ==========

function renderFactionLegend(names, selected) {
  const el = document.getElementById('factionmap-legend');
  if (!el) return;
  el.innerHTML = '';
  names.forEach((name, i) => {
    const item = document.createElement('div');
    item.className = 'legend-item' + (selected === name ? ' legend-item-active' : '');
    const dot = document.createElement('span');
    dot.className = 'legend-dot';
    dot.style.background = FM_FACTION_PALETTE[i % FM_FACTION_PALETTE.length];
    const txt = document.createElement('span');
    txt.textContent = name;
    item.appendChild(dot);
    item.appendChild(txt);
    item.title = name + ' 势力';
    item.onclick = () => {
      fmSelected = fmSelected === name ? null : name;
      renderFactionMap();
      renderFactionDetail(fmSelected);
    };
    el.appendChild(item);
  });
  if (el.children.length === 0) el.innerHTML = '<div class="card-hint">暂无势力数据</div>';
}

function renderFactionDetail(name) {
  const el = document.getElementById('factionmap-detail');
  if (!el) return;
  if (!name) {
    const rels = fmData && fmData.relations && fmData.relations.length ? fmData.relations.length : 0;
    el.innerHTML = '<div class="card-hint">点击色块查看势力档案：范围、下辖、特点与风格。' +
      (rels ? ' 地图虚线为敌对（红）/同盟（绿）关系。' : '') + '</div>';
    return;
  }
  const f = (fmData.factions || []).find(x => x.name === name);
  if (!f) { el.innerHTML = '<div class="card-hint">未找到势力档案</div>'; return; }
  const i = (fmData.factions || []).findIndex(x => x.name === name);
  const color = FM_FACTION_PALETTE[i % FM_FACTION_PALETTE.length];
  let html = '<div class="fm-detail-head"><span class="fm-detail-dot" style="background:' + color + '"></span>' +
    '<span class="fm-detail-name">' + escapeHtml(f.name) + '</span></div>';
  if (f.domain) html += '<div class="fm-detail-row">📍 范围：' + escapeHtml(f.domain) + '</div>';
  if (f.style) html += '<div class="fm-detail-row">🎭 风格：' + escapeHtml(f.style) + '</div>';
  const traits = (f.traits || []);
  if (traits.length) html += '<div class="fm-detail-row">🏷 特点：' + traits.map(t => '<span class="fm-tag">' + escapeHtml(t) + '</span>').join('') + '</div>';
  const members = (f.members || []);
  if (members.length) html += '<div class="fm-detail-row">👥 成员（' + members.length + '）：<span class="fm-members">' + escapeHtml(members.join('、')) + '</span></div>';
  const subs = (f.subordinates || []);
  if (subs.length) html += '<div class="fm-detail-row">🏰 下辖势力：<span class="fm-members">' + escapeHtml(subs.join('、')) + '</span></div>';
  if (f.desc) html += '<div class="fm-detail-row fm-desc">' + escapeHtml(f.desc.length > 220 ? f.desc.slice(0, 220) + '…' : f.desc) + '</div>';
  // 关联势力（关系）
  const rels = (fmData.relations || []).filter(r => r.source === name || r.target === name);
  if (rels.length) {
    html += '<div class="fm-detail-row">🔗 关系：' + rels.map(r => {
      const other = r.source === name ? r.target : r.source;
      const hostile = r.type === '敌对' || r.type === '竞争' || r.type === '仇视';
      return '<span class="fm-rel-badge ' + (hostile ? 'fm-rel-hostile' : 'fm-rel-ally') + '">' +
        escapeHtml(other) + ' · ' + escapeHtml(r.type) + '</span>';
    }).join(' ') + '</div>';
  }
  el.innerHTML = html;
}

// ========== 拖拽调整势力中心 ==========

function toggleFmDrag() {
  fmDragMode = !fmDragMode;
  const btn = document.getElementById('factionmap-drag-toggle');
  if (btn) btn.classList.toggle('active', fmDragMode);
  renderFactionMap();
}

function fmSvgPoint(svg, evt) {
  const rect = svg.getBoundingClientRect();
  return {
    x: (evt.clientX - rect.left) / rect.width * FM_W,
    y: (evt.clientY - rect.top) / rect.height * FM_H
  };
}

// 全局拖拽：仅在按住手柄时重算 Voronoi
window.addEventListener('mousemove', (evt) => {
  if (!fmDragging) return;
  const svg = document.querySelector('#factionmap-canvas svg');
  if (!svg) return;
  const p = fmSvgPoint(svg, evt);
  fmSeedOverride.set(fmDragging, {
    x: Math.max(12, Math.min(FM_W - 12, p.x)),
    y: Math.max(12, Math.min(FM_H - 12, p.y))
  });
  renderFactionMap();
});
window.addEventListener('mouseup', () => { fmDragging = null; });

// ========== 导出 PNG ==========

function exportFmPng() {
  const svg = document.querySelector('#factionmap-canvas svg');
  if (!svg) { showToast('请先生成地图', 'warning'); return; }
  const clone = svg.cloneNode(true);
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', FM_W);
  clone.setAttribute('height', FM_H);
  const data = new XMLSerializer().serializeToString(clone);
  const blob = new Blob([data], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const img = new Image();
  img.onload = () => {
    const scale = 2;
    const canvas = document.createElement('canvas');
    canvas.width = FM_W * scale; canvas.height = FM_H * scale;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = FM_COLORS.bg; ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    URL.revokeObjectURL(url);
    canvas.toBlob(b => {
      if (!b) { showToast('导出失败', 'error'); return; }
      const a = document.createElement('a');
      a.href = URL.createObjectURL(b);
      a.download = '实力分布地图_' + (document.getElementById('factionmap-book')?.value.split(/[\\/]/).pop() || 'NovelForge') + '.png';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1500);
      showToast('已导出高清 PNG（' + (FM_W * scale) + '×' + (FM_H * scale) + '）', 'success');
    }, 'image/png');
  };
  img.onerror = () => { showToast('PNG 渲染失败', 'error'); URL.revokeObjectURL(url); };
  img.src = url;
}
