/* ===================== 资源与战力账本 =====================
   后端从章节抽取资源收支与境界进阶，本面板展示余额卡、流水表、
   境界曲线与一致性告警，支持导出 CSV。
   注意：面板 id 用 assets（ledger 已被 Truth 状态「台账」面板占用）。 */

let assetsData = null;
let assetsTab = 'res';   // res | power | warns

async function loadAssets() {
  const book = document.getElementById('assets-book')?.value?.trim();
  const box = document.getElementById('assets-body');
  const stats = document.getElementById('assets-stats');
  if (!box) return;
  if (!book) { box.innerHTML = '<div class="empty-hint">请先在上方选择书目。</div>'; if (stats) stats.textContent = ''; return; }
  box.innerHTML = '<div class="empty-hint">正在结算资源账本…</div>';
  try {
    const r = await fetch(authUrl(API + '/api/ledger?path=' + encodeURIComponent(book)), { headers: authHeaders() });
    const j = await r.json();
    if (!j.ok) { box.innerHTML = `<div class="empty-hint">结算失败：${escapeHtml(j.error || '未知错误')}</div>`; return; }
    assetsData = j;
    renderAssetsTabs();
    renderAssets();
    if (stats) {
      const s = j.stats || {};
      stats.textContent = `资源 ${s.resources || 0} 种 · 流水 ${s.entries || 0} 条 · 章节 ${s.chapters || 0} · 告警 ${s.warnings || 0}`;
    }
  } catch (e) {
    box.innerHTML = `<div class="empty-hint">请求失败：${escapeHtml(e.message)}</div>`;
  }
}

function renderAssetsTabs() {
  const bar = document.getElementById('assets-tabs');
  if (!bar || !assetsData) return;
  const s = assetsData.stats || {};
  const tabs = [
    { key: 'res', label: `资源余额 ${(assetsData.resources || []).length}` },
    { key: 'power', label: `境界进阶 ${s.trackedCharacters || 0}` },
    { key: 'warns', label: `告警 ${s.warnings || 0}` },
  ];
  bar.innerHTML = tabs.map(t => `<button class="chip ${assetsTab === t.key ? 'chip-active' : ''}" data-atab="${t.key}">${t.label}</button>`).join('');
  bar.querySelectorAll('[data-atab]').forEach(b => {
    b.onclick = () => { assetsTab = b.dataset.atab; renderAssetsTabs(); renderAssets(); };
  });
}

function renderAssets() {
  const box = document.getElementById('assets-body');
  if (!box || !assetsData) return;
  if (assetsTab === 'res') box.innerHTML = renderAssetsRes();
  else if (assetsTab === 'power') box.innerHTML = renderAssetsPower();
  else box.innerHTML = renderAssetsWarns();
}

function renderAssetsRes() {
  const list = assetsData.resources || [];
  if (!list.length) return '<div class="empty-hint">正文中未识别到资源收支。可尝试「获得三百块灵石」「花费五十枚丹药」这类明确句式，或在 truth/ledger.json 手工记账。</div>';
  let html = '<div class="ledger-cards">';
  list.forEach(r => {
    const bal = Number(r.balance || 0);
    const cls = bal < 0 ? 'bal-neg' : (bal > 0 ? 'bal-pos' : 'bal-zero');
    html += `<div class="ledger-card">
      <div class="ledger-res-name">${escapeHtml(r.name)}</div>
      <div class="ledger-bal ${cls}">${fmtAssetNum(bal)}<span class="ledger-unit">${escapeHtml(r.unit || '')}</span></div>
      <div class="ledger-io"><span class="io-in">收 ${fmtAssetNum(r.totalIn)}</span><span class="io-out">支 ${fmtAssetNum(r.totalOut)}</span></div>
      <div class="ledger-mention">正文提及 ${r.mentions || 0} 次</div>
    </div>`;
  });
  html += '</div>';

  html += '<div class="ledger-table-wrap"><table class="ledger-table"><thead><tr>'
       + '<th>章</th><th>资源</th><th>变动</th><th>余额</th><th>上下文</th></tr></thead><tbody>';
  let rows = 0;
  (assetsData.resources || []).forEach(r => {
    (r.history || []).forEach(h => {
      if (rows++ >= 200) return;
      const d = Number(h.delta || 0);
      html += `<tr>
        <td class="c-ch">${h.chapter}</td>
        <td>${escapeHtml(r.name)}</td>
        <td class="${d >= 0 ? 'io-in' : 'io-out'}">${d >= 0 ? '+' : ''}${fmtAssetNum(d)}</td>
        <td class="${Number(h.balance) < 0 ? 'bal-neg' : ''}">${fmtAssetNum(h.balance)}</td>
        <td class="c-ctx">${escapeHtml(h.context || '')}</td>
      </tr>`;
    });
  });
  html += '</tbody></table></div>';
  return html;
}

function renderAssetsPower() {
  const list = assetsData.power || [];
  if (!list.length) return '<div class="empty-hint">未识别到人物境界进阶（需章节中同时出现已登记角色名与境界词，如「萧尘突破至炼气三层」）。</div>';
  const maxIdx = Math.max(1, ...list.flatMap(p => (p.timeline || []).map(t => Number(t.index || 0))));
  let html = '';
  list.forEach(p => {
    const tl = p.timeline || [];
    html += `<div class="power-row">
      <div class="power-name">${escapeHtml(p.character)}<span class="power-cur">${escapeHtml(p.current || '')}</span></div>
      <div class="power-track">`;
    tl.forEach(t => {
      const h = Math.max(6, Math.round(Number(t.index || 0) / maxIdx * 46));
      html += `<div class="power-pt" style="height:${h}px" title="第${t.chapter}章 ${escapeHtml(t.realm)}"><span>${t.chapter}</span></div>`;
    });
    html += '</div></div>';
  });
  return html;
}

function renderAssetsWarns() {
  const warns = assetsData.warnings || [];
  if (!warns.length) return '<div class="empty-hint">未发现账目异常。</div>';
  return '<div class="glossary-warns">' + warns.map(w => {
    const cls = w.level === 'error' ? 'warn-error' : (w.level === 'warn' ? 'warn-warn' : 'warn-info');
    return `<div class="glossary-warn ${cls}"><span class="warn-tag">${escapeHtml(w.type)}</span>${escapeHtml(w.message)}</div>`;
  }).join('') + '</div>';
}

/** 导出流水 CSV */
function exportAssets() {
  if (!assetsData) { showToast('请先加载账本', 'warn'); return; }
  const esc = v => `"${String(v === undefined || v === null ? '' : v).replace(/"/g, '""')}"`;
  let csv = '章节,资源,单位,变动,余额,来源,上下文\n';
  (assetsData.resources || []).forEach(r => {
    (r.history || []).forEach(h => {
      csv += [h.chapter, r.name, r.unit, h.delta, h.balance, h.manual ? '手工账' : '正文抽取', h.context]
        .map(esc).join(',') + '\n';
    });
  });
  csv += '\n人物,章节,境界\n';
  (assetsData.power || []).forEach(p => {
    (p.timeline || []).forEach(t => { csv += [p.character, t.chapter, t.realm].map(esc).join(',') + '\n'; });
  });
  csv += '\n级别,类型,说明\n';
  (assetsData.warnings || []).forEach(w => { csv += [w.level, w.type, w.message].map(esc).join(',') + '\n'; });
  downloadFile('\uFEFF' + csv, `资源账本_${assetsData.book || 'book'}.csv`, 'text/csv;charset=utf-8');
  showToast('账本已导出 CSV', 'success');
}

function fmtAssetNum(v) {
  const n = Number(v || 0);
  if (Math.abs(n - Math.round(n)) < 1e-6) return String(Math.round(n));
  return n.toFixed(2);
}
