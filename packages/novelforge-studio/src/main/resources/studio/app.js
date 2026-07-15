// 墨阁 · NovelForge Studio — Frontend

const API = '';  // same origin

// ========== Navigation ==========
function showPanel(name) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-tab').forEach(b => b.classList.remove('active'));
  document.getElementById('panel-' + name).classList.add('active');
  document.getElementById('nav-' + name).classList.add('active');

  // Auto-refresh relevant panels
  if (name === 'books') loadBooks();
  if (name === 'state' || name === 'write') populateBookSelects();
}

// ========== Result Display ==========
function showResult(div, msg, isError) {
  div.textContent = msg;
  div.className = 'result-box show ' + (isError ? 'error' : 'success');
  // Auto-hide after 30s
  setTimeout(() => { div.classList.remove('show'); }, 30000);
}

function clearResult(div) {
  div.className = 'result-box';
  div.textContent = '';
}

// ========== Create Book ==========
async function createBook() {
  const title = document.getElementById('book-title').value.trim();
  const genre = document.getElementById('book-genre').value;
  const author = document.getElementById('book-author').value.trim();
  const resultDiv = document.getElementById('create-result');

  if (!title) { showResult(resultDiv, '请输入书名', true); return; }

  try {
    const res = await fetch(API + '/api/book/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, genre, author })
    });
    const data = await res.json();
    if (data.status === 'created') {
      showResult(resultDiv, `✦ "${title}" 已开卷！路径: ${data.path}`, false);
      document.getElementById('book-title').value = '';
      document.getElementById('book-author').value = '';
      loadBooks();
      populateBookSelects();
    } else {
      showResult(resultDiv, '✗ ' + (data.error || '创建失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}

// ========== Books List ==========
const GENRE_LABELS = {
  'xuanhuan': '玄幻', 'xianxia': '仙侠', 'urban': '都市',
  'horror': '恐怖', 'romance-zh': '言情', 'fantasy': 'Fantasy',
  'thriller': 'Thriller', 'romance-en': 'Romance', 'scifi': 'Sci-Fi', 'mystery': 'Mystery'
};

async function loadBooks() {
  const listDiv = document.getElementById('books-list');
  try {
    const res = await fetch(API + '/api/books');
    const books = await res.json();
    if (books.length === 0) {
      listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">书阁空空，先开卷创作吧</p>';
    } else {
      listDiv.innerHTML = books.map(b => `
        <div class="book-card" onclick="selectBook('${b.path}')">
          <div class="card-title">${b.title}</div>
          <div class="card-genre">${GENRE_LABELS[b.genre] || b.genre}</div>
          <div class="card-meta">
            <span class="card-chapters">${b.chapters} 章</span>
            <span>${b.path}</span>
          </div>
        </div>
      `).join('');
    }
    populateBookSelects(books);
  } catch (e) {
    listDiv.innerHTML = '<p style="color:var(--cinnabar-light)">加载失败: ' + e.message + '</p>';
  }
}

function selectBook(path) {
  // Auto-select in all dropdowns
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    sel.value = path;
  });
  showPanel('write');
}

// ========== Populate Book Selects ==========
async function populateBookSelects(books) {
  if (!books) {
    try {
      const res = await fetch(API + '/api/books');
      books = await res.json();
    } catch (e) { return; }
  }
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    if (!sel) return;
    sel.innerHTML = books.map(b =>
      `<option value="${b.path}">${b.title} · ${GENRE_LABELS[b.genre] || b.genre}</option>`
    ).join('');
  });
}

// ========== Write Chapter ==========
// Pipeline step simulation
const AGENT_ORDER = ['Architect', 'Planner', 'Composer', 'Writer', 'Observer', 'Reflector', 'Normalizer', 'Auditor', 'Reviser'];
const AGENT_DURATION = [3000, 2500, 2000, 8000, 3000, 3500, 2000, 4000, 5000]; // estimated ms per agent

function resetPipelineSteps() {
  document.querySelectorAll('.step-node').forEach(node => {
    node.classList.remove('running', 'completed');
    node.classList.add('pending');
    node.querySelector('.step-bar').style.height = '0';
  });
  document.querySelectorAll('.step-connector').forEach(c => {
    c.classList.remove('active');
  });
}

function markStepRunning(agentName) {
  const node = document.querySelector(`.step-node[data-agent="${agentName}"]`);
  if (node) {
    node.classList.remove('pending');
    node.classList.add('running');
  }
}

function markStepCompleted(agentName) {
  const node = document.querySelector(`.step-node[data-agent="${agentName}"]`);
  if (node) {
    node.classList.remove('running');
    node.classList.add('completed');
  }
  // Activate connector before this node
  const prevConnector = node?.previousElementSibling;
  if (prevConnector && prevConnector.classList.contains('step-connector')) {
    prevConnector.classList.add('active');
  }
}

async function writeChapter() {
  const bookPath = document.getElementById('write-book').value;
  const mode = document.getElementById('write-mode').value;
  const apiKey = document.getElementById('api-key').value.trim();
  const baseUrl = document.getElementById('base-url').value.trim();
  const modelId = document.getElementById('model-id').value.trim();
  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');
  const btnWrite = document.getElementById('btn-write');
  const chapterPreview = document.getElementById('chapter-preview');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (!apiKey) { showResult(resultDiv, '请输入 API Key', true); return; }

  // Reset UI
  clearResult(resultDiv);
  chapterPreview.style.display = 'none';
  btnWrite.disabled = true;
  btnWrite.textContent = '炼章中…';

  // Start pipeline animation
  resetPipelineSteps();

  const agents = mode === 'draft'
    ? AGENT_ORDER.slice(0, 4)  // Architect→Writer for draft
    : AGENT_ORDER;              // Full 9 for next

  // Simulate step progression while waiting for API
  let stepIndex = 0;
  const stepTimer = setInterval(() => {
    if (stepIndex < agents.length) {
      if (stepIndex > 0) markStepCompleted(agents[stepIndex - 1]);
      markStepRunning(agents[stepIndex]);
      progressDiv.innerHTML = `<span class="spinner"></span> ${agents[stepIndex]} 正在炼章…`;
      stepIndex++;
    }
  }, 5000);

  try {
    const res = await fetch(API + '/api/write', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, mode, apiKey, baseUrl, model: modelId })
    });
    const data = await res.json();

    clearInterval(stepTimer);
    progressDiv.textContent = '';

    // Mark all remaining steps as completed
    agents.forEach(a => markStepCompleted(a));

    if (data.status === 'ok') {
      let msg = `✦ 第 ${data.chapterNumber} 章已成！${data.length} 字`;
      if (data.auditScore) {
        const scoreColor = data.auditScore >= 7 ? 'var(--success)' : data.auditScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
        msg += ` · 审阅 <span style="color:${scoreColor}">${data.auditScore.toFixed(1)}</span>/10`;
      }
      showResult(resultDiv, msg, false);

      // Show chapter preview
      await showChapterPreview(bookPath, data.chapterNumber);
    } else {
      // Reset steps on failure
      resetPipelineSteps();
      showResult(resultDiv, '✗ ' + (data.error || '写作失败'), true);
    }
  } catch (e) {
    clearInterval(stepTimer);
    progressDiv.textContent = '';
    resetPipelineSteps();
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btnWrite.disabled = false;
    btnWrite.textContent = '落笔！';
  }
}

async function showChapterPreview(bookPath, chapterNum) {
  const preview = document.getElementById('chapter-preview');
  const textDiv = document.getElementById('chapter-text');
  const statsDiv = document.getElementById('chapter-stats');

  try {
    const infoRes = await fetch(API + `/api/book/info?path=${encodeURIComponent(bookPath)}`);
    const info = await infoRes.json();

    // Load the chapter from books list
    const booksRes = await fetch(API + '/api/books');
    const books = await booksRes.json();

    preview.style.display = 'block';
    textDiv.textContent = `第 ${chapterNum} 章已完成，请通过 CLI 或书库查看完整内容。`;
    statsDiv.innerHTML = `
      <span>章节: ${info.chapters}</span>
      <span>角色数: ${(info.characters || '').split('\n').length}</span>
      <span>悬念: ${(info.hooks || '').split('\n').length}</span>
    `;
  } catch (e) {
    preview.style.display = 'none';
  }
}

// ========== Audit Chapter ==========
async function auditChapter() {
  const bookPath = document.getElementById('audit-book').value;
  const chapterNum = document.getElementById('audit-chapter').value;
  const apiKey = document.getElementById('audit-api-key').value.trim();
  const baseUrl = document.getElementById('audit-base-url').value.trim();
  const modelId = document.getElementById('audit-model').value.trim();
  const progressDiv = document.getElementById('audit-progress');

  if (!bookPath) { return; }
  if (!apiKey) { showResult(document.createElement('div'), '请输入 API Key', true); return; }

  progressDiv.innerHTML = '<span class="spinner"></span> 33维审阅运行中…';

  try {
    const res = await fetch(API + '/api/audit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        path: bookPath,
        chapter: chapterNum ? parseInt(chapterNum) : null,
        apiKey, baseUrl, model: modelId
      })
    });
    const data = await res.json();
    progressDiv.textContent = '';

    if (data.status === 'ok') {
      renderAuditResult(data);
    } else {
      document.getElementById('audit-result-area').style.display = 'none';
      progressDiv.textContent = '✗ ' + (data.error || '审阅失败');
    }
  } catch (e) {
    progressDiv.textContent = '';
    progressDiv.textContent = '✗ 网络错误: ' + e.message;
  }
}

function renderAuditResult(data) {
  const area = document.getElementById('audit-result-area');
  area.style.display = 'block';

  // Score display
  const scoreEl = document.getElementById('audit-total-score');
  const badge = document.getElementById('audit-pass-badge');

  scoreEl.textContent = data.overallScore.toFixed(1);
  scoreEl.style.color = data.overallScore >= 7 ? 'var(--success)' :
                        data.overallScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';

  badge.textContent = data.pass ? '✓ 通过' : '✗ 未通过';
  badge.className = 'pass-badge ' + (data.pass ? 'pass' : 'fail');

  // Dimension grid
  const grid = document.getElementById('audit-dim-grid');
  if (data.dimensionScores) {
    const dims = Object.entries(data.dimensionScores);
    grid.innerHTML = dims.map(([name, score]) => {
      const level = score >= 7 ? 'high' : score >= 5 ? 'medium' : 'low';
      const width = (score / 10 * 100).toFixed(0);
      return `
        <div class="dim-cell ${level}">
          <div class="dim-name">${name}</div>
          <div class="dim-score">${score.toFixed(1)}</div>
          <div class="dim-bar-track">
            <div class="dim-bar-fill" style="width:${width}%"></div>
          </div>
        </div>
      `;
    }).join('');
  }

  // Issues
  const issuesDiv = document.getElementById('audit-issues');
  let html = '';
  if (data.criticalIssues && data.criticalIssues.length > 0) {
    html += `
      <div class="issue-block">
        <div class="issue-title" style="color:var(--cinnabar-light)">⚠ 关键问题</div>
        <ul class="issue-list critical">
          ${data.criticalIssues.map(i => `<li>${i}</li>`).join('')}
        </ul>
      </div>
    `;
  }
  if (data.warnings && data.warnings.length > 0) {
    html += `
      <div class="issue-block">
        <div class="issue-title" style="color:var(--warning)">💡 改进建议</div>
        <ul class="issue-list warning">
          ${data.warnings.map(w => `<li>${w}</li>`).join('')}
        </ul>
      </div>
    `;
  }
  issuesDiv.innerHTML = html;
}

// ========== Load State ==========
async function loadState() {
  const bookPath = document.getElementById('state-book').value;
  const type = document.getElementById('state-type').value;
  const content = document.getElementById('state-content');
  const statsRow = document.getElementById('state-stats');

  if (!bookPath) { content.textContent = '请选择书籍'; return; }

  try {
    // Load state
    const stateRes = await fetch(API + `/api/state?path=${encodeURIComponent(bookPath)}&type=${type}`);
    const stateData = await stateRes.json();
    content.textContent = stateData.summary || '无数据';

    // Load progress stats
    const progRes = await fetch(API + `/api/progress?path=${encodeURIComponent(bookPath)}`);
    const progData = await progRes.json();

    statsRow.style.display = 'grid';
    document.getElementById('stat-chapters').textContent = progData.totalChapters || 0;
    document.getElementById('stat-words').textContent = progData.totalWords || 0;
    document.getElementById('stat-audited').textContent = progData.auditedChapters || 0;
    document.getElementById('stat-passed').textContent = progData.passedChapters || 0;

  } catch (e) {
    content.textContent = '加载失败: ' + e.message;
  }
}

// ========== Export Book ==========
async function exportBook() {
  const bookPath = document.getElementById('export-book').value;
  const format = document.getElementById('export-format').value;
  const resultDiv = document.getElementById('export-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }

  try {
    const res = await fetch(API + '/api/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, format })
    });
    const data = await res.json();

    if (data.status === 'ok') {
      showResult(resultDiv, `✦ 成书！${data.outputPath} (${data.chapters} 章)`, false);
    } else {
      showResult(resultDiv, '✗ ' + (data.error || '导出失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}

// ========== Config ==========
const agentToggles = {};

function toggleAgent(btn) {
  btn.classList.toggle('active');
  const key = btn.dataset.key;
  agentToggles[key] = btn.classList.contains('active');
  // Update pipeline steps visibility
  updatePipelineStepVisibility();
}

function updatePipelineStepVisibility() {
  const toggleMap = {
    'runArchitect': 'Architect',
    'runPlanner': 'Planner',
    'runComposer': 'Composer',
    'runWriter': 'Writer',
    'runObserver': 'Observer',
    'runReflector': 'Reflector',
    'runNormalizer': 'Normalizer',
    'runAuditor': 'Auditor',
    'runReviser': 'Reviser'
  };

  document.querySelectorAll('.step-node').forEach(node => {
    const agent = node.dataset.agent;
    const key = Object.entries(toggleMap).find(([k, v]) => v === agent)?.[0];
    if (key && agentToggles[key] === false) {
      node.style.opacity = '0.2';
      node.style.transform = 'scale(0.8)';
    } else {
      node.style.opacity = '';
      node.style.transform = '';
    }
  });
}

async function saveConfig() {
  const resultDiv = document.getElementById('config-result');
  const body = {
    chapterWordsMin: parseInt(document.getElementById('cfg-min-words').value),
    chapterWordsMax: parseInt(document.getElementById('cfg-max-words').value),
    auditPassThreshold: parseFloat(document.getElementById('cfg-audit-threshold').value),
    maxRevisionPasses: parseInt(document.getElementById('cfg-max-revisions').value)
  };

  // Add agent toggles
  Object.entries(agentToggles).forEach(([key, val]) => {
    body[key] = val;
  });

  try {
    const res = await fetch(API + '/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    showResult(resultDiv, data.status === 'updated' ? '✦ 配置已入炉' : '✗ ' + (data.error || '更新失败'), data.status !== 'updated');
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}

// ========== Load Config ==========
async function loadConfig() {
  try {
    const res = await fetch(API + '/api/config');
    const data = await res.json();

    document.getElementById('cfg-min-words').value = data.chapterWordsMin || 2000;
    document.getElementById('cfg-max-words').value = data.chapterWordsMax || 4000;
    document.getElementById('cfg-audit-threshold').value = data.auditPassThreshold || 7.0;
    document.getElementById('cfg-max-revisions').value = data.maxRevisionPasses || 1;

    // Set toggle states
    const toggleKeys = ['runArchitect', 'runPlanner', 'runComposer', 'runWriter',
                        'runObserver', 'runReflector', 'runNormalizer', 'runAuditor', 'runReviser'];
    toggleKeys.forEach(key => {
      const btn = document.querySelector(`.toggle-btn[data-key="${key}"]`);
      if (btn && data[key] !== undefined) {
        const active = data[key];
        btn.classList.toggle('active', active);
        agentToggles[key] = active;
      }
    });
    updatePipelineStepVisibility();
  } catch (e) {
    // Use defaults
  }
}

// ========== Init ==========
loadBooks();
populateBookSelects();
loadConfig();
resetPipelineSteps();
