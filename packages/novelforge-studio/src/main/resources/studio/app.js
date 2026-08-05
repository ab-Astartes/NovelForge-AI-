// 墨阁 · NovelForge Studio — Frontend

const API = '';  // same origin

// 🟡-1: Auth token — auto-set on page load from startup message
let AUTH_TOKEN = '';

// 🟢-1: Shared LLM config — single source for all panels
let currentWriteJobId = null;

const sharedConfig = {
  apiKey: '',
  baseUrl: 'https://api.openai.com/v1',
  modelId: 'gpt-4o'
};

function syncConfigToUI() {
  document.querySelectorAll('.shared-api-key').forEach(el => el.value = sharedConfig.apiKey);
  document.querySelectorAll('.shared-base-url').forEach(el => el.value = sharedConfig.baseUrl);
  document.querySelectorAll('.shared-model-id').forEach(el => el.value = sharedConfig.modelId);
}

function syncConfigFromUI(sourceId) {
  sharedConfig.apiKey = document.getElementById(sourceId + '-api-key')?.value?.trim() || sharedConfig.apiKey;
  sharedConfig.baseUrl = document.getElementById(sourceId + '-base-url')?.value?.trim() || sharedConfig.baseUrl;
  sharedConfig.modelId = document.getElementById(sourceId + '-model-id')?.value?.trim() || sharedConfig.modelId;
  syncConfigToUI();
}

// 🟢-2: Safe path encoding for HTML onclick
function safePath(path) {
  return path.replace(/\\/g, '/');
}

function authHeaders() {
  const headers = { 'Content-Type': 'application/json' };  
  if (AUTH_TOKEN) headers['Authorization'] = 'Bearer ' + AUTH_TOKEN;
  return headers;
}

function authUrl(url) {
  if (!AUTH_TOKEN) return url;
  return url + (url.includes('?') ? '&' : '?') + 'token=' + AUTH_TOKEN;
}

// ========== Navigation ==========
function showPanel(name) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-tab').forEach(b => b.classList.remove('active'));
  document.getElementById('panel-' + name).classList.add('active');
  document.getElementById('nav-' + name).classList.add('active');

  // Auto-refresh relevant panels
  if (name === 'books') loadBooks();
  if (name === 'state' || name === 'write') populateBookSelects();
  if (name === 'style') { populateBookSelects(); loadStyle(); }
  if (name === 'characters') { populateBookSelects(); loadCharacters(); }
  if (name === 'hooks') { populateBookSelects(); loadHooks(); }
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
    const res = await fetch(authUrl(API + '/api/book/create'), {
      method: 'POST',
      headers: authHeaders(),
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
    const res = await fetch(authUrl(API + '/api/books'), { headers: authHeaders() });
    const books = await res.json();
    if (books.length === 0) {
      listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">书阁空空，先开卷创作吧</p>';
    } else {
      listDiv.innerHTML = books.map(b => `
        <div class="book-card">
          <div onclick="selectBook('${safePath(b.path)}')" style="cursor:pointer">
            <div class="card-title">${b.title}</div>
            <div class="card-genre">${GENRE_LABELS[b.genre] || b.genre}</div>
            <div class="card-meta">
              <span class="card-chapters">${b.chapters} 章</span>
              <span>${b.path}</span>
            </div>
          </div>
          <button onclick="quickDeleteBook('${safePath(b.path)}', '${b.title}')" class="btn-ghost btn-sm btn-danger-inline" title="删除此书" style="margin-top:8px;width:100%">🗑 焚卷</button>
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
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book', 'delete-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    sel.value = path;
  });
  showPanel('write');
  showBookDetail(path); // Show book detail in bookshelf
}

async function showBookDetail(bookPath) {
  const detailDiv = document.getElementById('book-detail');
  const contentDiv = document.getElementById('book-detail-content');
  try {
    const res = await fetch(authUrl(API + `/api/book/info?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const info = await res.json();
    detailDiv.style.display = 'block';
    // Build summary + chapter list
    let html = `<div style="display:flex;gap:16px;margin-bottom:16px">
      <div class="stat-card"><div class="stat-value">${info.chapters}</div><div class="stat-label">章节</div></div>
      <div class="stat-card"><div class="stat-value">${info.chapterDetails ? info.chapterDetails.reduce((sum, ch) => sum + ch.wordCount, 0) : 0}</div><div class="stat-label">总字数</div></div>
      <div class="stat-card"><div class="stat-value">${info.chapterDetails ? info.chapterDetails.filter(ch => ch.passed).length : 0}</div><div class="stat-label">已通过</div></div>
      <div class="stat-card"><div class="stat-value">${info.chapterDetails ? info.chapterDetails.length > 0 ? (info.chapterDetails.reduce((sum, ch) => sum + (ch.auditScore || 0), 0) / info.chapterDetails.filter(ch => ch.auditScore).length).toFixed(1) : '\u2014' : '\u2014'}</div><div class="stat-label">平均分</div></div>
      <div class="stat-card"><div class="stat-value">${info.referencesCount || 0}</div><div class="stat-label">参考文献</div></div>
      <div class="stat-card"><div class="stat-value">${info.inspirationsCount || 0}</div><div class="stat-label">参照作品</div></div>
    </div>`;
    if (info.outlinePreview) {
      html += `<div style="margin-bottom:12px;padding:8px;border:1px solid #333;border-radius:4px;font-size:13px;color:#999">`;
      html += `<div style="color:#c9a961;font-weight:bold;margin-bottom:4px">大纲预览</div>`;
      html += `${info.outlinePreview}...</div>`;
    }
    if (info.intentPreview) {
      html += `<div style="margin-bottom:12px;padding:8px;border:1px solid #333;border-radius:4px;font-size:13px;color:#999">`;
      html += `<div style="color:#c9a961;font-weight:bold;margin-bottom:4px">写作意图</div>`;
      html += `${info.intentPreview}...</div>`;
    }

    // === 写作素材区块：参考文献 + 参照作品 ===
    html += `<div id="materials-section" style="margin-bottom:12px">`;
    html += `<div style="display:flex;gap:0;margin-bottom:8px;border:1px solid #333;border-radius:4px;overflow:hidden">`;
    html += `<button onclick="loadReferences('${bookPath}')" id="tab-refs" style="flex:1;padding:8px;background:#c0392b;color:#fff;border:none;font-size:14px;cursor:pointer">📚 参考文献 (${info.referencesCount || 0})</button>`;
    html += `<button onclick="loadInspirations('${bookPath}')" id="tab-insps" style="flex:1;padding:8px;background:#2a2a2a;color:#c9a961;border:none;border-left:1px solid #333;font-size:14px;cursor:pointer">📖 参照作品 (${info.inspirationsCount || 0})</button>`;
    html += `</div>`;
    html += `<div id="references-list" style="margin-bottom:8px"></div>`;
    html += `<div id="inspirations-list" style="display:none"></div>`;
    html += `</div>`;

    if (info.chapterDetails && info.chapterDetails.length > 0) {
      html += '<table class="chapter-table" style="width:100%;border-collapse:collapse;font-size:13px"><tr style="border-bottom:2px solid #c0392b"><th>#</th><th>章节</th><th>字数</th><th>审阅分</th></tr>'; 
      for (const ch of info.chapterDetails) {
        const scoreColor = ch.auditScore >= 7 ? 'var(--success)' : ch.auditScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
        const scoreDisplay = ch.auditScore != null ? `<span style="color:${scoreColor}">${ch.auditScore.toFixed(1)}</span>/10` : '—';
        const statusIcon = ch.passed ? '✅' : ch.auditScore != null ? '⚠️' : '—';
        html += `<tr class="clickable" onclick="showPanel('write');showChapterContent('${bookPath}', ${ch.number})" style="cursor:pointer"><td>${ch.number}</td><td>${ch.title}</td><td>${ch.wordCount}</td><td>${statusIcon} ${scoreDisplay}</td></tr>`;
      }
      html += '</table>'; 
    }
    contentDiv.innerHTML = html;
    // 自动加载写作素材
    loadReferences(bookPath);
    loadInspirations(bookPath);
  } catch (e) {
    detailDiv.style.display = 'none';
  }
}

// ========== Populate Book Selects ==========
async function populateBookSelects(books) {
  if (!books) {
    try {
      const res = await fetch(authUrl(API + '/api/books'), { headers: authHeaders() });
      books = await res.json();
    } catch (e) { return; }
  }
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book', 'delete-book', 'progress-book', 'style-book', 'rollback-book', 'characters-book', 'hooks-book', 'synopsis-book', 'outline-editor-book', 'intent-editor-book', 'book-edit-book', 'search-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    if (!sel) return;
    sel.innerHTML = books.map(b =>
      `<option value="${safePath(b.path)}">${b.title} · ${GENRE_LABELS[b.genre] || b.genre}</option>`
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
  const batchCount = mode === 'batch' ? parseInt(document.getElementById('write-batch-count')?.value || '3') : 0;
  // 🟢-1: Read from shared config fields (write panel)
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;

  // Sync back to shared config
  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();
  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');
  const btnWrite = document.getElementById('btn-write');
  const chapterPreview = document.getElementById('chapter-preview');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  // apiKey is optional — if not provided, backend will fallback to studio config

  // Reset UI
  clearResult(resultDiv);
  chapterPreview.style.display = 'none';
  btnWrite.disabled = true;
  btnWrite.textContent = '炼章中…';
  document.getElementById('btn-cancel').style.display = 'inline-block';

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
    const res = await fetch(authUrl(API + '/api/write'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, mode, apiKey, baseUrl, model: modelId, count: batchCount })
    });
    const data = await res.json();

    clearInterval(stepTimer);

    // 🟡-2: Handle async job response
    if (data.jobId) {
      currentWriteJobId = data.jobId;
      streamWriteJob(data.jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
      return;
    }

    // Legacy sync fallback
    progressDiv.textContent = '';
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

async function resumeChapter() {
  const bookPath = document.getElementById('write-book').value;
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;

  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();

  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');
  const btnResume = document.getElementById('btn-resume');
  const btnWrite = document.getElementById('btn-write');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  // apiKey is optional — if not provided, backend will fallback to studio config

  clearResult(resultDiv);
  btnResume.disabled = true;
  btnWrite.disabled = true;
  btnResume.textContent = '续炼中…';
  progressDiv.innerHTML = '<span class="spinner"></span> 从中断处续炼…';

  try {
    const res = await fetch(authUrl(API + '/api/write/resume'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, apiKey, baseUrl, model: modelId })
    });
    const data = await res.json();

    if (data.jobId) {
      const agents = AGENT_ORDER;
      streamWriteJob(data.jobId, agents, progressDiv, resultDiv, btnResume, bookPath);
      return;
    }

    progressDiv.textContent = '';
    if (data.status === 'ok') {
      showResult(resultDiv, `✦ 续炼完成！从 ${data.resumedFrom} 继续`, false);
    } else if (data.error === 'No checkpoint found') {
      showResult(resultDiv, '✗ 未找到中断点，请先正常炼章', true);
    } else {
      showResult(resultDiv, '✗ ' + (data.error || '续炼失败'), true);
    }
  } catch (e) {
    progressDiv.textContent = '';
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btnResume.disabled = false;
    btnResume.textContent = '续笔';
    btnWrite.disabled = false;
  }
}

async function showChapterPreview(bookPath, chapterNum) {
  const preview = document.getElementById('chapter-preview');
  const textDiv = document.getElementById('chapter-text');
  const statsDiv = document.getElementById('chapter-stats');

  try {
    const infoRes = await fetch(authUrl(API + `/api/book/info?path=${encodeURIComponent(bookPath)}`), {
      headers: authHeaders()
    });
    const info = await infoRes.json();

    preview.style.display = 'block';

    // Build chapter details table from chapterDetails array
    let chapterHtml = '';
    if (info.chapterDetails && info.chapterDetails.length > 0) {
      chapterHtml = '<table class="chapter-table"><tr><th>#</th><th>章节</th><th>字数</th><th>审阅分</th></tr>';
      for (const ch of info.chapterDetails) {
        const scoreColor = ch.auditScore >= 7 ? 'var(--success)' : ch.auditScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
        const scoreDisplay = ch.auditScore != null ? `<span style="color:${scoreColor}">${ch.auditScore.toFixed(1)}</span>/10` : '—';
        const statusIcon = ch.passed ? '✅' : ch.auditScore != null ? '⚠️' : '—';
        chapterHtml += `<tr onclick="showChapterContent('${bookPath}', ${ch.number})" class="chapter-row clickable"><td>${ch.number}</td><td>${ch.title}</td><td>${ch.wordCount}</td><td>${statusIcon} ${scoreDisplay}</td></tr>`;
      }
      chapterHtml += '</table>';
    }

    textDiv.innerHTML = chapterHtml || `第 ${chapterNum || info.nextChapter - 1} 章已完成。`;
    statsDiv.innerHTML = `
      <span>总章数: ${info.chapters}</span>
      <span>下一章: ${info.nextChapter}</span>
      <span>角色数: ${(info.characters || '').split('\n').length}</span>
      <span>悬念: ${(info.hooks || '').split('\n').length}</span>
    `;
  } catch (e) {
    preview.style.display = 'none';
  }
}

// Show chapter content from API
let currentChapterInfo = { bookPath: '', chapterNum: 0 }; // For title editing

async function showChapterContent(bookPath, chapterNum) {
  const textDiv = document.getElementById('chapter-text');
  const titleInput = document.getElementById('chapter-title-input');
  try {
    const res = await fetch(authUrl(API + `/api/book/chapter?path=${encodeURIComponent(bookPath)}&chapter=${chapterNum}`), {
      headers: authHeaders()
    });
    if (!res.ok) {
      textDiv.textContent = `加载第 ${chapterNum} 章失败 (${res.status})`;
      return;
    }
    const data = await res.json();
    currentChapterInfo = { bookPath, chapterNum: data.number }; // Store for title edit
    titleInput.value = data.title || `第${data.number}章`; // Populate title edit field
    const content = data.finalText || data.draftText || '(无内容)';
    currentChapterDraftText = data.draftText || ''; // Store draft for toggle
    currentChapterFinalText = data.finalText || ''; // Store final for toggle
    currentChapterViewMode = 'final'; // Default view
    let header = `<strong>第 ${data.number} 章 · ${data.title}</strong> · ${data.wordCount} 字`;
    if (data.audit) header += ` · 审阅: ${data.audit.overallScore.toFixed(1)}/10 ${data.audit.passed ? '✅' : '⚠️'}`;
    if (data.draftText && data.finalText) header += ` · <span id="draft-final-toggle" style="cursor:pointer;color:#c9a961;font-size:0.85em" onclick="toggleDraftFinal()">[切换初稿/终稿]</span>`;
    textDiv.innerHTML = header + '<hr style="border-color:var(--ink-border);margin:8px 0"><div style="white-space:pre-wrap;line-height:1.8">' + content.replace(/</g, '&lt;') + '</div>';
  } catch (e) {
    textDiv.textContent = '加载失败: ' + e.message;
  }
}

function toggleDraftFinal() {
  const textDiv = document.getElementById('chapter-text');
  if (!currentChapterDraftText && !currentChapterFinalText) return;
  currentChapterViewMode = currentChapterViewMode === 'final' ? 'draft' : 'final';
  const text = currentChapterViewMode === 'final' ? (currentChapterFinalText || currentChapterDraftText) : (currentChapterDraftText || currentChapterFinalText);
  const label = currentChapterViewMode === 'final' ? '终稿' : '初稿';
  // Update toggle text
  const toggle = document.getElementById('draft-final-toggle');
  if (toggle) toggle.textContent = `[当前:${label} · 点击切换]`;
  // Update content div only
  const contentDiv = textDiv.querySelector('div[style]');
  if (contentDiv) contentDiv.innerHTML = text.replace(/</g, '&lt;');
}

async function cancelWrite() {
  if (!currentWriteJobId) { alert('当前没有进行中的写作任务'); return; }
  if (!window.confirm('确认取消写作？当前进度将丢失。')) return;
  try {
    const res = await fetch(authUrl(API + '/api/write/cancel'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ jobId: currentWriteJobId })
    });
    const data = await res.json();
    if (data.status === 'cancelled') {
      currentWriteJobId = null;
      document.getElementById('btn-write').disabled = false;
      document.getElementById('btn-write').textContent = '落笔！';
      document.getElementById('btn-cancel').style.display = 'none';
      resetPipelineSteps();
      document.getElementById('write-progress').textContent = '✗ 已取消';
      showResult(document.getElementById('write-result'), '✗ 写作已取消', true);
    } else {
      alert('取消失败: ' + (data.error || '未知错误')); 
    }
  } catch (e) { alert('网络错误: ' + e.message); }
}

function startBatchWrite() {
  // Switch to batch mode and trigger write
  document.getElementById('write-mode').value = 'batch';
  document.getElementById('batch-count-group').style.display = 'block';
  writeChapter();
}

// ========== Outline/Intent Editors + Chapter Title Edit ==========
async function loadOutlineEditor() {
  const bookPath = document.getElementById('outline-editor-book').value;
  const textarea = document.getElementById('outline-editor-content');
  const resultDiv = document.getElementById('outline-editor-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  try {
    const res = await fetch(authUrl(API + `/api/book/outline?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const data = await res.json();
    textarea.value = data.outline || '';;
    showResult(resultDiv, `大纲加载成功 (${data.outline ? data.outline.length : 0} 字)`, false);
  } catch (e) { showResult(resultDiv, '加载失败: ' + e.message, true); }
}

async function saveOutlineEditor() {
  const bookPath = document.getElementById('outline-editor-book').value;
  const outline = document.getElementById('outline-editor-content').value;
  const resultDiv = document.getElementById('outline-editor-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/outline'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, outline })
    });
    const data = await res.json();
    if (data.status === 'saved') { showResult(resultDiv, `大纲已保存 (${data.length} 字)`, false); }
    else { showResult(resultDiv, '保存失败: ' + (data.error || '未知'), true); }
  } catch (e) { showResult(resultDiv, '网络错误: ' + e.message, true); }
}

async function loadIntentEditor() {
  const bookPath = document.getElementById('intent-editor-book').value;
  const textarea = document.getElementById('intent-editor-content');
  const resultDiv = document.getElementById('intent-editor-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  try {
    const res = await fetch(authUrl(API + `/api/book/intent?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const data = await res.json();
    textarea.value = data.intent || '';;
    showResult(resultDiv, `意图加载成功 (${data.intent ? data.intent.length : 0} 字)`, false);
  } catch (e) { showResult(resultDiv, '加载失败: ' + e.message, true); }
}

async function saveIntentEditor() {
  const bookPath = document.getElementById('intent-editor-book').value;
  const intent = document.getElementById('intent-editor-content').value;
  const resultDiv = document.getElementById('intent-editor-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/intent'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, intent })
    });
    const data = await res.json();
    if (data.status === 'saved') { showResult(resultDiv, `意图已保存 (${data.length} 字)`, false); }
    else { showResult(resultDiv, '保存失败: ' + (data.error || '未知'), true); }
  } catch (e) { showResult(resultDiv, '网络错误: ' + e.message, true); }
}

async function saveChapterTitle() {
  const { bookPath, chapterNum } = currentChapterInfo;
  const title = document.getElementById('chapter-title-input').value.trim();
  const preview = document.getElementById('chapter-preview');
  if (!bookPath || !chapterNum) { alert('请先加载章节内容'); return; }
  if (!title) { alert('标题不能为空'); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/chapter-title'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, chapter: chapterNum, title })
    });
    const data = await res.json();
    if (data.status === 'saved') {
      alert(`第 ${chapterNum} 章标题已更新为: ${title}`);
      showChapterContent(bookPath, chapterNum); // Refresh display
    } else {
      alert('保存失败: ' + (data.error || '未知'));
    }
  } catch (e) { alert('网络错误: ' + e.message); }
}

// ========== Chapter Content Edit ==========
function editChapterContent() {
  const textDiv = document.getElementById('chapter-text');
  const textarea = document.getElementById('chapter-edit-textarea');
  const btnEdit = document.getElementById('btn-edit-chapter');
  const btnSave = document.getElementById('btn-save-chapter');
  const btnCancel = document.getElementById('btn-cancel-edit');
  // Extract plain text from rendered HTML
  textarea.value = textDiv.querySelector('div[style]')?.textContent || textDiv.textContent;
  textarea.style.display = 'block';
  textDiv.style.display = 'none';
  btnEdit.style.display = 'none';
  btnSave.style.display = 'inline-block';
  btnCancel.style.display = 'inline-block';
}

function cancelChapterEdit() {
  document.getElementById('chapter-edit-textarea').style.display = 'none';
  document.getElementById('chapter-text').style.display = 'block';
  document.getElementById('btn-edit-chapter').style.display = 'inline-block';
  document.getElementById('btn-save-chapter').style.display = 'none';
  document.getElementById('btn-cancel-edit').style.display = 'none';
}

async function saveChapterContent() {
  const { bookPath, chapterNum } = currentChapterInfo;
  const finalText = document.getElementById('chapter-edit-textarea').value;
  if (!bookPath || !chapterNum) { alert('请先加载章节内容'); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/chapter'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, chapter: chapterNum, finalText })
    });
    const data = await res.json();
    if (data.status === 'saved') {
      alert(`第 ${chapterNum} 章内容已保存 (${data.wordCount} 字)`);
      cancelChapterEdit(); // Switch back to view mode
      await showChapterContent(bookPath, chapterNum); // Refresh display
    } else {
      alert('保存失败: ' + (data.error || '未知'));
    }
  } catch (e) { alert('网络错误: ' + e.message); }
}

// ========== Book Search ==========
async function searchBookContent() {
  const bookPath = document.getElementById('search-book').value;
  const keyword = document.getElementById('search-keyword').value;
  const resultDiv = document.getElementById('search-results');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (!keyword || keyword.trim().length === 0) { showResult(resultDiv, '请输入关键词', true); return; }
  try {
    const res = await fetch(authUrl(API + '/api/search?path=' + encodeURIComponent(bookPath) + '&keyword=' + encodeURIComponent(keyword.trim())), { headers: authHeaders() });
    const data = await res.json();
    if (data.length === 0) {
      showResult(resultDiv, `未找到「${keyword}」`, true);
      return;
    }
    let html = `<div style="margin-bottom:4px;color:#c9a961">找到 ${data.length} 处匹配：</div>`;
    data.forEach(hit => {
      const loc = hit.chapter === 0 ? '大纲' : `第${hit.chapter}章 ${hit.title}`;
      html += `<div style="padding:6px 8px;border-bottom:1px solid #333;cursor:pointer" onclick="showChapterContent('${bookPath}',${hit.chapter})">`;
      html += `<span style="color:#c0392b;font-weight:bold">${loc}</span>`;
      const snippet = hit.snippet ? hit.snippet.replace(new RegExp(`(${keyword.trim().replace(/[.*+?^${}()|[\]\]/g, '\$&')})`, 'gi'), '<span style="color:#c0392b;background:#1a0a0a;padding:0 2px">$1</span>') : '';
      html += `<div style="color:#999;font-size:0.85em;margin-top:2px">…${snippet}…</div></div>`;
    });
    resultDiv.innerHTML = html;
    resultDiv.className = 'result-box show success';
  } catch (e) { showResult(resultDiv, '网络错误: ' + e.message, true); }
}

// ========== Book Property Edit ==========
async function loadBookEdit() {
  const bookPath = document.getElementById('book-edit-book').value;
  const resultDiv = document.getElementById('book-edit-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  try {
    const res = await fetch(authUrl(API + `/api/book/info?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const info = await res.json();
    document.getElementById('book-edit-title').value = info.title || '';
    document.getElementById('book-edit-author').value = info.author || '';
    document.getElementById('book-edit-genre').value = info.genre || 'xuanhuan';
    showResult(resultDiv, `属性加载成功: ${info.title} (${info.genre})`, false);
  } catch (e) { showResult(resultDiv, '加载失败: ' + e.message, true); }
}

async function saveBookEdit() {
  const bookPath = document.getElementById('book-edit-book').value;
  const title = document.getElementById('book-edit-title').value.trim();
  const author = document.getElementById('book-edit-author').value.trim();
  const genre = document.getElementById('book-edit-genre').value;
  const resultDiv = document.getElementById('book-edit-result');
  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (!title) { showResult(resultDiv, '书名不能为空', true); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/edit'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, title, author, genre })
    });
    const data = await res.json();
    if (data.status === 'saved') {
      showResult(resultDiv, `属性已更新: ${data.title} (${data.genre})`, false);
      loadBooks(); // Refresh book list
    } else { showResult(resultDiv, '保存失败: ' + (data.error || '未知'), true); }
  } catch (e) { showResult(resultDiv, '网络错误: ' + e.message, true); }
}

// ========== Audit Chapter ==========
async function auditChapter() {
  const bookPath = document.getElementById('audit-book').value;
  const chapterNum = document.getElementById('audit-chapter').value;
  const apiKey = document.getElementById('audit-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('audit-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('audit-model-id')?.value?.trim() || sharedConfig.modelId;

  // Sync
  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();
  const progressDiv = document.getElementById('audit-progress');

  if (!bookPath) { return; }
  // apiKey is optional — backend will fallback to studio config

  progressDiv.innerHTML = '<span class="spinner"></span> 33维审阅运行中…';

  try {
    const res = await fetch(authUrl(API + '/api/audit'), {
      method: 'POST',
      headers: authHeaders(),
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
    const stateRes = await fetch(authUrl(API + `/api/state?path=${encodeURIComponent(bookPath)}&type=${type}`), {
      headers: authHeaders()
    });
    const stateData = await stateRes.json();
    content.textContent = stateData.summary || '无数据';

    // Load progress stats
    const progRes = await fetch(authUrl(API + `/api/progress?path=${encodeURIComponent(bookPath)}`), {
      headers: authHeaders()
    });
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

// ========== 🟢-4: Delete Book/Chapter ==========
async function quickDeleteBook(bookPath, title) {
  if (!window.confirm(`确认焚卷「${title}」？整部书将永久删除，不可恢复！`)) return;
  try {
    const res = await fetch(authUrl(API + '/api/book/delete'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, type: 'project' })
    });
    const data = await res.json();
    if (data.status === 'deleted') {
      loadBooks();
      populateBookSelects();
    } else {
      alert('删除失败: ' + (data.error || '未知错误'));
    }
  } catch (e) {
    alert('网络错误: ' + e.message);
  }
}

async function deleteBook() {
  const bookPath = document.getElementById('delete-book').value;
  const type = document.getElementById('delete-type').value;
  const resultDiv = document.getElementById('delete-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }

  const confirmMsg = type === 'project' 
    ? '确认焚卷？整部书将永久删除，不可恢复！'
    : '确认删除最后一章？不可恢复！';

  if (!window.confirm(confirmMsg)) { return; }

  try {
    const res = await fetch(authUrl(API + '/api/book/delete'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, type })
    });
    const data = await res.json();

    if (data.status === 'deleted') {
      showResult(resultDiv, `✦ ${type === 'project' ? '整部书已焚卷' : '最后一章已删除'}`, false);
      loadBooks();
      populateBookSelects();
    } else {
      showResult(resultDiv, '✗ ' + (data.error || '删除失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}

// ========== Export Book ==========
async function exportBook() {
  const bookPath = document.getElementById('export-book').value;
  const format = document.getElementById('export-format').value;
  const resultDiv = document.getElementById('export-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }

  try {
    const res = await fetch(authUrl(API + '/api/export'), {
      method: 'POST',
      headers: authHeaders(),
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
    maxRevisionPasses: parseInt(document.getElementById('cfg-max-revisions').value),
    globalDefault: {
      provider: document.getElementById('cfg-global-provider').value,
      model: document.getElementById('cfg-global-model').value,
      baseUrl: document.getElementById('cfg-global-baseurl').value,
      apiKey: document.getElementById('cfg-global-apikey').value
    },
    agentOverrides: {}
  };

  // Add agent toggles
  Object.entries(agentToggles).forEach(([key, val]) => {
    body[key] = val;
  });

  // Collect per-agent API overrides
  const agentNames = ['Architect','Planner','Composer','Writer','Observer','Reflector','Normalizer','Auditor','Reviser'];
  agentNames.forEach(name => {
    const el = document.getElementById('cfg-agent-' + name);
    if (el && el.value && el.value.trim()) {
      const parts = el.value.trim().split('|');
      const override = {};
      if (parts[0]) override.provider = parts[0];
      if (parts[1]) override.model = parts[1];
      if (parts[2]) override.baseUrl = parts[2];
      if (parts[3]) override.apiKey = parts[3];
      body.agentOverrides[name] = override;
    }
  });

  try {
    const res = await fetch(authUrl(API + '/api/config'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (data.status === 'updated') {
      // Sync to sharedConfig so other panels auto-use saved config
      sharedConfig.apiKey = body.globalDefault.apiKey || sharedConfig.apiKey;
      sharedConfig.baseUrl = body.globalDefault.baseUrl || sharedConfig.baseUrl;
      sharedConfig.modelId = body.globalDefault.model || sharedConfig.modelId;
      syncConfigToUI();
    }
    showResult(resultDiv, data.status === 'updated' ? '✦ 配置已入炉' : '✗ ' + (data.error || '更新失败'), data.status !== 'updated');
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}
}

// ========== 🟡-2: SSE Write Streaming + Polling Fallback ==========
function streamWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath) {
  const completedAgents = new Set();

  // Try SSE first — real-time agent progress
  try {
    const evtSource = new EventSource(authUrl(API + `/api/write/stream?jobId=${jobId}`));

    evtSource.addEventListener('batch_chapter_start', (e) => {
      const data = JSON.parse(e.data);
      progressDiv.innerHTML = `<span class="spinner"></span> 批量炼章 ${data.chapter}/${data.total}…`;
    });

    evtSource.addEventListener('batch_chapter_complete', (e) => {
      const data = JSON.parse(e.data);
      progressDiv.innerHTML = `<span style="color:var(--success)">✓ 第 ${data.chapter}/${data.total} 章已成</span>`;
    });

    evtSource.addEventListener('pipeline_start', (e) => {
      progressDiv.innerHTML = '<span class="spinner"></span> 流水线启动…';
    });

    evtSource.addEventListener('agent_start', (e) => {
      const data = JSON.parse(e.data);
      const agent = data.agent;
      // Complete all agents before this one that aren't yet marked
      for (const a of agents) {
        if (completedAgents.has(a)) continue;
        if (a === agent) break;
        markStepCompleted(a);
        completedAgents.add(a);
      }
      markStepRunning(agent);
      progressDiv.innerHTML = `<span class="spinner"></span> ${agent} 炼章中 (${data.step + 1}/${data.total})…`;
    });

    evtSource.addEventListener('agent_complete', (e) => {
      const data = JSON.parse(e.data);
      markStepCompleted(data.agent);
      completedAgents.add(data.agent);
      progressDiv.innerHTML = `<span style="color:var(--success)">✓ ${data.agent}</span> · ${(data.elapsed / 1000).toFixed(1)}s`;
    });

    evtSource.addEventListener('agent_skip', (e) => {
      const data = JSON.parse(e.data);
      completedAgents.add(data.agent);
      progressDiv.innerHTML = `<span style="color:var(--paper-dark)">— ${data.agent} 已跳过</span>`;
    });

    evtSource.addEventListener('agent_fail', (e) => {
      const data = JSON.parse(e.data);
      progressDiv.innerHTML = `<span style="color:var(--cinnabar-light)">✗ ${data.agent} 失败</span>`;
    });

    evtSource.addEventListener('pipeline_complete', (e) => {
      const data = JSON.parse(e.data);
      for (const a of agents) {
        if (!completedAgents.has(a)) { markStepCompleted(a); completedAgents.add(a); }
      }
      const scoreColor = data.score >= 7 ? 'var(--success)' : data.score >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
      progressDiv.innerHTML = `✦ 完成 · ${data.chapters} 章 · <span style="color:${scoreColor}">${data.score.toFixed(1)}</span>/10`;
    });

    evtSource.addEventListener('pipeline_fail', (e) => {
      const data = JSON.parse(e.data);
      resetPipelineSteps();
      document.getElementById('btn-cancel').style.display = 'none';
      currentWriteJobId = null;
      showResult(resultDiv, '✗ 流水线失败: ' + data.error, true);
    });

    evtSource.addEventListener('done', (e) => {
      evtSource.close();
      btnWrite.disabled = false;
      btnWrite.textContent = '落笔！';
      document.getElementById('btn-cancel').style.display = 'none';
      currentWriteJobId = null;
      const data = JSON.parse(e.data);
      if (data.status === 'completed') {
        showResult(resultDiv, '✦ 章已成！', false);
        showChapterPreview(bookPath);
      } else {
        showResult(resultDiv, '✗ 写作失败', true);
      }
    });

    evtSource.onerror = () => {
      evtSource.close();
      // SSE failed → fallback to polling
      pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
    };
  } catch (e) {
    // EventSource not supported → polling fallback
    pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
  }
}

// Polling fallback (used when SSE is unavailable)
async function pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath) {
  let stepIndex = 0;

  const poll = async () => {
    try {
      const res = await fetch(authUrl(API + `/api/write/status?jobId=${jobId}`), { headers: authHeaders() });
      const data = await res.json();

      // Use events array if available for better step tracking
      if (data.events && data.events.length > 0) {
        for (const evt of data.events) {
          // Parse SSE-formatted events
          const lines = evt.split('\n');
          const eventType = lines.find(l => l.startsWith('event:'))?.replace('event: ', '');
          const dataLine = lines.find(l => l.startsWith('data:'))?.replace('data: ', '');
          if (!eventType || !dataLine) continue;
          const evtData = JSON.parse(dataLine);

          if (eventType === 'agent_start') markStepRunning(evtData.agent);
          if (eventType === 'agent_complete') { markStepCompleted(evtData.agent); stepIndex++; }
          if (eventType === 'agent_skip') stepIndex++;
        }
      } else {
        // Fallback: estimate step from progress percentage
        const progressStep = Math.floor(data.progress / 100 * agents.length);
        while (stepIndex < progressStep && stepIndex < agents.length) {
          if (stepIndex > 0) markStepCompleted(agents[stepIndex - 1]);
          markStepRunning(agents[stepIndex]);
          stepIndex++;
        }
      }

      progressDiv.innerHTML = `<span class="spinner"></span> 进度 ${data.progress}% · ${data.elapsedSeconds}s`;

      if (data.status === 'completed') {
        agents.forEach(a => markStepCompleted(a));
        progressDiv.textContent = '';
        showResult(resultDiv, '✦ 章已成！', false);
        btnWrite.disabled = false;
        btnWrite.textContent = '落笔！';
        document.getElementById('btn-cancel').style.display = 'none';
        currentWriteJobId = null;
        showChapterPreview(bookPath);
        return;
      }

      if (data.status === 'failed') {
        resetPipelineSteps();
        progressDiv.textContent = '';
        showResult(resultDiv, '✗ ' + (data.error || '写作失败'), true);
        btnWrite.disabled = false;
        btnWrite.textContent = '落笔！';
        document.getElementById('btn-cancel').style.display = 'none';
        currentWriteJobId = null;
        return;
      }

      if (data.status === 'cancelled') {
        resetPipelineSteps();
        progressDiv.textContent = '';\n        showResult(resultDiv, '✦ 已取消', true);
        btnWrite.disabled = false;
        btnWrite.textContent = '落笔！';
        document.getElementById('btn-cancel').style.display = 'none';
        currentWriteJobId = null;
        return;
      }

      setTimeout(poll, 3000);
    } catch (e) {
      resetPipelineSteps();
      showResult(resultDiv, '✗ 轮询失败: ' + e.message, true);
      btnWrite.disabled = false;
      btnWrite.textContent = '落笔！';
      document.getElementById('btn-cancel').style.display = 'none';
      currentWriteJobId = null;
    }
  };

  poll();
}

// ========== Load Config ==========
async function loadConfig() {
  try {
    const res = await fetch(authUrl(API + '/api/config'), { headers: authHeaders() });
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

    // Load global API config
    if (data.globalDefault) {
      document.getElementById('cfg-global-provider').value = data.globalDefault.provider || 'openai';
      document.getElementById('cfg-global-model').value = data.globalDefault.model || 'gpt-4o';
      document.getElementById('cfg-global-baseurl').value = data.globalDefault.baseUrl || 'https://api.openai.com/v1';
      document.getElementById('cfg-global-apikey').value = data.globalDefault.apiKey || '';
      // Sync to sharedConfig so other panels auto-use saved config
      sharedConfig.apiKey = data.globalDefault.apiKey || '';
      sharedConfig.baseUrl = data.globalDefault.baseUrl || 'https://api.openai.com/v1';
      sharedConfig.modelId = data.globalDefault.model || 'gpt-4o';
      syncConfigToUI();
    }

    // Load active preset
    if (data.activePreset) {
      document.getElementById('cfg-preset').value = data.activePreset;
    }

    // Render per-agent API config rows
    renderAgentApiConfigs(data.agentOverrides || {});

  } catch (e) {
    // Use defaults
  }
}

function renderAgentApiConfigs(overrides) {
  const container = document.getElementById('agent-api-configs');
  if (!container) return;
  const agentNames = ['Architect','Planner','Composer','Writer','Observer','Reflector','Normalizer','Auditor','Reviser'];
  const agentLabels = ['构思','计划','编排','书写','观察','反思','润色','审查','修订'];
  let html = '';
  agentNames.forEach((name, i) => {
    const ov = overrides[name];
    const val = ov ? [ov.provider||'', ov.model||'', ov.baseUrl||'', ov.apiKey||''].join('|').replace(/\|+$/, '') : '';
    html += `<div style="display:flex;gap:6px;align-items:center">
      <span style="min-width:100px;color:rgba(255,255,255,0.5);font-size:12px">${agentLabels[i]} ${name}</span>
      <input type="text" id="cfg-agent-${name}" class="input-field" style="flex:1;font-size:12px" placeholder="provider|model|baseUrl|apiKey（留空跟随全局）" value="${escapeHtml(val)}">
    </div>`;
  });
  container.innerHTML = html;
}

async function applyPreset(presetName) {
  if (!presetName) return;
  try {
    const res = await fetch(authUrl(API + '/api/config/presets'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ action: 'apply', name: presetName })
    });
    const data = await res.json();
    if (data.status === 'preset applied') {
      loadConfig(); // reload everything
    }
  } catch (e) {}
}

async function showSampleConfig() {
  const box = document.getElementById('sample-config-box');
  if (!box) return;
  if (box.style.display !== 'none') { box.style.display = 'none'; return; }
  try {
    const res = await fetch(authUrl(API + '/api/config/sample'), { headers: authHeaders() });
    const data = await res.json();
    let html = '<div style="margin-bottom:6px;color:var(--accent);font-weight:bold">配置参考样例</div>';
    if (data.economy) html += '<div>💰 <b>省钱模式</b>：Writer/Auditor用gpt-4o-mini，其余默认</div>';
    if (data.quality) html += '<div>👑 <b>高质量模式</b>：Writer用claude-3-opus，Auditor用gpt-4o</div>';
    if (data.fast) html += '<div>⚡ <b>快速模式</b>：全部用gpt-4o-mini，建议跳过Observer/Reflector</div>';
    html += '<div style="margin-top:6px;color:rgba(255,255,255,0.3)">点击预设下拉框一键切换，或手动填写Agent独立配置</div>';
    box.innerHTML = html;
    box.style.display = 'block';
  } catch (e) {
    box.innerHTML = '加载失败';
    box.style.display = 'block';
  }
}

// ========== Diff Modal ==========
async function showDiff(chapterNum) {
  const bookPath = document.getElementById('progress-book').value;
  if (!bookPath) return;
  try {
    const res = await fetch(authUrl(API + '/api/diff?path=' + encodeURIComponent(bookPath) + '&chapter=' + chapterNum), { headers: authHeaders() });
    const data = await res.json();
    document.getElementById('diff-title').textContent = '第 ' + chapterNum + ' 章 Diff';
    const container = document.getElementById('diff-content');
    container.innerHTML = ''; // clear
    if (data.diff && data.diff.length > 0) {
      data.diff.forEach(item => {
        const div = document.createElement('div');
        div.style.padding = '8px 12px';
        div.style.borderRadius = '6px';
        div.style.marginBottom = '6px';
        if (item.type === 'kept') {
          div.style.background = 'rgba(46,204,113,0.08)';
          div.style.borderLeft = '3px solid #2ecc71';
          div.innerHTML = '<span style="color:#2ecc71;font-size:11px">✓ 保留</span><br>' + escapeHtml(item.text);
        } else if (item.type === 'added') {
          div.style.background = 'rgba(192,57,43,0.12)';
          div.style.borderLeft = '3px solid #c0392b';
          div.innerHTML = '<span style="color:#c0392b;font-size:11px">+ 新增</span><br>' + escapeHtml(item.text);
        } else if (item.type === 'moved') {
          div.style.background = 'rgba(241,196,15,0.08)';
          div.style.borderLeft = '3px solid #f1c40f';
          div.innerHTML = '<span style="color:#f1c40f;font-size:11px">↹ 移动</span><br>' + escapeHtml(item.text);
        } else if (item.type === 'removed') {
          div.style.background = 'rgba(149,165,166,0.12)';
          div.style.borderLeft = '3px solid #95a5a6';
          div.innerHTML = '<span style="color:#95a5a6;font-size:11px">- 删除</span><br>' + escapeHtml(item.text);
        }
        container.appendChild(div);
      });
    } else {
      container.innerHTML = '<p style="color:#8b7355;text-align:center">暂无 diff 数据</p>'; 
    }
    document.getElementById('diff-modal').style.display = ''; // show
  } catch (e) {
    console.error('showDiff error:', e);
  }
}
function closeDiffModal() {
  document.getElementById('diff-modal').style.display = 'none';
}
function escapeHtml(text) {
  if (!text) return '';  
  return text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ========== Style Panel ==========
async function loadStyle() {
  const bookPath = document.getElementById('style-book').value;
  if (!bookPath) return;
  try {
    const res = await fetch(authUrl(API + '/api/style?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const data = await res.json();
    document.getElementById('style-name').value = data.name || '';
    document.getElementById('style-desc').value = data.description || '';
    document.getElementById('style-vocabulary').value = data.vocabularyPattern || '';
    document.getElementById('style-sentence').value = data.sentenceStructure || '';
    document.getElementById('style-pacing').value = data.pacingPattern || '';
    document.getElementById('style-dialogue').value = data.dialogueStyle || '';
    document.getElementById('style-description').value = data.descriptionStyle || '';
    document.getElementById('style-sample').value = data.referenceSample || '';
  } catch (e) {
    console.error('loadStyle error:', e);
  }
}
async function saveStyle() {
  const bookPath = document.getElementById('style-book').value;
  const resultDiv = document.getElementById('style-result');
  if (!bookPath) {
    showResult(resultDiv, '请先选择项目', true);
    return;
  }
  const body = {
    name: document.getElementById('style-name').value,
    description: document.getElementById('style-desc').value,
    vocabularyPattern: document.getElementById('style-vocabulary').value,
    sentenceStructure: document.getElementById('style-sentence').value,
    pacingPattern: document.getElementById('style-pacing').value,
    dialogueStyle: document.getElementById('style-dialogue').value,
    descriptionStyle: document.getElementById('style-description').value,
    referenceSample: document.getElementById('style-sample').value
  };  
  try {
    const res = await fetch(authUrl(API + '/api/style?path=' + encodeURIComponent(bookPath)), {
      method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (data.success) {
      showResult(resultDiv, '✓ 风格保存成功，将在下次写作时生效', false);
    } else {
      showResult(resultDiv, '✗ ' + (data.error || '保存失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}

// ========== Progress Panel ==========
async function loadProgress() {
  const bookPath = document.getElementById('progress-book').value;
  if (!bookPath) return;
  try {
    const res = await fetch(authUrl(API + '/api/progress?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const data = await res.json();
    document.getElementById('progress-summary').style.display = '';
    document.getElementById('stat-chapters').querySelector('.stat-value').textContent = data.totalChapters || 0;
    document.getElementById('stat-words').querySelector('.stat-value').textContent = (data.totalWords || 0).toLocaleString();
    document.getElementById('stat-score').querySelector('.stat-value').textContent = (data.averageAuditScore || 0).toFixed(1);
    const secs = Math.round((data.totalPipelineTimeMs || 0) / 1000);
    document.getElementById('stat-time').querySelector('.stat-value').textContent = secs > 60 ? Math.floor(secs/60) + 'm' + (secs%60) + 's' : secs + 's';

    const tbody = document.getElementById('progress-tbody');
    tbody.innerHTML = '';
    if (data.chapters && data.chapters.length > 0) {
      data.chapters.forEach(ch => {
        const tr = document.createElement('tr');
        tr.style.borderBottom = '1px solid rgba(192,57,43,0.15)';
        const cs = Math.round(ch.pipelineTimeMs / 1000);
        tr.innerHTML = `<td style="padding:6px">${ch.chapterNumber}</td>
          <td style="padding:6px">${ch.chapterTitle || ''}</td>
          <td style="padding:6px;text-align:right">${ch.wordCount.toLocaleString()}</td>
          <td style="padding:6px;text-align:center">${ch.audited ? (ch.passed ? '✅' : '⚠️') : '—'}</td>
          <td style="padding:6px;text-align:right">${ch.auditScore.toFixed(1)}</td>
          <td style="padding:6px;text-align:right">${cs > 60 ? Math.floor(cs/60)+'m'+(cs%60)+'s' : cs+'s'}</td>
          <td style="padding:6px;text-align:center"><button onclick="showDiff(${ch.chapterNumber})" style="background:none;border:1px solid #c0392b;color:#c0392b;padding:2px 8px;border-radius:4px;font-size:12px">Diff</button></td>`;
        tbody.appendChild(tr);
      });
    } else {
      // No stored progress — show basic stats from chapters count
      tbody.innerHTML = '<tr><td colspan="6" style="padding:12px;color:#8b7355;text-align:center">暂无详细进度数据（需要通过写作功能生成）</td></tr>';
    }
  } catch (e) {
    console.error('loadProgress error:', e);
  }
}

// ========== Init ==========

// ========== Prompt-driven Outline Generation ==========
async function generateOutlineFromPrompt() {
  const bookPath = document.getElementById('write-book')?.value;
  const prompt = document.getElementById('outline-gen-prompt')?.value?.trim();
  const genre = document.getElementById('outline-gen-genre')?.value || 'xuanhuan';
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  const resultDiv = document.getElementById('outline-gen-result');

  if (!prompt) { showResult(resultDiv, '请输入创意提示词', true); return; }
  // apiKey is optional — backend will fallback to studio config

  // Sync config
  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();

  const btn = document.getElementById('btn-outline-gen');
  btn.disabled = true; btn.textContent = '生成中...';

  try {
    const body = { prompt, genre, apiKey, baseUrl, model: modelId };
    if (bookPath) body.path = bookPath;
    const res = await fetch(authUrl(API + '/api/outline/generate'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, '✅ 大纲已生成\n\n' + (data.outline || '').substring(0, 2000), false);
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '生成失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '✦ 生成大纲';
  }
}

// ========== Volume Outline Generation ==========
async function generateVolumeOutline() {
  const bookPath = document.getElementById('write-book')?.value;
  const prompt = document.getElementById('volume-gen-prompt')?.value?.trim() || ''; // optional
  const genre = document.getElementById('volume-gen-genre')?.value || 'xuanhuan';
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  const resultDiv = document.getElementById('volume-gen-result');

  // apiKey is optional — backend will fallback to studio config
  if (!bookPath) { showResult(resultDiv, '请选择书籍（需有大纲）', true); return; }

  // Sync config
  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();

  const btn = document.getElementById('btn-volume-gen');
  btn.disabled = true; btn.textContent = '生成中...';

  try {
    const res = await fetch(authUrl(API + '/api/volume/generate'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, prompt, genre, apiKey, baseUrl, model: modelId })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, '✅ 卷纲已生成\n\n' + (data.volumeOutline || '').substring(0, 2000), false);
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '生成失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '✦ 生成卷纲';
  }
}

// ========== Chapter Revision ==========
async function reviseChapter() {
  const bookPath = document.getElementById('write-book')?.value;
  const chapterNum = parseInt(document.getElementById('revise-chapter-num')?.value || '0');
  const prompt = document.getElementById('revise-prompt')?.value?.trim();
  const source = document.getElementById('revise-source')?.value || 'outline';
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  const resultDiv = document.getElementById('revise-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (chapterNum < 1) { showResult(resultDiv, '请输入有效章节号（>=1）', true); return; }
  if (!prompt) { showResult(resultDiv, '请输入修改提示词', true); return; }
  // apiKey is optional — backend will fallback to studio config

  // Sync config
  sharedConfig.apiKey = apiKey;
  sharedConfig.baseUrl = baseUrl;
  sharedConfig.modelId = modelId;
  syncConfigToUI();

  const btn = document.getElementById('btn-revise');
  btn.disabled = true; btn.textContent = '修改中...';

  try {
    const res = await fetch(authUrl(API + '/api/chapter/revise'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, chapter: chapterNum, prompt, source, apiKey, baseUrl, model: modelId })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, `✅ 第 ${data.chapter} 章已修改完成`, false);
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '修改失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '✦ 修改章节';
  }
}

// ========== Characters CRUD ==========
async function loadCharacters() {
  const bookPath = document.getElementById('characters-book')?.value;
  const listDiv = document.getElementById('characters-list');
  if (!bookPath) {
    listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">请选择项目</p>'; 
    return;
  }
  try {
    const res = await fetch(authUrl(API + `/api/characters?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const chars = await res.json();
    if (!chars || chars.length === 0) {
      listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">暂无角色数据</p>'; 
    } else {
      listDiv.innerHTML = chars.map(c => {
        const name = c.name || '未命名';
        const role = c.role || ''; 
        const power = c.powerLevel || ''; 
        const loc = c.location || ''; 
        return `<div class="ledger-item" onclick="editCharacter('${name}')">
          <div class="ledger-name">${name}</div>
          <div class="ledger-tags">
            ${role ? '<span class="ledger-tag">' + role + '</span>' : ''}
            ${power ? '<span class="ledger-tag">等级: ' + power + '</span>' : ''}
            ${loc ? '<span class="ledger-tag">位置: ' + loc + '</span>' : ''}
          </div>
        </div>`;
      }).join('');
    }
  } catch (e) {
    listDiv.innerHTML = '<p style="color:var(--cinnabar-light);text-align:center">加载失败: ' + e.message + '</p>'; 
  }
}

function editCharacter(name) {
  // Fill the edit form with character data
  document.getElementById('char-edit-name').value = name;
  // Load current character data from API
  const bookPath = document.getElementById('characters-book')?.value;
  if (!bookPath) return;
  fetch(authUrl(API + `/api/characters?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() })
    .then(r => r.json())
    .then(chars => {
      const char = chars.find(c => c.name === name);
      if (char) {
        document.getElementById('char-edit-role').value = char.role || '主角';
        document.getElementById('char-edit-power').value = char.powerLevel || ''; 
        document.getElementById('char-edit-location').value = char.location || ''; 
      }
    })
    .catch(() => {});
}

async function saveCharacter() {
  const bookPath = document.getElementById('characters-book')?.value;
  const name = document.getElementById('char-edit-name')?.value?.trim();
  const role = document.getElementById('char-edit-role')?.value;
  const powerLevel = document.getElementById('char-edit-power')?.value?.trim(); 
  const location = document.getElementById('char-edit-location')?.value?.trim(); 
  const resultDiv = document.getElementById('characters-result');

  if (!bookPath) { showResult(resultDiv, '请选择项目', true); return; }
  if (!name) { showResult(resultDiv, '请输入角色名', true); return; }

  try {
    const res = await fetch(authUrl(API + `/api/characters?path=${encodeURIComponent(bookPath)}`), {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ name, role, powerLevel, location })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, `✅ 角色 "${name}" 已保存`, false);
      loadCharacters();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '保存失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

async function deleteCharacter() {
  const bookPath = document.getElementById('characters-book')?.value;
  const name = document.getElementById('char-edit-name')?.value?.trim();
  const resultDiv = document.getElementById('characters-result');

  if (!bookPath) { showResult(resultDiv, '请选择项目', true); return; }
  if (!name) { showResult(resultDiv, '请输入角色名', true); return; }
  if (!window.confirm(`确认删除角色 "${name}"？`)) return;

  try {
    const res = await fetch(authUrl(API + `/api/characters?path=${encodeURIComponent(bookPath)}`), {
      method: 'DELETE',
      headers: authHeaders(),
      body: JSON.stringify({ name })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, `✅ 角色 "${name}" 已删除`, false);
      document.getElementById('char-edit-name').value = ''; 
      document.getElementById('char-edit-power').value = ''; 
      document.getElementById('char-edit-location').value = ''; 
      loadCharacters();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '删除失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// ========== Hooks CRUD ==========
async function loadHooks() {
  const bookPath = document.getElementById('hooks-book')?.value;
  const listDiv = document.getElementById('hooks-list');
  if (!bookPath) {
    listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">请选择项目</p>'; 
    return;
  }
  try {
    const res = await fetch(authUrl(API + `/api/hooks?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const hooks = await res.json();
    if (!hooks || hooks.length === 0) {
      listDiv.innerHTML = '<p style="color:var(--paper-dark);text-align:center;padding:24px">暂无伏笔数据</p>'; 
    } else {
      listDiv.innerHTML = hooks.map(h => {
        const id = h.id || '未命名';
        const priority = h.priority || 'medium'; 
        const desc = h.description || ''; 
        const priorityLabel = priority === 'high' ? '🔴 高' : priority === 'medium' ? '🟡 中' : '🟢 低';
        return `<div class="ledger-item" onclick="editHook('${id}')">
          <div class="ledger-name">${id}</div>
          <div class="ledger-tags">
            <span class="ledger-tag">${priorityLabel}</span>
            ${desc ? '<span class="ledger-tag">' + desc.substring(0, 60) + '</span>' : ''}
          </div>
        </div>`;
      }).join('');
    }
  } catch (e) {
    listDiv.innerHTML = '<p style="color:var(--cinnabar-light);text-align:center">加载失败: ' + e.message + '</p>'; 
  }
}

function editHook(hookId) {
  // Fill the edit form with hook data
  document.getElementById('hook-edit-id').value = hookId;
  // Load current hook data from API
  const bookPath = document.getElementById('hooks-book')?.value;
  if (!bookPath) return;
  fetch(authUrl(API + `/api/hooks?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() })
    .then(r => r.json())
    .then(hooks => {
      const hook = hooks.find(h => h.id === hookId);
      if (hook) {
        document.getElementById('hook-edit-desc').value = hook.description || ''; 
        document.getElementById('hook-edit-priority').value = hook.priority || 'medium';
      }
    })
    .catch(() => {});
}

async function updateHook() {
  const bookPath = document.getElementById('hooks-book')?.value;
  const hookId = document.getElementById('hook-edit-id')?.value?.trim();
  const description = document.getElementById('hook-edit-desc')?.value?.trim(); 
  const priority = document.getElementById('hook-edit-priority')?.value;
  const resultDiv = document.getElementById('hooks-result');

  if (!bookPath) { showResult(resultDiv, '请选择项目', true); return; }
  if (!hookId) { showResult(resultDiv, '请输入伏笔ID', true); return; }

  try {
    const res = await fetch(authUrl(API + `/api/hooks?path=${encodeURIComponent(bookPath)}`), {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ id: hookId, description, priority })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, `✅ 伏笔 "${hookId}" 已更新`, false);
      loadHooks();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '更新失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

async function deleteHook() {
  const bookPath = document.getElementById('hooks-book')?.value;
  const hookId = document.getElementById('hook-edit-id')?.value?.trim();
  const resultDiv = document.getElementById('hooks-result');

  if (!bookPath) { showResult(resultDiv, '请选择项目', true); return; }
  if (!hookId) { showResult(resultDiv, '请输入伏笔ID', true); return; }
  if (!window.confirm(`确认删除伏笔 "${hookId}"？`)) return;

  try {
    const res = await fetch(authUrl(API + `/api/hooks?path=${encodeURIComponent(bookPath)}`), {
      method: 'DELETE',
      headers: authHeaders(),
      body: JSON.stringify({ id: hookId })
    });
    const data = await res.json();
    if (data.status === 'ok') {
      showResult(resultDiv, `✅ 伏笔 "${hookId}" 已删除`, false);
      document.getElementById('hook-edit-id').value = ''; 
      document.getElementById('hook-edit-desc').value = ''; 
      loadHooks();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '删除失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// ========== 大纲梗概生成 ==========
async function generateOutlineSynopsis() {
  const bookPath = document.getElementById('write-book')?.value;
  if (!bookPath) { showResult(document.getElementById('write-result'), '请先选择书籍', true); return; }
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  // apiKey is optional — backend will fallback to studio config

  const btn = document.getElementById('btn-outline');
  btn.disabled = true; btn.textContent = '生成中...';
  clearResult(document.getElementById('write-result'));

  try {
    const resp = await fetch(authUrl(API + '/api/outline/synopsis'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, apiKey: apiKey, baseUrl: baseUrl, model: modelId })
    });
    const data = await resp.json();
    if (data.status === 'ok') {
      showResult(document.getElementById('write-result'), '✅ 大纲梗概已生成并保存\n\n' + data.outline, false);
    } else {
      showResult(document.getElementById('write-result'), '❌ ' + (data.error || '生成失败'), true);
    }
  } catch (e) {
    showResult(document.getElementById('write-result'), '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '大纲梗概';
  }
}

// ========== 卷纲梗概生成 ==========
async function generateVolumeSynopsis() {
  const bookPath = document.getElementById('write-book')?.value;
  if (!bookPath) { showResult(document.getElementById('write-result'), '请先选择书籍', true); return; }
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  // apiKey is optional — backend will fallback to studio config

  // Get chapter range from book info
  let volumeStart = 1, volumeEnd = 10;
  try {
    const infoResp = await fetch(authUrl(API + '/api/book/info?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    if (infoResp.ok) {
      const info = await infoResp.json();
      const chapterCount = info.chapters?.length || 0;
      if (chapterCount > 0) volumeEnd = chapterCount;
    }
  } catch (e) { /* use defaults */ }

  const btn = document.getElementById('btn-volume');
  btn.disabled = true; btn.textContent = '生成中...';
  clearResult(document.getElementById('write-result'));

  try {
    const resp = await fetch(authUrl(API + '/api/volume/synopsis'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, apiKey: apiKey, baseUrl: baseUrl, model: modelId, volumeStart, volumeEnd })
    });
    const data = await resp.json();
    if (data.status === 'ok') {
      showResult(document.getElementById('write-result'),
        `✅ 卷纲梗概已生成（第${data.volumeStart}章~第${data.volumeEnd}章）\n\n` + data.synopsis, false);
    } else {
      showResult(document.getElementById('write-result'), '❌ ' + (data.error || '生成失败'), true);
    }
  } catch (e) {
    showResult(document.getElementById('write-result'), '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '卷纲梗概';
  }
}

// ========== AI痕迹检测去除 ==========
async function detectAiTrace() {
  const bookPath = document.getElementById('write-book')?.value;
  if (!bookPath) { showResult(document.getElementById('write-result'), '请先选择书籍', true); return; }
  const apiKey = document.getElementById('write-api-key')?.value?.trim() || sharedConfig.apiKey;
  const baseUrl = document.getElementById('write-base-url')?.value?.trim() || sharedConfig.baseUrl;
  const modelId = document.getElementById('write-model-id')?.value?.trim() || sharedConfig.modelId;
  // apiKey is optional — backend will fallback to studio config

  const btn = document.getElementById('btn-ai-trace');
  btn.disabled = true; btn.textContent = '检测中...';
  clearResult(document.getElementById('write-result'));

  try {
    const resp = await fetch(authUrl(API + '/api/ai-trace'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, apiKey: apiKey, baseUrl: baseUrl, model: modelId })
    });
    const data = await resp.json();
    if (data.status === 'ok') {
      showResult(document.getElementById('write-result'),
        `✅ 第${data.chapter}章 AI痕迹检测完成\n\n` + data.analysis, false);
    } else {
      showResult(document.getElementById('write-result'), '❌ ' + (data.error || '检测失败'), true);
    }
  } catch (e) {
    showResult(document.getElementById('write-result'), '❌ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '去AI痕';
  }
}

async function fetchVersion() {
  try {
    const resp = await fetch(authUrl(API + '/api/version'), { headers: authHeaders() });
    if (resp.ok) {
      const data = await resp.json();
      const label = document.getElementById('version-label');
      if (label) label.textContent = data.full || data.version;
      const footer = document.getElementById('footer-version');
      if (footer) footer.textContent = data.full || data.version;
    }
  } catch (e) { /* silent */ }
}

// Init on page load
fetchVersion();
loadBooks();
populateBookSelects();
loadConfig();
resetPipelineSteps();

// Mode change: show/hide batch count field
document.getElementById('write-mode').addEventListener('change', function() {
  document.getElementById('batch-count-group').style.display = this.value === 'batch' ? '' : 'none';
});

// Auto-sync shared config on any input change
document.querySelectorAll('.shared-api-key, .shared-base-url, .shared-model-id').forEach(el => {
  el.addEventListener('input', () => {
    if (el.classList.contains('shared-api-key')) sharedConfig.apiKey = el.value.trim();
    if (el.classList.contains('shared-base-url')) sharedConfig.baseUrl = el.value.trim();
    if (el.classList.contains('shared-model-id')) sharedConfig.modelId = el.value.trim();
    syncConfigToUI();
  });
});

// ========== Chapter Synopsis Generation ========== 
async function generateChapterSynopsis() {
  const resultDiv = document.getElementById('synopsis-result');
  const sourceType = document.getElementById('synopsis-source-type').value;
  const bookPath = document.getElementById('synopsis-book').value;
  const sourceText = document.getElementById('synopsis-source').value.trim();
  const prompt = document.getElementById('synopsis-prompt').value.trim();
  const genre = document.getElementById('synopsis-genre').value;

  if (!sourceText && !bookPath) {
    showResult(resultDiv, '请输入大纲/卷纲内容或选择书籍', true);
    return;
  }

  const apiKey = document.getElementById('write-api-key').value.trim();
  const baseUrl = document.getElementById('write-base-url').value.trim() || 'https://api.openai.com/v1';
  const modelId = document.getElementById('write-model-id').value.trim() || 'gpt-4o';

  // apiKey is optional — backend will fallback to studio config
  }

  showResult(resultDiv, '⏳ 正在生成章节梗概...', false);
  const btn = document.getElementById('btn-synopsis');
  btn.disabled = true;

  try {
    const body = {
      source: sourceText || undefined,
      prompt: prompt || '',
      genre: genre,
      apiKey: apiKey,
      baseUrl: baseUrl,
      model: modelId
    };
    // If book selected, send path for auto-load + save
    if (bookPath) body.path = bookPath;

    const resp = await fetch(authUrl(API + '/api/chapter/synopsis'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(body)
    });
    const data = await resp.json();

    if (data.status === 'ok') {
      showResult(resultDiv, '✦ 章节梗概已生成\n\n' + data.synopsis, false);
    } else {
      showResult(resultDiv, '✗ 生成失败: ' + (data.error || '未知错误'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false;
  }
}

// ========== Rollback ========== 
async function listBackups() {
  const bookPath = document.getElementById('rollback-book').value;
  if (!bookPath) { showResult(document.getElementById('rollback-result'), '请选择项目', true); return; }
  try {
    const res = await fetch(authUrl(API + '/api/rollback?path=' + encodeURIComponent(bookPath) + '&action=list'), { headers: authHeaders() });
    const data = await res.json();
    const listDiv = document.getElementById('rollback-list');
    if (data.backups && data.backups.length > 0) {
      let html = '<div style="margin-bottom:8px;color:#8b7355;font-size:13px">共 ' + data.backups.length + ' 个备份版本：</div>';
      html += '<div style="display:flex;flex-direction:column;gap:8px">';
      data.backups.forEach(b => {
        html += '<div style="display:flex;align-items:center;gap:8px;padding:6px 12px;background:rgba(192,57,43,0.06);border-radius:6px">';
        html += '<span style="color:#c0392b;font-family:monospace;font-size:12px">' + b.display + '</span>';
        html += '<button onclick="rollbackState(' + b.timestamp + ')" style="background:none;border:1px solid #c0392b;color:#c0392b;padding:2px 8px;border-radius:4px;font-size:12px;margin-left:auto">回滚至此</button>';
        html += '</div>';
      });
      html += '</div>';
      listDiv.innerHTML = html;
    } else {
      listDiv.innerHTML = '<div style="color:#8b7355;padding:12px;text-align:center">暂无备份</div>';
    }
    clearResult(document.getElementById('rollback-result'));
  } catch (e) {
    showResult(document.getElementById('rollback-result'), '获取备份失败: ' + e.message, true);
  }
}

async function rollbackState(timestamp) {
  const bookPath = document.getElementById('rollback-book').value;
  if (!bookPath) { showResult(document.getElementById('rollback-result'), '请选择项目', true); return; }
  let url = API + '/api/rollback?path=' + encodeURIComponent(bookPath) + '&action=rollback';
  if (timestamp) url += '&timestamp=' + timestamp;
  try {
    const res = await fetch(authUrl(url), { headers: authHeaders() });
    const data = await res.json();
    if (data.success === 'true' || data.success === true) {
      showResult(document.getElementById('rollback-result'), '回滚成功！世界观已恢复到备份版本', false);
      listBackups(); // refresh backup list
    } else {
      showResult(document.getElementById('rollback-result'), '回滚失败', true);
    }
  } catch (e) {
    showResult(document.getElementById('rollback-result'), '回滚失败: ' + e.message, true);
  }
}

// ========== 写作素材：参考文献 & 参照作品 ==========
const REF_TYPE_LABELS = { book: '书籍', paper: '论文', web: '网页', article: '文章', film: '影视', game: '游戏', other: '其他' };

async function loadReferences(bookPath) {
  // 标签切换：显示参考文献，隐藏参照作品
  document.getElementById('references-list').style.display = 'block';
  document.getElementById('inspirations-list').style.display = 'none';
  document.getElementById('tab-refs').style.background = '#c0392b';
  document.getElementById('tab-refs').style.color = '#fff';
  document.getElementById('tab-insps').style.background = '#2a2a2a';
  document.getElementById('tab-insps').style.color = '#c9a961';
  try {
    const res = await fetch(authUrl(API + '/api/book/references?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const refs = await res.json();
    let html = '<div style="color:#c9a961;font-weight:bold;margin-bottom:6px">📚 参考文献</div>';
    if (refs.length === 0) {
      html += '<div style="color:#666;font-size:13px">暂无参考文献，点击下方添加</div>';
    } else {
      html += '<div style="display:flex;flex-direction:column;gap:6px">';
      for (const r of refs) {
        const typeLabel = REF_TYPE_LABELS[r.type] || r.type;
        html += `<div style="padding:6px 8px;border:1px solid #333;border-radius:4px;font-size:13px;background:#1a1a1a">`;
        html += `<div style="display:flex;justify-content:space-between">`;
        html += `<span style="color:#e0e0e0;font-weight:bold">${r.title}</span>`;
        html += `<span style="color:#666;font-size:11px">[${typeLabel}]</span>`;
        html += `</div>`;
        if (r.author) html += `<div style="color:#999;font-size:12px">作者: ${r.author}</div>`;
        if (r.summary) html += `<div style="color:#888;font-size:12px;margin-top:2px">${r.summary}</div>`;
        if (r.notes) html += `<div style="color:#c9a961;font-size:12px;margin-top:2px">📝 ${r.notes}</div>`;
        html += `<div style="margin-top:4px;display:flex;gap:8px">`;
        html += `<button onclick="deleteReference('${bookPath}','${r.id}')" style="color:#c0392b;border:none;background:none;font-size:12px;cursor:pointer">删除</button>`;
        html += `</div></div>`;
      }
      html += '</div>';
    }
    html += `<button onclick="addReference('${bookPath}')" style="margin-top:8px;background:#2a2a2a;border:1px solid #c0392b;color:#c9a961;padding:4px 12px;border-radius:4px;font-size:13px;cursor:pointer">+ 添加参考文献</button>`;
    document.getElementById('references-list').innerHTML = html;
  } catch (e) {
    document.getElementById('references-list').innerHTML = '<div style="color:#c0392b">加载失败</div>'; 
  }
}

async function loadInspirations(bookPath) {
  // 标签切换：显示参照作品，隐藏参考文献
  document.getElementById('references-list').style.display = 'none';
  document.getElementById('inspirations-list').style.display = 'block';
  document.getElementById('tab-insps').style.background = '#2980b9';
  document.getElementById('tab-insps').style.color = '#fff';
  document.getElementById('tab-refs').style.background = '#2a2a2a';
  document.getElementById('tab-refs').style.color = '#c9a961';
  try {
    const res = await fetch(authUrl(API + '/api/book/inspirations?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const insps = await res.json();
    let html = '<div style="color:#c9a961;font-weight:bold;margin-bottom:6px">📖 参照作品</div>';
    if (insps.length === 0) {
      html += '<div style="color:#666;font-size:13px">暂无参照作品，点击下方添加</div>'; 
    } else {
      html += '<div style="display:flex;flex-direction:column;gap:6px">';
      for (const insp of insps) {
        const typeLabel = REF_TYPE_LABELS[insp.type] || insp.type;
        html += `<div style="padding:6px 8px;border:1px solid #333;border-radius:4px;font-size:13px;background:#1a1a1a">`;
        html += `<div style="display:flex;justify-content:space-between">`;
        html += `<span style="color:#e0e0e0;font-weight:bold">${insp.title}</span>`;
        html += `<span style="color:#666;font-size:11px">[${typeLabel}]</span>`;
        html += `</div>`;
        if (insp.author) html += `<div style="color:#999;font-size:12px">作者: ${insp.author}</div>`;
        if (insp.summary) html += `<div style="color:#888;font-size:12px;margin-top:2px">${insp.summary}</div>`;
        if (insp.notes) html += `<div style="color:#c9a961;font-size:12px;margin-top:2px">📝 对标笔记: ${insp.notes}</div>`;
        html += `<div style="margin-top:4px;display:flex;gap:8px">`;
        html += `<button onclick="deleteInspiration('${bookPath}','${insp.id}')" style="color:#c0392b;border:none;background:none;font-size:12px;cursor:pointer">删除</button>`;
        html += `</div></div>`;
      }
      html += '</div>'; 
    }
    html += `<button onclick="addInspiration('${bookPath}')" style="margin-top:8px;background:#2a2a2a;border:1px solid #2980b9;color:#c9a961;padding:4px 12px;border-radius:4px;font-size:13px;cursor:pointer">+ 添加参照作品</button>`;
    document.getElementById('inspirations-list').innerHTML = html;
  } catch (e) {
    document.getElementById('inspirations-list').innerHTML = '<div style="color:#c0392b">加载失败</div>'; 
  }
}

function addReference(bookPath) {
  const container = document.getElementById('references-list');
  const existing = container.querySelector('.add-form');
  if (existing) existing.remove();
  const form = document.createElement('div');
  form.className = 'add-form';
  form.style.cssText = 'padding:8px;border:1px solid #c0392b;border-radius:4px;background:#1a1a1a;margin-top:8px';
  form.innerHTML = `
    <div style="font-size:13px;color:#c9a961;margin-bottom:6px">添加参考文献</div>
    <input id="ref-title" placeholder="标题/书名" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="ref-author" placeholder="作者" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <select id="ref-type" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
      <option value="book">书籍</option><option value="paper">论文</option><option value="web">网页</option><option value="article">文章</option><option value="film">影视</option><option value="game">游戏</option><option value="other">其他</option>
    </select>
    <input id="ref-summary" placeholder="简要说明" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="ref-notes" placeholder="作者笔记（如何参考）" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="ref-url" placeholder="链接（可选）" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <button onclick="submitReference('${bookPath}')" style="background:#c0392b;color:#fff;border:none;padding:6px 16px;border-radius:4px;font-size:13px;cursor:pointer">保存</button>
    <button onclick="this.parentElement.remove()" style="background:none;border:1px solid #333;color:#666;padding:6px 16px;border-radius:4px;font-size:13px;cursor:pointer;margin-left:8px">取消</button>
  `;
  container.appendChild(form);
}

async function submitReference(bookPath) {
  const title = document.getElementById('ref-title').value.trim();
  if (!title) { alert('请填写标题'); return; }
  const body = {
    path: bookPath,
    title: title,
    author: document.getElementById('ref-author').value.trim(),
    type: document.getElementById('ref-type').value,
    summary: document.getElementById('ref-summary').value.trim(),
    notes: document.getElementById('ref-notes').value.trim(),
    url: document.getElementById('ref-url').value.trim()
  };
  try {
    const res = await fetch(authUrl(API + '/api/book/references'), {
      method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (data.status === 'ok') {
      loadReferences(bookPath);
    } else {
      alert('保存失败: ' + (data.error || ''));
    }
  } catch (e) { alert('保存失败: ' + e.message); }
}

async function deleteReference(bookPath, refId) {
  if (!confirm('确定删除此参考文献？')) return;
  try {
    const res = await fetch(authUrl(API + '/api/book/references?path=' + encodeURIComponent(bookPath) + '&id=' + encodeURIComponent(refId)), {
      method: 'DELETE', headers: authHeaders()
    });
    const data = await res.json();
    if (data.status === 'deleted') {
      loadReferences(bookPath);
    } else {
      alert('删除失败: ' + (data.error || ''));
    }
  } catch (e) { alert('删除失败: ' + e.message); }
}

function addInspiration(bookPath) {
  const container = document.getElementById('inspirations-list');
  const existing = container.querySelector('.add-form');
  if (existing) existing.remove();
  const form = document.createElement('div');
  form.className = 'add-form';
  form.style.cssText = 'padding:8px;border:1px solid #2980b9;border-radius:4px;background:#1a1a1a;margin-top:8px';
  form.innerHTML = `
    <div style="font-size:13px;color:#c9a961;margin-bottom:6px">添加参照作品</div>
    <input id="insp-title" placeholder="标题/作品名" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="insp-author" placeholder="作者" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <select id="insp-type" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
      <option value="book">书籍</option><option value="film">影视</option><option value="game">游戏</option><option value="article">文章</option><option value="web">网页</option><option value="other">其他</option>
    </select>
    <input id="insp-summary" placeholder="简要说明" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="insp-notes" placeholder="对标笔记（如何参照/对标）" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <input id="insp-url" placeholder="链接（可选）" style="width:100%;padding:4px;margin-bottom:4px;background:#0d0d0d;border:1px solid #333;color:#e0e0e0;border-radius:3px;font-size:13px">
    <button onclick="submitInspiration('${bookPath}')" style="background:#2980b9;color:#fff;border:none;padding:6px 16px;border-radius:4px;font-size:13px;cursor:pointer">保存</button>
    <button onclick="this.parentElement.remove()" style="background:none;border:1px solid #333;color:#666;padding:6px 16px;border-radius:4px;font-size:13px;cursor:pointer;margin-left:8px">取消</button>
  `;
  container.appendChild(form);
}

async function submitInspiration(bookPath) {
  const title = document.getElementById('insp-title').value.trim();
  if (!title) { alert('请填写标题'); return; }
  const body = {
    path: bookPath,
    title: title,
    author: document.getElementById('insp-author').value.trim(),
    type: document.getElementById('insp-type').value,
    summary: document.getElementById('insp-summary').value.trim(),
    notes: document.getElementById('insp-notes').value.trim(),
    url: document.getElementById('insp-url').value.trim()
  };
  try {
    const res = await fetch(authUrl(API + '/api/book/inspirations'), {
      method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (data.status === 'ok') {
      loadInspirations(bookPath);
    } else {
      alert('保存失败: ' + (data.error || ''));
    }
  } catch (e) { alert('保存失败: ' + e.message); }
}

async function deleteInspiration(bookPath, inspId) {
  if (!confirm('确定删除此参照作品？')) return;
  try {
    const res = await fetch(authUrl(API + '/api/book/inspirations?path=' + encodeURIComponent(bookPath) + '&id=' + encodeURIComponent(inspId)), {
      method: 'DELETE', headers: authHeaders()
    });
    const data = await res.json();
    if (data.status === 'deleted') {
      loadInspirations(bookPath);
    } else {
      alert('删除失败: ' + (data.error || ''));
    }
  } catch (e) { alert('删除失败: ' + e.message); }
}
