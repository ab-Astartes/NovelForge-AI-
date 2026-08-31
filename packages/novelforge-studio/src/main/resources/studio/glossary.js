/* ===================== 设定集 / 术语表 =====================
   零 LLM 成本：后端从章节正文 + 角色表 + 世界观抽取六类设定实体，
   本面板做类型过滤、搜索、排序、告警展示与设定集导出。 */

const GLOSSARY_TYPES = [
  { key: 'all', label: '全部' },
  { key: 'person', label: '人物' },
  { key: 'skill', label: '功法典籍' },
  { key: 'item', label: '法宝兵器' },
  { key: 'place', label: '地名' },
  { key: 'realm', label: '境界体系' },
  { key: 'term', label: '术语' },
];

let glossaryData = null;
let glossaryType = 'all';
let glossaryQuery = '';
let glossaryOnlyUndef = false;

async function loadGlossary() {
  const book = document.getElementById('glossary-book')?.value?.trim();
  const box = document.getElementById('glossary-body');
  const stats = document.getElementById('glossary-stats');
  if (!box) return;
  if (!book) { box.innerHTML = '<div class="empty-hint">请先在上方选择书目。</div>'; if (stats) stats.textContent = ''; return; }
  box.innerHTML = '<div class="empty-hint">正在抽取设定实体…</div>';
  try {
    const r = await fetch(authUrl(API + '/api/glossary?path=' + encodeURIComponent(book)), { headers: authHeaders() });
    const j = await r.json();
    if (!j.ok) { box.innerHTML = `<div class="empty-hint">抽取失败：${escapeHtml(j.error || '未知错误')}</div>`; return; }
    glossaryData = j;
    renderGlossaryTypes();
    renderGlossary();
    if (stats) {
      const s = j.stats || {};
      stats.textContent = `实体 ${s.total || 0} · 章节 ${s.chapters || 0} · 已登记 ${s.known || 0} · 告警 ${s.warnings || 0}`;
    }
  } catch (e) {
    box.innerHTML = `<div class="empty-hint">请求失败：${escapeHtml(e.message)}</div>`;
  }
}

function renderGlossaryTypes() {
  const bar = document.getElementById('glossary-types');
  if (!bar || !glossaryData) return;
  const counts = (glossaryData.stats && glossaryData.stats.byType) || {};
  bar.innerHTML = GLOSSARY_TYPES.map(t => {
    const n = t.key === 'all' ? (glossaryData.terms || []).length : (counts[t.key] || 0);
    return `<button class="chip ${glossaryType === t.key ? 'chip-active' : ''}" data-gtype="${t.key}">${t.label} ${n}</button>`;
  }).join('');
  bar.querySelectorAll('[data-gtype]').forEach(b => {
    b.onclick = () => { glossaryType = b.dataset.gtype; renderGlossaryTypes(); renderGlossary(); };
  });
}

function renderGlossary() {
  const box = document.getElementById('glossary-body');
  if (!box) return;
  if (!glossaryData) { box.innerHTML = '<div class="empty-hint">暂无数据。</div>'; return; }
  const q = glossaryQuery.trim();
  let terms = (glossaryData.terms || []).filter(t => {
    if (glossaryType !== 'all' && t.type !== glossaryType) return false;
    if (glossaryOnlyUndef && t.defined) return false;
    if (q && !String(t.name).includes(q) && !String(t.definition || '').includes(q)) return false;
    return true;
  });
  if (!terms.length) { box.innerHTML = '<div class="empty-hint">没有符合条件的设定实体。</div>'; return; }

  const warns = (glossaryData.warnings || []).slice(0, 12);
  let html = '';
  if (warns.length) {
    html += '<div class="glossary-warns">';
    warns.forEach(w => {
      const cls = w.level === 'error' ? 'warn-error' : (w.level === 'warn' ? 'warn-warn' : 'warn-info');
      html += `<div class="glossary-warn ${cls}"><span class="warn-tag">${escapeHtml(w.type)}</span>${escapeHtml(w.message)}</div>`;
    });
    html += '</div>';
  }
  html += '<div class="glossary-grid">';
  terms.slice(0, 300).forEach(t => {
    const ex = (t.examples || []).map(e => `<li>${escapeHtml(e)}</li>`).join('');
    html += `<div class="glossary-card">
      <div class="glossary-head">
        <span class="glossary-name">${escapeHtml(t.name)}</span>
        <span class="glossary-type type-${t.type}">${escapeHtml(t.typeLabel || t.type)}</span>
        ${t.defined ? '' : '<span class="glossary-undef" title="高频但未登记设定">未登记</span>'}
      </div>
      <div class="glossary-meta">出现 ${t.count} 次 · 首见第 ${t.firstChapter || '?'} 章</div>
      ${t.definition ? `<div class="glossary-def">${escapeHtml(t.definition)}</div>` : ''}
      ${ex ? `<ul class="glossary-ex">${ex}</ul>` : ''}
    </div>`;
  });
  html += '</div>';
  box.innerHTML = html;
}

function filterGlossary() {
  glossaryQuery = document.getElementById('glossary-search')?.value || '';
  renderGlossary();
}

function toggleGlossaryUndef() {
  glossaryOnlyUndef = !glossaryOnlyUndef;
  const btn = document.getElementById('glossary-undef-toggle');
  if (btn) { btn.classList.toggle('chip-active', glossaryOnlyUndef); btn.textContent = glossaryOnlyUndef ? '✓ 仅未登记' : '仅未登记'; }
  renderGlossary();
}

/** 导出设定集 Markdown */
function exportGlossary() {
  if (!glossaryData) { showToast('请先加载设定集', 'warn'); return; }
  const byType = {};
  (glossaryData.terms || []).forEach(t => { (byType[t.type] = byType[t.type] || []).push(t); });
  let md = `# 设定集 · ${glossaryData.book || ''}\n\n`;
  md += `> 由 NovelForge 自动抽取 · 实体 ${glossaryData.stats?.total || 0} 条 · 章节 ${glossaryData.stats?.chapters || 0} 章\n\n`;
  GLOSSARY_TYPES.filter(t => t.key !== 'all').forEach(t => {
    const list = byType[t.key] || [];
    if (!list.length) return;
    md += `## ${t.label}（${list.length}）\n\n`;
    list.forEach(x => {
      md += `### ${x.name}\n\n- 类型：${x.typeLabel || x.type}\n- 出现：${x.count} 次（首见第 ${x.firstChapter || '?'} 章）\n`;
      md += `- 状态：${x.defined ? '已登记' : '未登记（建议补充设定）'}\n`;
      if (x.definition) md += `- 释义：${x.definition}\n`;
      (x.examples || []).slice(0, 2).forEach(e => { md += `- 例句：${e}\n`; });
      md += '\n';
    });
  });
  const warns = glossaryData.warnings || [];
  if (warns.length) {
    md += `## 一致性告警（${warns.length}）\n\n`;
    warns.forEach(w => { md += `- [${w.level}] ${w.message}\n`; });
  }
  downloadFile(md, `设定集_${glossaryData.book || 'book'}.md`, 'text/markdown;charset=utf-8');
  showToast('设定集已导出', 'success');
}
