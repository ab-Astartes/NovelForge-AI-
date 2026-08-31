/* ===================== 情绪张力曲线与节拍器 =====================
   后端按章计算张力分与节拍目标带，本面板用 SVG 绘制全书张力曲线，
   支持点击章节看指标明细与代表性片段、告警列表与 CSV 导出。 */

let tensionData = null;
let tensionSel = null;   // 当前选中章节号

async function loadTension() {
  const book = document.getElementById('tension-book')?.value?.trim();
  const box = document.getElementById('tension-body');
  const stats = document.getElementById('tension-stats');
  if (!box) return;
  if (!book) { box.innerHTML = '<div class="empty-hint">请先在上方选择书目。</div>'; if (stats) stats.textContent = ''; return; }
  box.innerHTML = '<div class="empty-hint">正在分析全书节奏…</div>';
  try {
    // 优先取「状态机 ↔ 张力」联动视图（含分支节点叠加）；失败则回退纯张力
    let j = null;
    try {
      const r1 = await fetch(authUrl(API + '/api/branching/tension?path=' + encodeURIComponent(book)), { headers: authHeaders() });
      const j1 = await r1.json();
      if (j1 && j1.ok) j = j1;
    } catch (e) { /* 忽略，走回退 */ }
    if (!j) {
      const r = await fetch(authUrl(API + '/api/tension?path=' + encodeURIComponent(book)), { headers: authHeaders() });
      const jr = await r.json();
      if (!jr.ok) { box.innerHTML = `<div class="empty-hint">分析失败：${escapeHtml(jr.error || '未知错误')}</div>`; return; }
      j = jr;
    }
    tensionData = j;
    tensionSel = null;
    renderTension();
    if (stats) {
      const s = j.stats || {};
      let txt = `章节 ${s.chapters || 0} · 均分 ${s.avg || 0} · 峰值 ${s.max || 0}（第${s.maxChapter || '-'}章）· 低谷 ${s.min || 0}（第${s.minChapter || '-'}章）· 告警 ${s.warnings || 0}`;
      if (j.hasBranching) txt += ` · 分支节点 ${Array.isArray(j.nodes) ? j.nodes.length : 0}`;
      stats.textContent = txt;
    }
  } catch (e) {
    box.innerHTML = `<div class="empty-hint">请求失败：${escapeHtml(e.message)}</div>`;
  }
}

function renderTension() {
  const box = document.getElementById('tension-body');
  if (!box || !tensionData) return;
  const curve = tensionData.curve || [];
  if (!curve.length) { box.innerHTML = '<div class="empty-hint">未读取到章节正文。</div>'; return; }

  const hasBranch = !!tensionData.hasBranching && Array.isArray(tensionData.branchMarkers) && tensionData.branchMarkers.length > 0;
  let html = '';
  html += '<div class="tension-chart-wrap">' + renderTensionChart(curve, tensionData.branchMarkers || []) + '</div>';
  html += '<div class="tension-legend">'
        + '<span><i class="lg-line"></i>实际张力</span>'
        + '<span><i class="lg-band"></i>节拍目标带（起承转合）</span>'
        + '<span><i class="lg-dot"></i>点击圆点查看该章明细</span>'
        + (hasBranch ? '<span><i class="lg-branch"></i>分支节点标记（悬停看活跃态）</span>' : '')
        + '</div>';

  const warns = tensionData.warnings || [];
  if (warns.length) {
    html += '<div class="glossary-warns tension-warns">';
    warns.forEach(w => {
      const cls = w.level === 'error' ? 'warn-error' : (w.level === 'warn' ? 'warn-warn' : 'warn-info');
      html += `<div class="glossary-warn ${cls}"><span class="warn-tag">${escapeHtml(w.type)}</span>${escapeHtml(w.message)}</div>`;
    });
    html += '</div>';
  }
  // 分支节点 ↔ 张力 联动概览
  if (hasBranch) {
    html += '<div class="branch-link-summary"><b>状态机 ↔ 张力联动</b>：下列章节含分支节点，其活跃 Flag/属性已叠加到该章张力点。</div>';
    const rows = tensionData.branchMarkers.map(m => {
      const flags = (m.flags || []).join('、');
      const attrs = (m.attrs || []).join('、');
      const state = [flags ? ('Flag: ' + flags) : '', attrs ? ('Attr: ' + attrs) : ''].filter(Boolean).join(' · ');
      return `<div class="branch-link-row"><span class="bl-ch">第${m.chapter}章</span><span class="bl-title">${escapeHtml(m.title)}</span><span class="bl-state">${state || '（无状态）'}</span></div>`;
    }).join('');
    html += '<div class="branch-link-list">' + rows + '</div>';
  }
  html += '<div id="tension-detail" class="tension-detail"></div>';
  html += '<div class="tension-table-wrap"><table class="ledger-table"><thead><tr>'
        + '<th>章</th><th>张力</th><th>目标</th><th>偏差</th><th>字数</th><th>对话%</th><th>句长</th><th>动作</th><th>转折</th><th>情绪</th></tr></thead><tbody>';
  curve.forEach(c => {
    const dev = Number(c.deviation || 0);
    const devCls = dev < -25 ? 'io-out' : (dev > 25 ? 'io-in' : '');
    const hasBN = (c.branchNodes || []).length > 0;
    html += `<tr class="tension-row ${tensionSel === c.chapter ? 'row-sel' : ''} ${hasBN ? 'row-branch' : ''}" data-tch="${c.chapter}">
      <td class="c-ch">${c.chapter}${hasBN ? ' <span class="bl-dot" title="含分支节点">◈</span>' : ''}</td>
      <td><b>${c.score}</b></td>
      <td>${c.target}</td>
      <td class="${devCls}">${dev > 0 ? '+' : ''}${dev}</td>
      <td>${c.words}</td>
      <td>${c.dialogRatio}</td>
      <td>${c.avgSentenceLen}</td>
      <td>${c.actionDensity}</td>
      <td>${c.turnDensity}</td>
      <td>${c.emotion}</td>
    </tr>`;
  });
  html += '</tbody></table></div>';
  box.innerHTML = html;

  box.querySelectorAll('[data-tch]').forEach(tr => {
    tr.onclick = () => { tensionSel = Number(tr.dataset.tch); renderTensionDetail(); renderTension(); };
  });
  renderTensionDetail();
}

/** SVG 曲线：目标带（±12）+ 实际折线 + 点 + 分支节点标记 */
function renderTensionChart(curve, markers) {
  const W = 900, H = 300, PADL = 42, PADR = 16, PADT = 14, PADB = 28;
  const iw = W - PADL - PADR, ih = H - PADT - PADB;
  const n = curve.length;
  const x = i => PADL + (n === 1 ? iw / 2 : iw * i / (n - 1));
  const y = v => PADT + ih * (1 - Math.max(0, Math.min(100, v)) / 100);
  const idxOf = ch => curve.findIndex(c => c.chapter === ch);
  const markersArr = Array.isArray(markers) ? markers : [];

  let svg = `<svg viewBox="0 0 ${W} ${H}" class="tension-svg" preserveAspectRatio="xMidYMid meet">`;
  // 网格与刻度
  [0, 25, 50, 75, 100].forEach(v => {
    svg += `<line x1="${PADL}" y1="${y(v)}" x2="${W - PADR}" y2="${y(v)}" class="tv-grid"/>`;
    svg += `<text x="${PADL - 8}" y="${y(v) + 4}" text-anchor="end" class="tv-tick">${v}</text>`;
  });
  // 目标带（±12）
  let up = '', dn = '';
  curve.forEach((c, i) => {
    const hi = Math.min(100, Number(c.target) + 12), lo = Math.max(0, Number(c.target) - 12);
    up += `${i === 0 ? 'M' : 'L'}${x(i)},${y(hi)} `;
    dn += `${i === 0 ? 'M' : 'L'}${x(i)},${y(lo)} `;
  });
  svg += `<path d="${up}" class="tv-band-line"/><path d="${dn}" class="tv-band-line"/>`;
  // 实际曲线
  let line = '', area = `M${x(0)},${y(0)} `;
  curve.forEach((c, i) => {
    const px = x(i), py = y(Number(c.score));
    line += `${i === 0 ? 'M' : 'L'}${px},${py} `;
    area += `L${px},${py} `;
  });
  area += `L${x(n - 1)},${y(0)} Z`;
  svg += `<path d="${area}" class="tv-area"/>`;
  svg += `<path d="${line}" class="tv-line"/>`;
  // 分支节点标记（菱形），置于该章曲线点上方
  markersArr.forEach(m => {
    const i = idxOf(m.chapter);
    if (i < 0) return;
    const px = x(i), py = y(Number(curve[i].score)) - 13;
    const tp = (m.type || 'scene');
    const flags = (m.flags || []).join('、');
    const attrs = (m.attrs || []).join('、');
    const state = [flags ? ('Flag: ' + flags) : '', attrs ? ('Attr: ' + attrs) : ''].filter(Boolean).join(' · ');
    const tip = `第${m.chapter}章 · ${m.title}（${tp}）${state ? '\n活跃态：' + state : ''}`;
    svg += `<rect x="${px - 5}" y="${py - 5}" width="10" height="10" transform="rotate(45 ${px} ${py})" class="tv-bm tv-bm-${tp}"><title>${escapeHtml(tip)}</title></rect>`;
  });
  // 点
  curve.forEach((c, i) => {
    const px = x(i), py = y(Number(c.score));
    const sel = tensionSel === c.chapter;
    svg += `<circle cx="${px}" cy="${py}" r="${sel ? 6 : 3.6}" class="tv-dot ${sel ? 'dot-sel' : ''}" data-dch="${c.chapter}"><title>第${c.chapter}章 张力 ${c.score}（目标 ${c.target}）</title></circle>`;
    // 章节号（每 N 章标一次，避免拥挤）
    const step = Math.ceil(n / 20);
    if (i % step === 0 || i === n - 1) {
      svg += `<text x="${px}" y="${H - 8}" text-anchor="middle" class="tv-tick">${c.chapter}</text>`;
    }
  });
  svg += '</svg>';
  return svg;
}

function renderTensionDetail() {
  const box = document.getElementById('tension-detail');
  if (!box) return;
  if (tensionSel == null) { box.innerHTML = '<div class="card-hint">点击曲线圆点或下方表格行，查看该章节奏明细与代表性片段。</div>'; return; }
  const c = (tensionData.curve || []).find(x => x.chapter === tensionSel);
  if (!c) { box.innerHTML = ''; return; }
  const dev = Number(c.deviation || 0);
  const verdict = dev < -25 ? '偏温吞：该推进时没起来'
      : dev > 25 ? '过早发力：该铺垫时已拉满'
      : Math.abs(dev) <= 10 ? '贴合节拍' : (dev < 0 ? '略低于节拍' : '略高于节拍');
  const ex = (c.excerpts || []).map(e => `<li>${escapeHtml(e)}</li>`).join('');
  const bn = (c.branchNodes || []);
  const bnHtml = bn.length ? `<div class="td-branch">
    <b>分支节点（活跃态）：</b>
    ${bn.map(b => {
      const flags = (b.flags || []).join('、');
      const attrs = (b.attrs || []).join('、');
      const st = [flags ? ('Flag: ' + flags) : '', attrs ? ('Attr: ' + attrs) : ''].filter(Boolean).join(' · ');
      return `<div class="td-branch-row"><span class="tdb-type tdb-${b.type}">${escapeHtml(b.type)}</span> ${escapeHtml(b.title)}${st ? ' — <span class="tdb-state">' + escapeHtml(st) + '</span>' : ''}</div>`;
    }).join('')}
  </div>` : '';
  box.innerHTML = `<div class="tension-detail-card">
    <div class="td-head">第 ${c.chapter} 章 <span class="td-score">张力 ${c.score}</span>
      <span class="td-target">目标 ${c.target}</span>
      <span class="td-verdict ${dev < -25 ? 'io-out' : (dev > 25 ? 'io-in' : '')}">${verdict}</span></div>
    <div class="td-metrics">
      <div><span>字数</span><b>${c.words}</b></div>
      <div><span>句数</span><b>${c.sentences}</b></div>
      <div><span>对话占比</span><b>${c.dialogRatio}%</b></div>
      <div><span>平均句长</span><b>${c.avgSentenceLen}</b></div>
      <div><span>句长方差</span><b>${c.sentenceVar}</b></div>
      <div><span>动作密度</span><b>${c.actionDensity}</b></div>
      <div><span>转折密度</span><b>${c.turnDensity}</b></div>
      <div><span>情绪极性</span><b>${c.emotion}</b></div>
    </div>
    ${bnHtml}
    ${c.peak ? `<div class="td-peak"><b>高潮片段：</b>${escapeHtml(c.peak)}</div>` : ''}
    ${ex ? `<ul class="td-excerpts">${ex}</ul>` : ''}
  </div>`;
}

/** 导出张力曲线 CSV */
function exportTension() {
  if (!tensionData) { showToast('请先加载节奏分析', 'warn'); return; }
  const esc = v => `"${String(v === undefined || v === null ? '' : v).replace(/"/g, '""')}"`;
  let csv = '章节,张力,目标,偏差,字数,句数,对话占比,平均句长,句长方差,动作密度,转折密度,情绪极性,高潮片段\n';
  (tensionData.curve || []).forEach(c => {
    csv += [c.chapter, c.score, c.target, c.deviation, c.words, c.sentences, c.dialogRatio,
      c.avgSentenceLen, c.sentenceVar, c.actionDensity, c.turnDensity, c.emotion, c.peak]
      .map(esc).join(',') + '\n';
  });
  csv += '\n级别,类型,章节,说明\n';
  (tensionData.warnings || []).forEach(w => {
    csv += [w.level, w.type, w.chapter || '', w.message].map(esc).join(',') + '\n';
  });
  downloadFile('\uFEFF' + csv, `张力曲线_${tensionData.book || 'book'}.csv`, 'text/csv;charset=utf-8');
  showToast('节奏分析已导出 CSV', 'success');
}
