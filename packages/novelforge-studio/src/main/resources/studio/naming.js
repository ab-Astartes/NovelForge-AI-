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
];

let namingType = 'person';
let namingBusy = false;

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

  fetch('/api/naming?' + params.toString())
    .then(r => r.json())
    .then(j => {
      if (!j.ok) { if (hint) hint.textContent = '生成失败：' + (j.error || '未知错误'); return; }
      renderNamingResults(j.names || []);
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
    const card = document.createElement('div');
    card.className = 'naming-card';
    card.innerHTML = `
      <div class="naming-name">${escapeHtml(n.name)}</div>
      <div class="naming-meaning">${escapeHtml(n.meaning || '')}</div>
      <button class="naming-copy" title="复制">复制</button>`;
    card.querySelector('.naming-copy').onclick = () => {
      navigator.clipboard?.writeText(n.name);
      card.querySelector('.naming-copy').textContent = '已复制';
      setTimeout(() => { card.querySelector('.naming-copy').textContent = '复制'; }, 1200);
    };
    box.appendChild(card);
  });
}

function typeLabel(k) { const t = NAMING_TYPES.find(x => x.key === k); return t ? t.label : k; }
function styleLabel(k) { const s = NAMING_STYLES.find(x => x.key === k); return s ? s.label : k; }
