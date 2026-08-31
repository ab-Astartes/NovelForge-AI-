// ============ 智能取名面板 ============
// 类型：人物 / 武功招式 / 道具 / 兵器 / 势力 / 坐骑
// 风格：仙侠 / 武侠 / 玄幻 / 古典 / 凶煞 / 雅致

const NAMING_TYPES = [
  { key: 'person', label: '人物', icon: '👤' },
  { key: 'skill', label: '武功招式', icon: '🌀' },
  { key: 'item', label: '道具', icon: '🎁' },
  { key: 'weapon', label: '兵器', icon: '⚔' },
  { key: 'faction', label: '势力', icon: '🏯' },
  { key: 'mount', label: '坐骑', icon: '🐎' },
];

const NAMING_STYLES = [
  { key: 'xianxia', label: '仙侠飘逸' },
  { key: 'wuxia', label: '武侠刚正' },
  { key: 'xuanhuan', label: '玄幻霸气' },
  { key: 'classic', label: '古典文雅' },
  { key: 'fierce', label: '凶煞凌厉' },
  { key: 'elegant', label: '清丽雅致' },
  { key: 'horror', label: '恐怖惊悚' },
  { key: 'scifi', label: '科幻硬核' },
  { key: 'fantasy', label: '奇幻瑰丽' },
  { key: 'mystery', label: '神秘悬疑' },
  { key: 'eerie', label: '诡异怪谈' },
];

let namingType = 'person';
let namingBusy = false;
let namingFavs = new Map();   // name -> {type, name, meaning} 收藏夹（会话内）

function renderNamingPanel() {
  const typesEl = document.getElementById('naming-types');
  const stylesEl = document.getElementById('naming-styles');
  if (!typesEl || !stylesEl) return;
  typesEl.innerHTML = '';
  NAMING_TYPES.forEach(t => {
    const b = document.createElement('button');
    b.className = 'naming-chip' + (t.key === namingType ? ' active' : '');
    b.innerHTML = `<span>${t.icon}</span>${t.label}`;
    b.onclick = () => { namingType = t.key; renderNamingPanel(); };
    typesEl.appendChild(b);
  });
  stylesEl.innerHTML = '';
  NAMING_STYLES.forEach(s => {
    const opt = document.createElement('option');
    opt.value = s.key; opt.textContent = s.label;
    stylesEl.appendChild(opt);
  });
  // 人物专属控件显隐
  const personOnly = document.getElementById('naming-person-only');
  if (personOnly) personOnly.style.display = (namingType === 'person') ? '' : 'none';
}

function doNaming() {
  if (namingBusy) return;
  const style = document.getElementById('naming-styles').value;
  const gender = document.querySelector('input[name="naming-gender"]:checked')?.value || 'male';
  const surname = document.getElementById('naming-surname').value.trim();
  const keyword = document.getElementById('naming-keyword').value.trim();
  const count = document.getElementById('naming-count').value;
  const bookPath = document.getElementById('naming-book') ? document.getElementById('naming-book').value : '';
  const params = new URLSearchParams({ type: namingType, style, gender, count });
  if (surname) params.set('surname', surname);
  if (keyword) params.set('keyword', keyword);
  if (bookPath) params.set('path', bookPath);

  namingBusy = true;
  const btn = document.getElementById('naming-generate');
  const hint = document.getElementById('naming-hint');
  if (btn) { btn.disabled = true; btn.textContent = '生成中…'; }
  if (hint) hint.textContent = '正在生成…';

  fetch(authUrl(API + '/api/naming?' + params.toString()), { headers: authHeaders() })
    .then(r => r.json())
    .then(j => {
      if (!j.ok) { if (hint) hint.textContent = '生成失败：' + (j.error || '未知错误'); return; }
      lastNamingNames = j.names || [];
      renderNamingResults(lastNamingNames);
      if (hint) hint.textContent = `已生成 ${j.generated} 个${typeLabel(j.type)}名称（风格：${styleLabel(j.style)}）`;
    })
    .catch(e => { if (hint) hint.textContent = '请求异常：' + e.message; })
    .finally(() => { namingBusy = false; if (btn) { btn.disabled = false; btn.textContent = '✦ 生成名称'; } });
}

function renderNamingResults(names) {
  const box = document.getElementById('naming-results');
  if (!box) return;
  box.innerHTML = '';
  if (!names.length) { box.innerHTML = '<div class="empty-hint">暂无结果，请调整条件后重试。</div>'; return; }
  names.forEach(n => {
    const fav = namingFavs.has(n.name);
    const card = document.createElement('div');
    card.className = 'naming-card' + (fav ? ' fav' : '');
    card.innerHTML = `
      <div class="naming-name">${escapeHtml(n.name)}</div>
      <div class="naming-meaning">${escapeHtml(n.meaning || '')}</div>
      <div class="naming-card-actions">
        <button class="naming-fav" title="收藏">${fav ? '♥ 已收藏' : '♡ 收藏'}</button>
        <button class="naming-copy" title="复制">复制</button>
      </div>`;
    card.querySelector('.naming-copy').onclick = () => {
      navigator.clipboard?.writeText(n.name);
      const b = card.querySelector('.naming-copy');
      b.textContent = '已复制';
      setTimeout(() => { b.textContent = '复制'; }, 1200);
    };
    card.querySelector('.naming-fav').onclick = () => {
      if (namingFavs.has(n.name)) { namingFavs.delete(n.name); }
      else { namingFavs.set(n.name, { type: namingType, name: n.name, meaning: n.meaning || '' }); }
      renderNamingResults(names);   // 刷新卡片心形状态
      renderNamingFavs();
    };
    box.appendChild(card);
  });
}

// ========== 收藏夹 + 批量落库 ==========
function renderNamingFavs() {
  const box = document.getElementById('naming-favs');
  const cnt = document.getElementById('naming-fav-count');
  if (cnt) cnt.textContent = namingFavs.size;
  if (!box) return;
  if (namingFavs.size === 0) { box.innerHTML = '<div class="card-hint">点击结果卡片上的 ♡ 收藏名称，可在此批量落库。</div>'; return; }
  box.innerHTML = '';
  namingFavs.forEach((v, k) => {
    const row = document.createElement('div');
    row.className = 'fav-row';
    const tlabel = (NAMING_TYPES.find(x => x.key === v.type) || {}).label || v.type;
    row.innerHTML = `<span class="fav-type">${escapeHtml(tlabel)}</span><span class="fav-name">${escapeHtml(v.name)}</span>` +
      `<button class="fav-remove" title="移除">✕</button>`;
    row.querySelector('.fav-remove').onclick = () => { namingFavs.delete(k); renderNamingResults(lastNamingNames); renderNamingFavs(); };
    box.appendChild(row);
  });
}

let lastNamingNames = [];

function saveNamingFavs(target) {
  const bookPath = document.getElementById('naming-book') ? document.getElementById('naming-book').value : '';
  if (!bookPath) { showToast('请先在上方选择书目', 'warning'); return; }
  if (namingFavs.size === 0) { showToast('收藏夹为空', 'warning'); return; }
  if (target === 'characters') {
    const persons = [...namingFavs.values()].filter(v => v.type === 'person');
    if (persons.length === 0) { showToast('收藏夹中没有「人物」类名称可落库', 'warning'); return; }
    const entries = persons.map(v => ({ type: 'person', name: v.name, meaning: v.meaning }));
    fetch(authUrl(API + '/api/naming/save'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, target: 'characters', entries })
    }).then(r => r.json()).then(j => {
      if (j.ok) { showToast(j.message || '已落库角色表', 'success'); persons.forEach(v => namingFavs.delete(v.name)); renderNamingFavs(); }
      else showToast('落库失败：' + (j.error || '未知错误'), 'error');
    }).catch(e => showToast('请求异常：' + e.message, 'error'));
  } else {
    const entries = [...namingFavs.values()].map(v => ({ type: v.type, name: v.name, meaning: v.meaning }));
    fetch(authUrl(API + '/api/naming/save'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, target: 'favorites', entries })
    }).then(r => r.json()).then(j => {
      if (j.ok) showToast(j.message || '已收藏到素材库', 'success');
      else showToast('收藏失败：' + (j.error || '未知错误'), 'error');
    }).catch(e => showToast('请求异常：' + e.message, 'error'));
  }
}

function typeLabel(k) { const t = NAMING_TYPES.find(x => x.key === k); return t ? t.label : k; }
function styleLabel(k) { const s = NAMING_STYLES.find(x => x.key === k); return s ? s.label : k; }
