// 墨阁 · NovelForge Studio — Frontend v2 (Sidebar Layout)

const API = '';  // same origin

// 🟡-1: Auth token — auto-set on page load from startup message


// ========== Keyboard Shortcuts ==========
document.addEventListener('keydown', function(e) {
  if (e.ctrlKey && e.key === 's') { e.preventDefault(); saveConfig(); }
  if (e.ctrlKey && e.key === 'b') { e.preventDefault(); showPanel('books'); }
  if (e.ctrlKey && e.key === 'w') { e.preventDefault(); showPanel('write'); }
  if (e.ctrlKey && e.shiftKey && e.key === 'A') { e.preventDefault(); showPanel('audit'); }
});
// ========== Toast Notifications ==========
function showToast(message, type, duration) {
  type = type || 'info';
  duration = duration || 3000;
  var container = document.getElementById('toast-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toast-container';
    container.style.cssText = 'position:fixed;top:20px;right:20px;z-index:10000;display:flex;flex-direction:column;gap:8px;pointer-events:none;';
    document.body.appendChild(container);
  }
  var toast = document.createElement('div');
  var colors = { info: '#3a8f8f', success: '#27ae60', error: '#c0392b', warning: '#d4a24e' };
  var icons = { info: '\u2139', success: '\u2713', error: '\u2717', warning: '\u26A0' };
  var borderColor = colors[type] || colors.info;
  var iconChar = icons[type] || icons.info;
  toast.style.cssText = 'background:var(--ink-card, #1c1c24);color:var(--paper, #e8e4dc);padding:12px 16px;border-radius:8px;border-left:4px solid ' + borderColor + ';font-size:13px;max-width:320px;box-shadow:0 4px 12px rgba(0,0,0,0.3);pointer-events:auto;opacity:0;transform:translateX(20px);transition:all 0.3s ease;';
  toast.innerHTML = '<span style="margin-right:8px">' + iconChar + '</span>' + message;
  container.appendChild(toast);
  requestAnimationFrame(function() {
    toast.style.opacity = '1';
    toast.style.transform = 'translateX(0)';
  });
  setTimeout(function() {
    toast.style.opacity = '0';
    toast.style.transform = 'translateX(20px)';
    setTimeout(function() { toast.remove(); }, 300);
  }, duration);
}


// ========== Token Usage ==========
async function refreshUsage() {
  try {
    const resp = await fetch(authUrl(API + '/api/usage'));
    const data = await resp.json();
    document.getElementById('usage-total-calls').textContent = (data.totalCalls || 0).toLocaleString();
    document.getElementById('usage-total-input').textContent = (data.totalInputTokens || 0).toLocaleString();
    document.getElementById('usage-total-output').textContent = (data.totalOutputTokens || 0).toLocaleString();
    const cost = (data.totalCostCents || 0) / 100;
    document.getElementById('usage-total-cost').textContent = cost < 0.01 ? '$0.00' : '$' + cost.toFixed(2);
    
    const modelsDiv = document.getElementById('usage-models-list');
    if (data.models && Object.keys(data.models).length > 0) {
      let html = '<table class="usage-table"><thead><tr><th>模型</th><th>调用</th><th>输入</th><th>输出</th><th>费用</th></tr></thead><tbody>';
      for (const [model, v] of Object.entries(data.models)) {
        const mCost = (v.costCents || 0) / 100;
        html += '<tr><td>' + model + '</td><td>' + (v.calls||0) + '</td><td>' + (v.inputTokens||0).toLocaleString() + '</td><td>' + (v.outputTokens||0).toLocaleString() + '</td><td>' + (mCost < 0.01 ? '$0.00' : '$' + mCost.toFixed(2)) + '</td></tr>';
      }
      html += '</tbody></table>';
      modelsDiv.innerHTML = html;
    } else {
      modelsDiv.innerHTML = '<div class="usage-empty">暂无数据</div>';
    }
    showToast('用量已刷新', 'info', 1500);
  } catch(e) {
    showToast('获取用量失败: ' + e.message, 'error');
  }
}

async function resetUsage() {
  if (!confirm('确定要重置所有用量统计？此操作不可撤销。')) return;
  try {
    await fetch(authUrl(API + '/api/usage'), { method: 'DELETE' });
    await refreshUsage();
    showToast('统计已重置', 'success');
  } catch(e) {
    showToast('重置失败: ' + e.message, 'error');
  }
}


// ========== Chapter Continuation (续写) ==========
async function continueChapter() {
  const bookPath = document.getElementById('global-book')?.value;
  if (!bookPath) { showToast('请先选择书籍', 'warning'); return; }
  const chapterTitle = document.getElementById('chapter-edit-title')?.value;
  const currentText = document.getElementById('chapter-editor')?.value || '';
  if (!currentText.trim()) { showToast('章节内容为空，无法续写', 'warning'); return; }
  
  const prompt = document.getElementById('continue-prompt')?.value || '';
  const maxWords = 2000;
  const resultDiv = document.getElementById('chapter-continue-result') || document.getElementById('write-result');
  const body = { path: bookPath, chapterTitle, currentText, prompt, maxWords, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  await streamLlmRequest('/api/chapter/continue/stream', body, resultDiv, 'chapter-editor', 'btn-continue-chapter', '续写');
}

function appendContinuation() {
  const editor = document.getElementById('chapter-editor');
  const resultDiv = document.getElementById('chapter-continue-result') || document.getElementById('write-result');
  const continuedText = resultDiv?.innerText || '';
  if (continuedText && editor) {
    editor.value += '\n\n' + continuedText;
    showToast('续写内容已追加到编辑器', 'success');
  }
}


// ========== Chapter Continuation from Toolbox ==========
async function continueChapterTool() {
  const bookPath = document.getElementById('global-book')?.value;
  if (!bookPath) { showToast('请先选择书籍', 'warning'); return; }
  const prompt = document.getElementById('continue-chapter-prompt')?.value || '';
  const resultDiv = document.getElementById('write-result') || document.getElementById('synopsis-result');
  if (!resultDiv) { showToast('请切换到落笔面板查看结果', 'info'); return; }
  
  // Get current chapter content from the book info
  let currentText = '';
  try {
    const info = await (await fetch(authUrl(API + '/api/book/info?path=' + encodeURIComponent(bookPath)))).json();
    if (info.chapters && info.chapters.length > 0) {
      // Use the last chapter's content
      const lastCh = info.chapters[info.chapters.length - 1];
      const chResp = await fetch(authUrl(API + '/api/book/chapter?path=' + encodeURIComponent(bookPath) + '&chapter=' + lastCh.num));
      const chData = await chResp.json();
      currentText = chData.finalText || chData.draftText || '';
    }
  } catch(e) { showToast('获取章节内容失败', 'error'); return; }
  
  if (!currentText.trim()) { showToast('章节内容为空', 'warning'); return; }
  
  const body = { path: bookPath, currentText, prompt, maxWords: 2000, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  await streamLlmRequest('/api/chapter/continue/stream', body, resultDiv, null, 'btn-continue-chapter', '续写');
  showRefIndicator(resultDiv, bookPath);
}


// ========== Built-in Providers ==========
const BUILTIN_PROVIDERS = {
  openai:    { name: 'OpenAI',    baseUrl: 'https://api.openai.com/v1',                          model: 'gpt-4o' },
  anthropic: { name: 'Anthropic', baseUrl: 'https://api.anthropic.com',                          model: 'claude-3-opus-20240229' },
  deepseek:  { name: 'DeepSeek',  baseUrl: 'https://api.deepseek.com/v1',                        model: 'deepseek-chat' },
  qwen:      { name: '通义千问',   baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-max' },
  glm:       { name: '智谱GLM',   baseUrl: 'https://open.bigmodel.cn/api/paas/v4',               model: 'glm-4' },
  kimi:      { name: 'Moonshot',  baseUrl: 'https://api.moonshot.cn/v1',                          model: 'moonshot-v1-8k' },
  minimax:   { name: 'MiniMax',   baseUrl: 'https://api.minimax.chat/v1',                         model: 'abab6.5s-chat' }
};

function applyProviderPreset(key) {
  const p = BUILTIN_PROVIDERS[key];
  if (!p) return;
  const urlEl = document.getElementById('cfg-global-baseUrl');
  const modelEl = document.getElementById('cfg-global-modelId');
  if (urlEl) urlEl.value = p.baseUrl;
  if (modelEl) modelEl.value = p.model;
  // Also update provider select
  const provEl = document.getElementById('cfg-global-provider');
  if (provEl) {
    const opts = provEl.options;
    let found = false;
    for (let i = 0; i < opts.length; i++) {
      if (opts[i].value === key) { provEl.selectedIndex = i; found = true; break; }
    }
    if (!found) {
      // Add as custom
      provEl.value = 'custom';
    }
  }
  showToast('已切换到 ' + p.name + ' (' + p.model + ')', 'success', 2000);
}


// ========== Character & Hook Systems ==========
async function loadCharacterSheet() {
  const bookPath = document.getElementById('global-book')?.value;
  if (!bookPath) { document.getElementById('characters-content').innerHTML = '<div class="empty-hint">请先选择书籍</div>'; return; }
  try {
    const resp = await fetch(authUrl(API + '/api/characters?path=' + encodeURIComponent(bookPath)));
    const data = await resp.json();
    const container = document.getElementById('characters-content');
    if (!data.characters || data.characters.length === 0) {
      container.innerHTML = '<div class="empty-hint">暂无人物数据。完成写作后，人物信息将自动提取。</div>';
      return;
    }
    let html = '<div class="character-grid">';
    for (const c of data.characters) {
      html += '<div class="character-card">';
      html += '<div class="character-name">' + (c.name || '未命名') + '</div>';
      if (c.role) html += '<div class="character-role">' + c.role + '</div>';
      if (c.description) html += '<div class="character-desc">' + c.description + '</div>';
      if (c.relationships && c.relationships.length > 0) {
        html += '<div class="character-relations"><span class="relation-label">关系：</span>';
        for (const r of c.relationships) {
          html += '<span class="relation-tag">' + r.target + '(' + r.type + ')</span> ';
        }
        html += '</div>';
      }
      if (c.traits) html += '<div class="character-traits">' + c.traits + '</div>';
      html += '</div>';
    }
    html += '</div>';
    container.innerHTML = html;
  } catch(e) {
    document.getElementById('characters-content').innerHTML = '<div class="empty-hint">加载失败: ' + e.message + '</div>';
  }
}

async function loadHookTracker() {
  const bookPath = document.getElementById('global-book')?.value;
  if (!bookPath) { document.getElementById('hooks-content').innerHTML = '<div class="empty-hint">请先选择书籍</div>'; return; }
  try {
    const resp = await fetch(authUrl(API + '/api/hooks?path=' + encodeURIComponent(bookPath)));
    const data = await resp.json();
    const container = document.getElementById('hooks-content');
    if (!data.hooks || data.hooks.length === 0) {
      container.innerHTML = '<div class="empty-hint">暂无伏笔数据。完成写作后，伏笔信息将自动追踪。</div>';
      return;
    }
    let html = '<div class="hook-list">';
    for (const h of data.hooks) {
      const status = h.resolved ? '✅ 已收束' : '⏳ 待收束';
      html += '<div class="hook-card ' + (h.resolved ? 'resolved' : 'pending') + '">';
      html += '<div class="hook-title">' + (h.description || h.hook || '未命名伏笔') + '</div>';
      html += '<div class="hook-status">' + status + '</div>';
      if (h.plantedChapter) html += '<div class="hook-chapter">埋设：第' + h.plantedChapter + '章</div>';
      if (h.resolvedChapter) html += '<div class="hook-chapter">收束：第' + h.resolvedChapter + '章</div>';
      html += '</div>';
    }
    html += '</div>';
    container.innerHTML = html;
  } catch(e) {
    document.getElementById('hooks-content').innerHTML = '<div class="empty-hint">加载失败: ' + e.message + '</div>';
  }
}


// ========== World-Building Panel ==========
async function loadWorldBuilding() {
  const bookPath = document.getElementById('global-book')?.value;
  const container = document.getElementById('world-content');
  if (!bookPath) { if (container) container.innerHTML = '<div class="empty-hint">请先选择书籍</div>'; return; }
  try {
    const resp = await fetch(authUrl(API + '/api/world?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const data = await resp.json();
    let html = '';
    if (data.world) {
      html += '<div class="world-section">';
      html += '<div class="world-section-title">🌍 世界观设定</div>';
      if (data.world.setting) html += '<div class="world-field"><label>背景设定</label><div class="world-value">' + data.world.setting + '</div></div>';
      if (data.world.rules) html += '<div class="world-field"><label>世界规则</label><div class="world-value">' + data.world.rules + '</div></div>';
      if (data.world.geography) html += '<div class="world-field"><label>地理环境</label><div class="world-value">' + data.world.geography + '</div></div>';
      if (data.world.technology) html += '<div class="world-field"><label>技术水平</label><div class="world-value">' + data.world.technology + '</div></div>';
      if (data.world.culture) html += '<div class="world-field"><label>文化风俗</label><div class="world-value">' + data.world.culture + '</div></div>';
      if (data.world.history) html += '<div class="world-field"><label>历史背景</label><div class="world-value">' + data.world.history + '</div></div>';
      html += '</div>';
    }
    if (!data.world || (!data.world.setting && !data.world.rules)) {
      html += '<div class="empty-hint">暂无世界观数据。写作后系统会自动提取世界观信息。</div>';
    }
    if (container) container.innerHTML = html;
  } catch(e) {
    if (container) container.innerHTML = '<div class="empty-hint">加载失败: ' + e.message + '</div>';
  }
}

let AUTH_TOKEN = '';
(function() {
  const params = new URLSearchParams(window.location.search);
  const t = params.get('token');
  if (t) AUTH_TOKEN = t;
})();

// Populate version display
(function() {
  var versionEl = document.getElementById('studio-version');
  if (versionEl) {
    fetch(API + '/api/version').then(function(r){return r.json()}).then(function(d){
      versionEl.textContent = d.version || d.full || '';
    }).catch(function(){});
  }
})();

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

// Panel name mapping for old panel names that other code may reference
const PANEL_MAP = {
  'create': 'create',
  'books': 'books',
  'write': 'write',
  'toolbox': 'toolbox',
  'audit': 'audit',
  'config': 'config',
  // Legacy panel names → redirect to new panels
  'state': 'toolbox',
  'progress': 'toolbox',
  'style': 'toolbox',
  'rollback': 'audit',
  'characters': 'toolbox',
  'hooks': 'toolbox',
  'usage': 'panel-usage'
};

function showPanel(name) {
  const targetName = PANEL_MAP[name] || name;
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.sidebar-nav-item').forEach(b => b.classList.remove('active'));
  const panel = document.getElementById('panel-' + targetName);
  if (panel) {
    panel.classList.add('active');
    if (panelId === 'usage') refreshUsage();
    if (panelId === 'characters') loadCharacterSheet();
    if (panelId === 'hooks') loadHookTracker();
    if (panelId === 'world') loadWorldBuilding();
    // Trigger animation
    panel.style.opacity = '0';
    panel.style.transform = 'translateY(8px)';
    requestAnimationFrame(() => {
      panel.style.opacity = '1';
      panel.style.transform = 'translateY(0)';
    });
  }
  const nav = document.getElementById('nav-' + targetName);
  if (nav) nav.classList.add('active');

  // Auto-refresh relevant panels
  if (targetName === 'books') loadBooks();
  if (targetName === 'write' || targetName === 'toolbox') populateBookSelects();
  if (targetName === 'write') { const wb = document.getElementById('write-book')?.value; if (wb) updateWriteStats(wb); }
  if (targetName === 'toolbox') { populateBookSelects(); loadStyle(); loadCharacters(); loadHooks(); }
}

// ========== Result Display ==========

function showResult(div, msg, isError) {
  div.textContent = msg;
  div.className = 'result-box show ' + (isError ? 'error' : 'success');
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
      showResult(resultDiv, '✓ 「' + title + '」已开卷！路径: ' + data.path, false);
      document.getElementById('book-title').value = '';
      document.getElementById('book-author').value = '';
      loadBooks();
      populateBookSelects();
    } else if (data.status === 'exists') {
      showResult(resultDiv, '⚠ 「' + title + '」已存在，路径: ' + data.path, true);
      loadBooks();
      populateBookSelects();
    } else {
      showResult(resultDiv, '? ' + (data.error || '创建失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  }
}


// ========== SSE Streaming Helper ==========
async function streamLlmRequest(url, body, resultDiv, textareaId, btnId, btnText) {
  const btn = btnId ? document.getElementById(btnId) : null;
  if (btn) { btn.disabled = true; btn.textContent = '生成中...'; }
        btn.classList.add('btn-loading');

  const textarea = textareaId ? document.getElementById(textareaId) : null;
  if (textarea) textarea.value = '';

  let fullText = '';
  try {
    const res = await fetch(authUrl(API + url), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // Parse SSE events
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';

      for (const part of parts) {
        let currentEvent = '';
        for (const line of part.split('\n')) {
          if (line.startsWith('event: ')) {
            currentEvent = line.substring(7).trim();
          } else if (line.startsWith('data: ')) {
            const data = line.substring(6);
            if (currentEvent === 'chunk') {
              fullText += data.replace(/\\n/g, '\n');
              if (textarea) textarea.value = fullText;
              if (resultDiv) {
                const preview = fullText.substring(0, 500);
                showResult(resultDiv, '⏳ 生成中...\n\n' + preview + (fullText.length > 500 ? '...' : ''), false);
              }
            } else if (currentEvent === 'done') {
              if (textarea) textarea.value = fullText;
              if (resultDiv) {
                const preview = fullText.substring(0, 2000);
                showResult(resultDiv, '✓ 生成完成\n\n' + preview + (fullText.length > 2000 ? '\n...(完整内容见编辑器)' : ''), false);
              }
            } else if (currentEvent === 'error') {
              if (resultDiv) showResult(resultDiv, '✗ ' + data, true);
            }
          }
        }
      }
    }
    // Handle any remaining data in buffer
    if (buffer.trim()) {
      let currentEvent = '';
      for (const line of buffer.split('\n')) {
        if (line.startsWith('event: ')) currentEvent = line.substring(7).trim();
        else if (line.startsWith('data: ') && currentEvent === 'chunk') {
          fullText += line.substring(6).replace(/\\n/g, '\n');
          if (textarea) textarea.value = fullText;
        }
      }
    }
  } catch (e) {
    if (resultDiv) showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = btnText; }
        btn.classList.remove('btn-loading');
  }
  return fullText;
}

// ========== Wizard: Create Book + Generate Outline + Generate Volume Outline ==========

let wizardState = {
  currentStep: 1,
  bookPath: '',
  bookTitle: '',
  bookGenre: '',
  outline: '',
  volumeOutline: ''
};

function wizardSetStep(step) {
  wizardState.currentStep = step;
  // Update step indicators
  for (let i = 1; i <= 3; i++) {
    const stepEl = document.getElementById('wizard-step-' + i);
    const contentEl = document.getElementById('wizard-step-content-' + i);
    if (!stepEl) continue;
    if (i === step) {
      stepEl.classList.add('active');
      stepEl.classList.remove('completed');
      if (contentEl) {
        contentEl.style.display = 'block';
        contentEl.style.opacity = '0';
        contentEl.style.transform = 'translateY(12px)';
        requestAnimationFrame(() => {
          contentEl.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
          contentEl.style.opacity = '1';
          contentEl.style.transform = 'translateY(0)';
        });
      }
    } else if (i < step) {
      stepEl.classList.remove('active');
      stepEl.classList.add('completed');
      if (contentEl) contentEl.style.display = 'none';
    } else {
      stepEl.classList.remove('active');
      stepEl.classList.remove('completed');
      if (contentEl) contentEl.style.display = 'none';
    }
  }
  // Update step lines
  for (let i = 1; i <= 2; i++) {
    const lineEl = document.getElementById('wizard-line-' + i);
    if (lineEl) {
      if (i < step) lineEl.classList.add('active');
      else lineEl.classList.remove('active');
    }
  }
}

function wizardGoToStep(step) {
  // Only allow going back to completed steps or current step
  if (step > wizardState.currentStep) return;
  wizardSetStep(step);
}

function wizardNextStep(step) {
  wizardSetStep(step);
  // Auto-trigger actions for step 2→3
  if (step === 3) {
    wizardGenerateVolume();
  }
}

async function createBookWizard() {
  const title = document.getElementById('book-title').value.trim();
  const genre = document.getElementById('book-genre').value;
  const author = document.getElementById('book-author').value.trim();
  const prompt = document.getElementById('book-prompt').value.trim();
  const supplement = document.getElementById('book-supplement').value.trim();
  const resultDiv = document.getElementById('create-result');

  if (!title) { showResult(resultDiv, '请输入书名', true); return; }
  if (!prompt) { showResult(resultDiv, '请输入创意提示词', true); return; }

  const btn = document.getElementById('btn-wizard-create');
  btn.disabled = true;
  btn.textContent = '开卷中…';

  wizardState.bookTitle = title;
  wizardState.bookGenre = genre;

  try {
    // Step 1: Create book
    const createRes = await fetch(authUrl(API + '/api/book/create'), {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ title, genre, author })
    });
    const createData = await createRes.json();

    if (createData.status === 'exists') {
      // Book already exists - continue with existing book
      wizardState.bookPath = createData.path;
      showResult(resultDiv, '⚠ 该书籍已存在，将使用现有项目继续', true);
      loadBooks();
      populateBookSelects();
      wizardSetStep(2);
      document.getElementById('wizard-outline-loading').style.display = 'flex';
      document.getElementById('wizard-outline-result').style.display = 'none';
      const fullPrompt = supplement ? prompt + '\n\n补充设定：' + supplement : prompt;
      try {
        const outlineRes = await fetch(authUrl(API + '/api/outline/generate/stream'), {
          method: 'POST',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({
            prompt: fullPrompt, genre: genre,
            apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId,
            path: createData.path
          })
        });
        // Read SSE stream
        const dupReader = outlineRes.body.getReader();
        const dupDecoder = new TextDecoder();
        let dupBuffer = '';
        let outlineFullText = '';
        while (true) {
          const { done, value } = await dupReader.read();
          if (done) break;
          dupBuffer += dupDecoder.decode(value, { stream: true });
          const parts = dupBuffer.split('\n\n');
          dupBuffer = parts.pop() || '';
          for (const part of parts) {
            let evt = '';
            for (const line of part.split('\n')) {
              if (line.startsWith('event: ')) evt = line.substring(7).trim();
              else if (line.startsWith('data: ') && evt === 'chunk') {
                outlineFullText += line.substring(6).replace(/\\\\n/g, '\n');
                document.getElementById('wizard-outline-text').value = outlineFullText;
              }
            }
          }
        }
        const outlineData = { status: 'ok', outline: outlineFullText };
        if (outlineData.status === 'ok') {
          wizardState.outline = outlineData.outline || '';
          document.getElementById('wizard-outline-text').value = wizardState.outline;
          document.getElementById('wizard-book-path').textContent = createData.path;
          document.getElementById('wizard-outline-result').style.display = 'block';
        } else {
          document.getElementById('wizard-outline-result').style.display = 'block';
          document.getElementById('wizard-outline-text').value = '';
          document.getElementById('wizard-book-path').textContent = createData.path;
          showResult(document.getElementById('wizard-outline-result-msg'), '? 大纲生成失败: ' + (outlineData.error || '未知错误'), true);
        }
      } catch (e2) {
        document.getElementById('wizard-outline-loading').style.display = 'none';
        showResult(document.getElementById('wizard-outline-result-msg'), '? 网络错误: ' + e2.message, true);
      }
      btn.disabled = false;
      btn.textContent = '开卷创作';
      return;
    }
    if (createData.status !== 'created') {
      showResult(resultDiv, '? ' + (createData.error || '创建失败'), true);
      btn.disabled = false;
      btn.textContent = '开卷创作';
      return;
    }

    wizardState.bookPath = createData.path;
    loadBooks();
    populateBookSelects();

    // Move to step 2
    wizardSetStep(2);

    // Show loading
    document.getElementById('wizard-outline-loading').style.display = 'flex';
    document.getElementById('wizard-outline-result').style.display = 'none';

    // Step 2: Generate outline
    const fullPrompt = supplement ? prompt + '\n\n补充设定：' + supplement : prompt;
    const outlineRes = await fetch(authUrl(API + '/api/outline/generate/stream'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: fullPrompt, genre: genre,
        apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId,
        path: createData.path
      })
    });
    // Read SSE stream for wizard outline
    const oReader = outlineRes.body.getReader();
    const oDecoder = new TextDecoder();
    let oBuffer = ''; let outlineFullText = '';
    while (true) {
      const { done, value } = await oReader.read();
      if (done) break;
      oBuffer += oDecoder.decode(value, { stream: true });
      const parts = oBuffer.split('\n\n');
      oBuffer = parts.pop() || '';
      for (const part of parts) {
        let evt = '';
        for (const line of part.split('\n')) {
          if (line.startsWith('event: ')) evt = line.substring(7).trim();
          else if (line.startsWith('data: ') && evt === 'chunk') {
            outlineFullText += line.substring(6).replace(/\\n/g, '\n');
            document.getElementById('wizard-outline-text').value = outlineFullText;
          }
        }
      }
    }
    const outlineData = { status: 'ok', outline: outlineFullText };

    // Hide loading
    document.getElementById('wizard-outline-loading').style.display = 'none';

    if (outlineData.status === 'ok') {
      wizardState.outline = outlineData.outline || '';
      document.getElementById('wizard-outline-text').value = wizardState.outline;
      document.getElementById('wizard-book-path').textContent = createData.path;
      document.getElementById('wizard-outline-result').style.display = 'block';
      showResult(resultDiv, `✦ "${title}" 已开卷，大纲已生成！`, false);
    } else {
      document.getElementById('wizard-outline-result').style.display = 'block';
      document.getElementById('wizard-outline-text').value = '';
      document.getElementById('wizard-book-path').textContent = createData.path;
      showResult(document.getElementById('wizard-outline-result-msg'),
        '✗ 大纲生成失败: ' + (outlineData.error || '未知错误') + '，可手动编辑后保存', true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = '开卷创作';
  }
}

async function wizardSaveOutline() {
  const outline = document.getElementById('wizard-outline-text').value;
  const resultDiv = document.getElementById('wizard-outline-result-msg');
  const btn = document.getElementById('btn-wizard-save-outline');

  if (!wizardState.bookPath) { showResult(resultDiv, '请先创建书籍', true); return; }

  btn.disabled = true;
  btn.textContent = '保存中…';

  try {
    const res = await fetch(authUrl(API + '/api/book/outline'), {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ path: wizardState.bookPath, outline })
    });
    const data = await res.json();
    if (data.status === 'ok' || data.status === 'saved') {
      wizardState.outline = outline;
      showResult(resultDiv, '✦ 大纲已保存 (' + outline.length + ' 字)', false);
    } else {
      showResult(resultDiv, '✗ 保存失败: ' + (data.error || '未知错误'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = '保存大纲';
  }
}

async function wizardGenerateVolume() {
  if (!wizardState.bookPath) return;

  const resultDiv = document.getElementById('wizard-volume-result-msg');

  // Show loading
  document.getElementById('wizard-volume-loading').style.display = 'flex';
  document.getElementById('wizard-volume-result').style.display = 'none';

  try {
    const res = await fetch(authUrl(API + '/api/volume/generate/stream'), {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        path: wizardState.bookPath, prompt: '', genre: wizardState.bookGenre,
        apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId
      })
    });
    // Read SSE stream for wizard volume
    const vReader = res.body.getReader();
    const vDecoder = new TextDecoder();
    let vBuffer = ''; let volFullText = '';
    while (true) {
      const { done, value } = await vReader.read();
      if (done) break;
      vBuffer += vDecoder.decode(value, { stream: true });
      const parts = vBuffer.split('\n\n');
      vBuffer = parts.pop() || '';
      for (const part of parts) {
        let evt = '';
        for (const line of part.split('\n')) {
          if (line.startsWith('event: ')) evt = line.substring(7).trim();
          else if (line.startsWith('data: ') && evt === 'chunk') {
            volFullText += line.substring(6).replace(/\\n/g, '\n');
            document.getElementById('wizard-volume-text').value = volFullText;
          }
        }
      }
    }
    const data = { status: 'ok', volumeOutline: volFullText };

    document.getElementById('wizard-volume-loading').style.display = 'none';

    if (data.status === 'ok') {
      wizardState.volumeOutline = data.volumeOutline || '';
      document.getElementById('wizard-volume-text').value = wizardState.volumeOutline;
      document.getElementById('wizard-volume-result').style.display = 'block';
    } else {
      document.getElementById('wizard-volume-result').style.display = 'block';
      document.getElementById('wizard-volume-text').value = '';
      showResult(document.getElementById('wizard-volume-result-msg'), '? 卷纲生成失败: ' + (data.error || '未知错误'), true);
    }
  } catch (e) {
    document.getElementById('wizard-volume-loading').style.display = 'none';
    showResult(document.getElementById('wizard-volume-result-msg'), '? 网络错误: ' + e.message, true);
  }
}

async function wizardSaveVolume() {
  const volumeOutline = document.getElementById('wizard-volume-text').value;
  const resultDiv = document.getElementById('wizard-volume-result-msg');
  const btn = document.getElementById('btn-wizard-save-volume');

  if (!wizardState.bookPath) { showResult(resultDiv, '请先创建书籍', true); return; }

  btn.disabled = true;
  btn.textContent = '保存中…';

  try {
    const res = await fetch(authUrl(API + '/api/book/outline'), {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ path: wizardState.bookPath, outline: volumeOutline })
    });
    const data = await res.json();
    if (data.status === 'ok' || data.status === 'saved') {
      wizardState.volumeOutline = volumeOutline;
      showResult(resultDiv, '✦ 卷纲已保存 (' + volumeOutline.length + ' 字)', false);
    } else {
      showResult(resultDiv, '✗ 保存失败: ' + (data.error || '未知错误'), true);
    }
  } catch (e) {
    showResult(resultDiv, '✗ 网络错误: ' + e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = '保存卷纲';
  }
}

function wizardGoToWrite() {
  // Set the book in the write panel's select
  const writeSelect = document.getElementById('write-book');
  if (writeSelect && wizardState.bookPath) {
    writeSelect.value = wizardState.bookPath;
  }
  showPanel('write');
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
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book', 'delete-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    if (sel) sel.value = path;
  });
  showPanel('write');
  showBookDetail(path);
}

async function showBookDetail(bookPath) {
  const detailDiv = document.getElementById('book-detail');
  const contentDiv = document.getElementById('book-detail-content');
  try {
    const res = await fetch(authUrl(API + `/api/book/info?path=${encodeURIComponent(bookPath)}`), { headers: authHeaders() });
    const info = await res.json();
    detailDiv.style.display = 'block';
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
    loadReferences(bookPath);
    loadInspirations(bookPath);
  } catch (e) {
    detailDiv.style.display = 'none';
  }
}

function onGlobalBookChange(val) {
  const ids = ['write-book','audit-book','synopsis-book','style-book','characters-book','hooks-book','state-book','rollback-book','toolbox-book'];
  ids.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = val;
  });
  // Book detail card
  const detailCard = document.getElementById('book-detail-card');
  if (detailCard) {
    detailCard.style.display = val ? 'block' : 'none';
    if (val) loadBookEdit();
  }
  // Update context indicators
  const sel = document.getElementById('global-book');
  const bookLabel = val && sel ? sel.options[sel.selectedIndex].text : '';
  const writeCtx = document.getElementById('write-book-context');
  const writeName = document.getElementById('write-book-name');
  if (writeCtx) writeCtx.style.display = val ? 'block' : 'none';
  if (writeName) writeName.textContent = val ? bookLabel : '未选择书籍';
  const auditCtx = document.getElementById('audit-book-context');
  const auditName = document.getElementById('audit-book-name');
  if (auditCtx) auditCtx.style.display = val ? 'block' : 'none';
  if (auditName) auditName.textContent = val ? bookLabel : '未选择书籍';
}

function onToolboxBookChange(val) {
  const g = document.getElementById('global-book');
  if (g) g.value = val;
  onGlobalBookChange(val);
}

function switchDetailTab(tabName) {
  document.querySelectorAll('.detail-tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.detail-tab-content').forEach(t => t.classList.remove('active'));
  const tab = document.querySelector(`.detail-tab[onclick="switchDetailTab('${tabName}')"]`);
  const content = document.getElementById('tab-' + tabName);
  if (tab) tab.classList.add('active');
  if (content) content.classList.add('active');
}

// ========== Populate Book Selects ==========

async function populateBookSelects(books) {
  if (!books) {
    try {
      const res = await fetch(authUrl(API + '/api/books'), { headers: authHeaders() });
      books = await res.json();
    } catch (e) { return; }
  }
  const selects = ['global-book','toolbox-book','write-book', 'state-book', 'audit-book', 'export-book', 'delete-book', 'progress-book', 'style-book', 'rollback-book', 'characters-book', 'hooks-book', 'synopsis-book', 'outline-editor-book', 'intent-editor-book', 'book-edit-book', 'search-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    if (!sel) return;
    sel.innerHTML = books.map(b =>
      `<option value="${safePath(b.path)}">${b.title} · ${GENRE_LABELS[b.genre] || b.genre}</option>`
    ).join('');
  });
}

// ========== Write Chapter ==========

const AGENT_ORDER = ['Architect', 'Planner', 'Composer', 'Writer', 'Observer', 'Reflector', 'Normalizer', 'Auditor', 'Reviser'];
const AGENT_DURATION = [3000, 2500, 2000, 8000, 3000, 3500, 2000, 4000, 5000];

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
  const prevConnector = node?.previousElementSibling;
  if (prevConnector && prevConnector.classList.contains('step-connector')) {
    prevConnector.classList.add('active');
  }
}

async function writeChapter() {
  const bookPath = document.getElementById('write-book').value;
  const mode = document.getElementById('write-mode').value;
  const batchCount = mode === 'batch' ? parseInt(document.getElementById('write-batch-count')?.value || '3') : 0;
  // Use sharedConfig only — no more per-panel API fields
  const apiKey = sharedConfig.apiKey;
  const baseUrl = sharedConfig.baseUrl;
  const modelId = sharedConfig.modelId;

  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');
  const btnWrite = document.getElementById('btn-write');
  const chapterPreview = document.getElementById('chapter-preview');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }

  clearResult(resultDiv);
  chapterPreview.style.display = 'none';
  btnWrite.disabled = true;
  btnWrite.textContent = '炼章中…';
  document.getElementById('btn-cancel').style.display = 'inline-block';

  resetPipelineSteps();

  const agents = mode === 'draft'
    ? AGENT_ORDER.slice(0, 4)
    : AGENT_ORDER;

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

    if (data.jobId) {
      currentWriteJobId = data.jobId;
      streamWriteJob(data.jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
      return;
    }

    progressDiv.textContent = '';
    agents.forEach(a => markStepCompleted(a));

    if (data.status === 'ok') {
      let msg = `✦ 第 ${data.chapterNumber} 章已成！${data.length} 字`;
      if (data.auditScore) {
        const scoreColor = data.auditScore >= 7 ? 'var(--success)' : data.auditScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
        msg += ` · 审阅 <span style="color:${scoreColor}">${data.auditScore.toFixed(1)}</span>/10`;
      }
      showResult(resultDiv, msg, false);
      await showChapterPreview(bookPath, data.chapterNumber);
    } else {
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
  const apiKey = sharedConfig.apiKey;
  const baseUrl = sharedConfig.baseUrl;
  const modelId = sharedConfig.modelId;

  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');
  const btnResume = document.getElementById('btn-resume');
  const btnWrite = document.getElementById('btn-write');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }

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

let currentChapterInfo = { bookPath: '', chapterNum: 0 };
let currentChapterDraftText = '';
let currentChapterFinalText = '';
let currentChapterViewMode = 'final';

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
    currentChapterInfo = { bookPath, chapterNum: data.number };
    titleInput.value = data.title || `第${data.number}章`;
    const content = data.finalText || data.draftText || '(无内容)';
    currentChapterDraftText = data.draftText || '';
    currentChapterFinalText = data.finalText || '';
    currentChapterViewMode = 'final';
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
  const toggle = document.getElementById('draft-final-toggle');
  if (toggle) toggle.textContent = `[当前:${label} · 点击切换]`;
  const contentDiv = textDiv.querySelector('div[style]');
  if (contentDiv) contentDiv.innerHTML = text.replace(/</g, '&lt;');
}

async function cancelWrite() {
  if (!currentWriteJobId) { showToast('当前没有进行中的写作任务', 'info'); return; }
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
      showToast('取消失败: ' + (data.error || '未知错误'), 'error');
    }
  } catch (e) { showToast('网络错误: ' + e.message, 'error'); }
}

function startBatchWrite() {
  document.getElementById('write-mode').value = 'batch';
  document.getElementById('batch-count-group').style.display = 'block';
  writeChapter();
}

// ========== Outline/Intent Editors ==========

async function loadOutlineEditor() {
  const bookPath = document.getElementById('global-book').value;
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
  const bookPath = document.getElementById('global-book').value;
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
  const bookPath = document.getElementById('global-book').value;
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
  const bookPath = document.getElementById('global-book').value;
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
  if (!bookPath || !chapterNum) { showToast('请先加载章节内容', 'info'); return; }
  if (!title) { showToast('标题不能为空', 'info'); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/chapter-title'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, chapter: chapterNum, title })
    });
    const data = await res.json();
    if (data.status === 'saved') {
      showToast(`第 ${chapterNum} 章标题已更新为: ${title}`, 'info');
      showChapterContent(bookPath, chapterNum);
    } else {
      showToast('保存失败: ' + (data.error || '未知'), 'error');
    }
  } catch (e) { showToast('网络错误: ' + e.message, 'error'); }
}

// ========== Chapter Content Edit ==========

function editChapterContent() {
  const textDiv = document.getElementById('chapter-text');
  const textarea = document.getElementById('chapter-edit-textarea');
  const btnEdit = document.getElementById('btn-edit-chapter');
  const btnSave = document.getElementById('btn-save-chapter');
  const btnCancel = document.getElementById('btn-cancel-edit');
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
  if (!bookPath || !chapterNum) { showToast('请先加载章节内容', 'info'); return; }
  try {
    const res = await fetch(authUrl(API + '/api/book/chapter'), {
      method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ path: bookPath, chapter: chapterNum, finalText })
    });
    const data = await res.json();
    if (data.status === 'saved') {
      showToast(`第 ${chapterNum} 章内容已保存 (${data.wordCount} 字, 'info')`);
      cancelChapterEdit();
      await showChapterContent(bookPath, chapterNum);
    } else {
      showToast('保存失败: ' + (data.error || '未知'), 'error');
    }
  } catch (e) { showToast('网络错误: ' + e.message, 'error'); }
}

// ========== Book Search ==========

async function searchBookContent() {
  const bookPath = document.getElementById('global-book')?.value;
  const keyword = document.getElementById('search-keyword')?.value;
  const resultDiv = document.getElementById('search-results');
  if (!bookPath) { if (resultDiv) resultDiv.innerHTML = '<div class="empty-hint">请选择书籍</div>'; return; }
  if (!keyword || keyword.trim().length === 0) { if (resultDiv) resultDiv.innerHTML = '<div class="empty-hint">请输入关键词</div>'; return; }
  try {
    const res = await fetch(authUrl(API + '/api/search?path=' + encodeURIComponent(bookPath) + '&keyword=' + encodeURIComponent(keyword.trim())), { headers: authHeaders() });
    const data = await res.json();
    if (!data || data.length === 0) {
      if (resultDiv) resultDiv.innerHTML = '<div class="empty-hint">未找到「' + keyword + '」</div>';
      return;
    }
    let html = '<div class="search-summary">找到 ' + data.length + ' 处匹配「<span class="search-keyword-highlight">' + keyword + '</span>」</div>';
    html += '<div class="search-results-list">';
    for (const hit of data) {
      const chTitle = hit.chapter || hit.title || '未知章节';
      const snippet = hit.snippet ? hit.snippet.replace(new RegExp('(' + keyword.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi'), '<span class="search-highlight">$1</span>') : '';
      html += '<div class="search-result-item">';
      html += '<div class="search-result-chapter" onclick="jumpToChapter(\'' + bookPath + '\', \'' + chTitle.replace(/'/g, "\\'") + '\')">' + chTitle + '</div>';
      html += '<div class="search-result-snippet">' + snippet + '</div>';
      html += '</div>';
    }
    html += '</div>';
    if (resultDiv) resultDiv.innerHTML = html;
  } catch(e) {
    if (resultDiv) resultDiv.innerHTML = '<div class="empty-hint">搜索失败: ' + e.message + '</div>';
  }
}

function jumpToChapter(bookPath, chapterTitle) {
  showPanel('books');
  setTimeout(() => {
    showChapterContent(bookPath, chapterTitle);
    showToast('已跳转到: ' + chapterTitle, 'info', 2000);
  }, 300);
}


// ========== Book Property Edit ==========

async function loadBookEdit() {
  const bookPath = document.getElementById('global-book').value;
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
  const bookPath = document.getElementById('global-book').value;
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
      loadBooks();
    } else { showResult(resultDiv, '保存失败: ' + (data.error || '未知'), true); }
  } catch (e) { showResult(resultDiv, '网络错误: ' + e.message, true); }
}

// ========== Audit Chapter ==========

async function auditChapter() {
  const bookPath = document.getElementById('audit-book').value;
  const chapterNum = document.getElementById('audit-chapter').value;
  // Use sharedConfig — no more per-panel API fields
  const apiKey = sharedConfig.apiKey;
  const baseUrl = sharedConfig.baseUrl;
  const modelId = sharedConfig.modelId;

  const progressDiv = document.getElementById('audit-progress');

  if (!bookPath) { return; }

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

  const scoreEl = document.getElementById('audit-total-score');
  const badge = document.getElementById('audit-pass-badge');

  scoreEl.textContent = data.overallScore.toFixed(1);
  scoreEl.style.color = data.overallScore >= 7 ? 'var(--success)' :
                        data.overallScore >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';

  badge.textContent = data.pass ? '✓ 通过' : '✗ 未通过';
  badge.className = 'pass-badge ' + (data.pass ? 'pass' : 'fail');

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
    const stateRes = await fetch(authUrl(API + `/api/state?path=${encodeURIComponent(bookPath)}&type=${type}`), {
      headers: authHeaders()
    });
    const stateData = await stateRes.json();
    content.textContent = stateData.summary || '无数据';

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

// ========== Delete Book ==========

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
      showToast('删除失败: ' + (data.error || '未知错误'), 'error');
    }
  } catch (e) {
    showToast('网络错误: ' + e.message, 'error');
  }
}

async function deleteBook() {
  const bookPath = document.getElementById('global-book').value;
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
  const bookPath = document.getElementById('global-book').value;
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
      ...(document.getElementById('cfg-global-apikey').value.trim() ? { apiKey: document.getElementById('cfg-global-apikey').value.trim() } : {})
    },
    agentOverrides: {}
  };

  Object.entries(agentToggles).forEach(([key, val]) => {
    body[key] = val;
  });

  const agentNames = ['Architect','Planner','Composer','Writer','Observer','Reflector','Normalizer','Auditor','Reviser'];
  agentNames.forEach(name => {
    const modelEl = document.getElementById('cfg-agent-model-' + name);
    const baseUrlEl = document.getElementById('cfg-agent-baseurl-' + name);
    const apiKeyEl = document.getElementById('cfg-agent-apikey-' + name);
    const providerEl = document.getElementById('cfg-agent-provider-' + name);
    const model = modelEl ? modelEl.value.trim() : '';
    const baseUrl = baseUrlEl ? baseUrlEl.value.trim() : '';
    const apiKey = apiKeyEl ? apiKeyEl.value.trim() : '';
    const provider = providerEl ? providerEl.value : '';
    if (model || baseUrl || apiKey || provider) {
      const override = {};
      if (model) override.model = model;
      if (baseUrl) override.baseUrl = baseUrl;
      if (apiKey) override.apiKey = apiKey;
      if (provider) override.provider = provider;
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
          showToast('配置已保存', 'success');
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

// ========== SSE Write Streaming + Polling Fallback ==========

function streamWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath) {
  const completedAgents = new Set();

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
      pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
    };
  } catch (e) {
    pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath);
  }
}

async function pollWriteJob(jobId, agents, progressDiv, resultDiv, btnWrite, bookPath) {
  let stepIndex = 0;

  const poll = async () => {
    try {
      const res = await fetch(authUrl(API + `/api/write/status?jobId=${jobId}`), { headers: authHeaders() });
      const data = await res.json();

      if (data.events && data.events.length > 0) {
        for (const evt of data.events) {
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
        progressDiv.textContent = '';
        showResult(resultDiv, '✦ 已取消', true);
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

    // Render unified agent pipeline config
    renderAgentPipelineConfig(data.agentOverrides || {});

    if (data.globalDefault) {
      document.getElementById('cfg-global-provider').value = data.globalDefault.provider || 'openai';
      document.getElementById('cfg-global-model').value = data.globalDefault.model || 'gpt-4o';
      document.getElementById('cfg-global-baseurl').value = data.globalDefault.baseUrl || 'https://api.openai.com/v1';
      const maskedKey = data.globalDefault.apiKey || '';
      const keyInput = document.getElementById('cfg-global-apikey');
      keyInput.value = '';
      keyInput.placeholder = maskedKey ? maskedKey + '（留空保留原值）' : 'sk-...';
      sharedConfig.baseUrl = data.globalDefault.baseUrl || 'https://api.openai.com/v1';
      sharedConfig.modelId = data.globalDefault.model || 'gpt-4o';
      if (data.globalDefault.apiKey) sharedConfig.apiKey = data.globalDefault.apiKey;
      syncConfigToUI();
    }

    const presetSelect = document.getElementById('cfg-preset');
    if (data.presets && presetSelect) {
      const currentVal = presetSelect.value;
      presetSelect.innerHTML = '<option value="">自定义</option>';
      const presetLabels = { economy: '💰 省钱模式', quality: '👑 高质量模式', fast: '⚡ 快速模式' };
      Object.keys(data.presets).forEach(key => {
        const desc = data.presets[key].description || presetLabels[key] || key;
        const opt = document.createElement('option');
        opt.value = key;
        opt.textContent = desc;
        presetSelect.appendChild(opt);
      });
      presetSelect.value = data.activePreset || currentVal || '';
    } else if (data.activePreset) {
      presetSelect.value = data.activePreset;
    }


  } catch (e) {
    // Use defaults
  }
}

// ========== Agent Config Cards (new card-based UI) ==========

function renderAgentPipelineConfig(overrides) {
  const container = document.getElementById('agent-pipeline-config');
  if (!container) return;
  const agentNames = ['Architect','Planner','Composer','Writer','Observer','Reflector','Normalizer','Auditor','Reviser'];
  const agentLabels = ['构思','计划','编排','书写','观察','反思','润色','审查','修订'];
  const agentIcons = ['🏗','📋','🎼','✍','👁','🪞','✨','🔍','🔧'];
  const toggleKeys = ['runArchitect','runPlanner','runComposer','runWriter','runObserver','runReflector','runNormalizer','runAuditor','runReviser'];
  let html = '';
  agentNames.forEach((name, i) => {
    const ov = overrides[name] || {};
    const hasOverride = ov.model || ov.baseUrl || ov.apiKey || ov.provider;
    const isActive = agentToggles[toggleKeys[i]] !== false;
    const statusClass = hasOverride ? 'custom' : 'global';
    const statusText = hasOverride ? '自定义' : '全局';
    const modelDisplay = hasOverride ? (ov.provider ? ov.provider + '/' : '') + (ov.model || '—') : '跟随全局';
    html += `<div class="agent-pipeline-card ${hasOverride ? 'has-override' : ''} ${isActive ? '' : 'disabled-agent'}" id="agent-card-${name}">
      <div class="agent-pipeline-icon">${agentIcons[i]}</div>
      <div class="agent-pipeline-info">
        <div class="agent-pipeline-name">${agentLabels[i]}<span class="agent-pipeline-name-en">${name}</span></div>
        <div class="agent-pipeline-model">${modelDisplay}</div>
      </div>
      <span class="agent-pipeline-status ${statusClass}">${statusText}</span>
      <label class="agent-toggle-switch">
        <input type="checkbox" ${isActive ? 'checked' : ''} onchange="toggleAgentPipeline(this, '${toggleKeys[i]}')">
        <span class="agent-toggle-slider"></span>
      </label>
      <button class="agent-expand-btn" onclick="toggleAgentCard('${name}')">▶</button>
    </div>
    <div class="agent-pipeline-body" id="agent-body-${name}">
      <div class="agent-override-grid">
        <div class="form-group">
          <label>Provider</label>
          <select id="cfg-agent-provider-${name}" class="input-field" style="width:100%">
            <option value="" ${!ov.provider?'selected':''}>跟随全局</option>
            <option value="openai" ${ov.provider==='openai'?'selected':''}>OpenAI</option>
            <option value="anthropic" ${ov.provider==='anthropic'?'selected':''}>Anthropic</option>
            <option value="custom" ${ov.provider==='custom'?'selected':''}>自定义</option>
          </select>
        </div>
        <div class="form-group">
          <label>模型</label>
          <input type="text" id="cfg-agent-model-${name}" class="input-field" style="width:100%" placeholder="默认" value="${escapeHtml(ov.model||'')}">
        </div>
        <div class="form-group">
          <label>API地址</label>
          <input type="text" id="cfg-agent-baseurl-${name}" class="input-field" style="width:100%" placeholder="默认" value="${escapeHtml(ov.baseUrl||'')}">
        </div>
        <div class="form-group">
          <label>API Key</label>
          <input type="password" id="cfg-agent-apikey-${name}" class="input-field" style="width:100%" placeholder="${ov.apiKey ? escapeHtml(ov.apiKey)+'（留空保留）' : '默认'}" value="">
        </div>
      </div>
      <div class="agent-override-tip">留空则跟随全局默认配置</div>
    </div>`;
  });
  container.innerHTML = html;
}


function toggleAgentCard(agentName) {
  const card = document.getElementById('agent-card-' + agentName);
  const body = document.getElementById('agent-body-' + agentName);
  if (!card || !body) return;
  card.classList.toggle('open');
  // body visibility is handled by CSS: .agent-pipeline-card.open + .agent-pipeline-body
}

function toggleAgentPipeline(checkbox, key) {
  const active = checkbox.checked;
  agentToggles[key] = active;
  const card = checkbox.closest('.agent-pipeline-card');
  if (card) {
    card.classList.toggle('disabled-agent', !active);
  }
  updatePipelineStepVisibility();
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
      loadConfig();
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
  const bookPath = document.getElementById('progress-book')?.value || document.getElementById('state-book')?.value;
  if (!bookPath) return;
  try {
    const res = await fetch(authUrl(API + '/api/diff?path=' + encodeURIComponent(bookPath) + '&chapter=' + chapterNum), { headers: authHeaders() });
    const data = await res.json();
    document.getElementById('diff-title').textContent = '第 ' + chapterNum + ' 章 Diff';
    const container = document.getElementById('diff-content');
    container.innerHTML = '';
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
    document.getElementById('diff-modal').style.display = '';
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
  const bookPath = document.getElementById('style-book')?.value;
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
  const bookPath = document.getElementById('progress-book')?.value || document.getElementById('state-book')?.value;
  if (!bookPath) return;
  try {
    const res = await fetch(authUrl(API + '/api/progress?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    const data = await res.json();
    const summaryDiv = document.getElementById('progress-summary');
    if (summaryDiv) summaryDiv.style.display = '';

    // Use new IDs to avoid conflicts with state-stats
    const pstatChapters = document.getElementById('pstat-chapters');
    const pstatWords = document.getElementById('pstat-words');
    const pstatScore = document.getElementById('pstat-score');
    const pstatTime = document.getElementById('pstat-time');

    if (pstatChapters) pstatChapters.querySelector('.stat-value').textContent = data.totalChapters || 0;
    if (pstatWords) pstatWords.querySelector('.stat-value').textContent = (data.totalWords || 0).toLocaleString();
    if (pstatScore) pstatScore.querySelector('.stat-value').textContent = (data.averageAuditScore || 0).toFixed(1);
    const secs = Math.round((data.totalPipelineTimeMs || 0) / 1000);
    if (pstatTime) pstatTime.querySelector('.stat-value').textContent = secs > 60 ? Math.floor(secs/60) + 'm' + (secs%60) + 's' : secs + 's';

    const tbody = document.getElementById('progress-tbody');
    if (!tbody) return;
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
      tbody.innerHTML = '<tr><td colspan="6" style="padding:12px;color:#8b7355;text-align:center">暂无详细进度数据（需要通过写作功能生成）</td></tr>';
    }
  } catch (e) {
    console.error('loadProgress error:', e);
  }
}


// ========== Write Progress Statistics ==========

async function updateWriteStats(bookPath) {
  if (!bookPath) return;
  const statsDiv = document.getElementById('write-stats');
  if (!statsDiv) return;
  try {
    const info = await (await fetch(authUrl(API + '/api/book/info?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() })).json();
    const chs = info.chapterDetails || [];
    const totalWords = chs.reduce((s, c) => s + c.wordCount, 0);
    const scored = chs.filter(c => c.auditScore != null);
    const avgScore = scored.length > 0 ? (scored.reduce((s, c) => s + c.auditScore, 0) / scored.length).toFixed(1) : '—';
    const passed = chs.filter(c => c.passed).length;
    // Outline coverage: if outline mentions chapters, estimate coverage
    let coverage = '—';
    if (info.outlinePreview) {
      const outlineChapters = (info.outlinePreview.match(/第[一二三四五六七八九十百千\d]+章/g) || []).length;
      if (outlineChapters > 0) {
        coverage = Math.min(100, Math.round(chs.length / outlineChapters * 100)) + '%';
      }
    }
    document.getElementById('ws-chapters').textContent = chs.length;
    document.getElementById('ws-words').textContent = totalWords.toLocaleString();
    document.getElementById('ws-avg-score').textContent = avgScore;
    document.getElementById('ws-passed').textContent = passed;
    document.getElementById('ws-coverage').textContent = coverage;
    // Color the avg score
    const avgEl = document.getElementById('ws-avg-score');
    if (avgScore !== '—') {
      const score = parseFloat(avgScore);
      avgEl.style.color = score >= 7 ? 'var(--success)' : score >= 5 ? 'var(--warning)' : 'var(--cinnabar-light)';
    }
    statsDiv.style.display = 'flex';
  } catch (e) {}
}

async function showRefIndicator(bookPath, resultDiv) {
  if (!bookPath) return;
  try {
    const info = await (await fetch(authUrl(API + '/api/book/info?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() })).json();
    const refCount = info.referencesCount || 0;
    const inspCount = info.inspirationsCount || 0;
    if (refCount > 0 || inspCount > 0) {
      const badge = document.createElement('div');
      badge.style.cssText = 'padding:4px 8px;margin-bottom:6px;background:#1a1a1a;border:1px solid #c9a961;border-radius:4px;font-size:12px;color:#c9a961';
      badge.innerHTML = `\u{1F4DA} 已引用素材：${refCount > 0 ? refCount + '篇参考文献' : ''}${refCount > 0 && inspCount > 0 ? '、' : ''}${inspCount > 0 ? inspCount + '部参照作品' : ''}，生成内容将融入参考要点`;
      resultDiv.prepend(badge);
    }
  } catch (e) {}
}

// ========== Prompt-driven Outline Generation ==========

async function generateOutlineFromPrompt() {
  const bookPath = document.getElementById('write-book')?.value;
  const prompt = document.getElementById('outline-gen-prompt')?.value?.trim();
  const genre = document.getElementById('outline-gen-genre')?.value || 'xuanhuan';
  const resultDiv = document.getElementById('outline-gen-result');

  if (!prompt) { showResult(resultDiv, '请输入创意提示词', true); return; }

  const body = { prompt, genre, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  if (bookPath) body.path = bookPath;
  showRefIndicator(bookPath, resultDiv);
  await streamLlmRequest('/api/outline/generate/stream', body, resultDiv, null, 'btn-outline-gen', '✒ 生成大纲');
}

// ========== Volume Outline Generation ==========

async function generateVolumeOutline() {
  const bookPath = document.getElementById('write-book')?.value;
  const prompt = document.getElementById('volume-gen-prompt')?.value?.trim() || '';
  const genre = document.getElementById('volume-gen-genre')?.value || 'xuanhuan';
  const resultDiv = document.getElementById('volume-gen-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍（需有大纲）', true); return; }

  const body = { path: bookPath, prompt, genre, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  showRefIndicator(bookPath, resultDiv);
  await streamLlmRequest('/api/volume/generate/stream', body, resultDiv, null, 'btn-volume-gen', '✒ 生成卷纲');
}

// ========== Chapter Revision ==========

async function reviseChapter() {
  const bookPath = document.getElementById('write-book')?.value;
  const chapterNum = parseInt(document.getElementById('revise-chapter-num')?.value || '0');
  const prompt = document.getElementById('revise-prompt')?.value?.trim();
  const source = document.getElementById('revise-source')?.value || 'outline';
  const apiKey = sharedConfig.apiKey;
  const baseUrl = sharedConfig.baseUrl;
  const modelId = sharedConfig.modelId;
  const resultDiv = document.getElementById('revise-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (chapterNum < 1) { showResult(resultDiv, '请输入有效章节号（>=1）', true); return; }
  if (!prompt) { showResult(resultDiv, '请输入修改提示词', true); return; }

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
  document.getElementById('char-edit-name').value = name;
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
  document.getElementById('hook-edit-id').value = hookId;
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

  const body = { path: bookPath, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  showRefIndicator(bookPath, document.getElementById('write-result'));
  await streamLlmRequest('/api/outline/synopsis/stream', body, document.getElementById('write-result'), null, 'btn-outline', '大纲梗概');
}

// ========== 卷纲梗概生成 ==========

async function generateVolumeSynopsis() {
  const bookPath = document.getElementById('write-book')?.value;
  if (!bookPath) { showResult(document.getElementById('write-result'), '请先选择书籍', true); return; }

  let volumeStart = 1, volumeEnd = 10;
  try {
    const infoResp = await fetch(authUrl(API + '/api/book/info?path=' + encodeURIComponent(bookPath)), { headers: authHeaders() });
    if (infoResp.ok) {
      const info = await infoResp.json();
      const chapterCount = info.chapters?.length || 0;
      if (chapterCount > 0) volumeEnd = chapterCount;
    }
  } catch (e) { /* use defaults */ }

  const body = { path: bookPath, apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId, volumeStart, volumeEnd };
  showRefIndicator(bookPath, document.getElementById('write-result'));
  await streamLlmRequest('/api/volume/synopsis/stream', body, document.getElementById('write-result'), null, 'btn-volume', '卷纲梗概');
}

// ========== AI痕迹检测去除 ==========

async function detectAiTrace() {
  const bookPath = document.getElementById('write-book')?.value;
  if (!bookPath) { showResult(document.getElementById('write-result'), '请先选择书籍', true); return; }
  const apiKey = sharedConfig.apiKey;
  const baseUrl = sharedConfig.baseUrl;
  const modelId = sharedConfig.modelId;

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

// ========== Chapter Synopsis Generation ==========

async function generateChapterSynopsis() {
  const bookPath = document.getElementById('synopsis-source-book')?.value 
    || document.getElementById('global-book')?.value;
  const source = document.getElementById('synopsis-source')?.value || '';
  const prompt = document.getElementById('chapter-synopsis-prompt')?.value || '';
  const genre = document.getElementById('chapter-synopsis-genre')?.value || '';
  if (!source && !bookPath) { showToast('请先选择书籍或输入大纲/卷纲内容', 'warning'); return; }
  const resultDiv = document.getElementById('synopsis-result') || document.getElementById('write-result');
  const body = { apiKey: sharedConfig.apiKey, baseUrl: sharedConfig.baseUrl, model: sharedConfig.modelId };
  if (bookPath) body.path = bookPath;
  if (source) body.source = source;
  if (prompt) body.prompt = prompt;
  if (genre) body.genre = genre;
  await streamLlmRequest('/api/chapter/synopsis/stream', body, resultDiv, null, 'btn-chapter-synopsis', '章节梗概');
  showRefIndicator(resultDiv, bookPath);
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
      listBackups();
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
  if (!title) { showToast('请填写标题', 'info'); return; }
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
      showToast('保存失败: ' + (data.error || ''), 'error');
    }
  } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
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
      showToast('删除失败: ' + (data.error || ''), 'error');
    }
  } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
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
  if (!title) { showToast('请填写标题', 'info'); return; }
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
      showToast('保存失败: ' + (data.error || ''), 'error');
    }
  } catch (e) { showToast('保存失败: ' + e.message, 'error'); }
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
      showToast('删除失败: ' + (data.error || ''), 'error');
    }
  } catch (e) { showToast('删除失败: ' + e.message, 'error'); }
}

// ========== Version ==========

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

// ========== Init ==========

fetchVersion();
loadBooks();
populateBookSelects();
loadConfig();
resetPipelineSteps();

// Mode change: show/hide batch count field
document.getElementById('write-mode').addEventListener('change', function() {
  document.getElementById('batch-count-group').style.display = this.value === 'batch' ? '' : 'none';
});

// Auto-sync shared config on any input change (only config panel fields now)
document.querySelectorAll('.shared-api-key, .shared-base-url, .shared-model-id').forEach(el => {
  el.addEventListener('input', () => {
    if (el.classList.contains('shared-api-key')) sharedConfig.apiKey = el.value.trim();
    if (el.classList.contains('shared-base-url')) sharedConfig.baseUrl = el.value.trim();
    if (el.classList.contains('shared-model-id')) sharedConfig.modelId = el.value.trim();
    syncConfigToUI();
  });
});