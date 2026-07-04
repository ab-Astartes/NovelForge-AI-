// NovelForge Studio — Frontend JS

const API = '';  // same origin

function showPanel(name) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('panel-' + name).classList.add('active');
  document.getElementById('nav-' + name).classList.add('active');
}

// --- Create Book ---
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
      showResult(resultDiv, `✅ "${title}" 创建成功！路径: ${data.path}`, false);
      document.getElementById('book-title').value = '';
      loadBooks(); // refresh book list
      populateBookSelects();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '创建失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// --- Load Books ---
async function loadBooks() {
  const listDiv = document.getElementById('books-list');
  try {
    const res = await fetch(API + '/api/books');
    const books = await res.json();
    if (books.length === 0) {
      listDiv.innerHTML = '<p style="color:var(--dim)">暂无书籍。点击"创建"新建项目。</p>';
    } else {
      listDiv.innerHTML = books.map(b => `
        <div class="book-card">
          <div class="title">${b.title}</div>
          <div class="meta">题材: ${b.genre} | 章节: ${b.chapters} | 路径: ${b.path}</div>
        </div>
      `).join('');
    }
    populateBookSelects(books);
  } catch (e) {
    listDiv.innerHTML = '<p style="color:var(--accent)">加载失败: ' + e.message + '</p>';
  }
}

// --- Populate book selects ---
function populateBookSelects(books) {
  if (!books) {
    fetch(API + '/api/books').then(r => r.json()).then(populateBookSelects);
    return;
  }
  const selects = ['write-book', 'state-book', 'audit-book', 'export-book', 'progress-book'];
  selects.forEach(id => {
    const sel = document.getElementById(id);
    sel.innerHTML = books.map(b => `<option value="${b.path}">${b.title} (${b.genre})</option>`).join('');
  });
}

// --- Write Chapter ---
async function writeChapter() {
  const bookPath = document.getElementById('write-book').value;
  const mode = document.getElementById('write-mode').value;
  const apiKey = document.getElementById('api-key').value.trim();
  const baseUrl = document.getElementById('base-url').value.trim();
  const modelId = document.getElementById('model-id').value.trim();
  const progressDiv = document.getElementById('write-progress');
  const resultDiv = document.getElementById('write-result');

  if (!bookPath) { showResult(resultDiv, '请选择书籍', true); return; }
  if (!apiKey) { showResult(resultDiv, '请输入 API Key', true); return; }

  progressDiv.textContent = '⏳ 正在写作，9 Agent 流水线运行中...（可能需要几分钟）';
  resultDiv.textContent = '';

  try {
    const res = await fetch(API + '/api/write', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: bookPath, mode, apiKey, baseUrl, model: modelId })
    });
    const data = await res.json();
    progressDiv.textContent = '';

    if (data.status === 'ok') {
      let msg = `✅ 第 ${data.chapterNumber} 章已完成！长度: ${data.length} 字`;
      if (data.auditScore) msg += ` | 审计评分: ${data.auditScore}/10`;
      showResult(resultDiv, msg, false);
      loadBooks();
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '写作失败'), true);
    }
  } catch (e) {
    progressDiv.textContent = '';
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// --- Load State ---
async function loadState() {
  const bookPath = document.getElementById('state-book').value;
  const type = document.getElementById('state-type').value;
  const content = document.getElementById('state-content');

  if (!bookPath) { content.textContent = '请选择书籍'; return; }

  try {
    const res = await fetch(API + `/api/state?path=${encodeURIComponent(bookPath)}&type=${type}`);
    const data = await res.json();
    content.textContent = data.summary || '无数据';
  } catch (e) {
    content.textContent = '加载失败: ' + e.message;
  }
}

// --- Config ---
async function saveConfig() {
  const resultDiv = document.getElementById('config-result');
  try {
    const res = await fetch(API + '/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        chapterWordsMin: parseInt(document.getElementById('cfg-min-words').value),
        chapterWordsMax: parseInt(document.getElementById('cfg-max-words').value),
        auditPassThreshold: parseFloat(document.getElementById('cfg-audit-threshold').value),
        maxRevisionPasses: parseInt(document.getElementById('cfg-max-revisions').value)
      })
    });
    const data = await res.json();
    showResult(resultDiv, data.status === 'updated' ? '✅ 配置已更新' : '❌ ' + (data.error || '更新失败'), data.status !== 'updated');
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// --- Audit Chapter ---
async function auditChapter() {
  const bookPath = document.getElementById('audit-book').value;
  const chapterNum = document.getElementById('audit-chapter').value;
  const apiKey = document.getElementById('audit-api-key').value.trim();
  const baseUrl = document.getElementById('audit-base-url').value.trim();
  const modelId = document.getElementById('audit-model').value.trim();
  const resultDiv = document.getElementById('audit-result');

  if (!bookPath) { resultDiv.textContent = '请选择书籍'; return; }
  if (!apiKey) { resultDiv.textContent = '请输入 API Key'; return; }

  resultDiv.textContent = '⏳ 33维审计运行中...';

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

    if (data.status === 'ok') {
      let output = `📊 总分: ${data.overallScore}/10\n`;
      output += `通过: ${data.pass ? '✅ YES' : '❌ NO'}\n\n`;
      if (data.dimensionScores) {
        output += '维度评分:\n';
        for (const [dim, score] of Object.entries(data.dimensionScores)) {
          output += `  ${dim}: ${score}\n`; // i18n: dimension names
        }
      }
      if (data.criticalIssues && data.criticalIssues.length > 0) {
        output += `\n⚠️ 关键问题:\n`;
        data.criticalIssues.forEach(i => output += `  - ${i}\n`);
      }
      if (data.warnings && data.warnings.length > 0) {
        output += `\n💡 建议:\n`;
        data.warnings.forEach(w => output += `  - ${w}\n`);
      }
      resultDiv.textContent = output;
    } else {
      resultDiv.textContent = '❌ ' + (data.error || '审计失败');
    }
  } catch (e) {
    resultDiv.textContent = '❌ 网络错误: ' + e.message;
  }
}

// --- Export Book ---
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
      showResult(resultDiv, `✅ 导出成功: ${data.outputPath}`, false);
    } else {
      showResult(resultDiv, '❌ ' + (data.error || '导出失败'), true);
    }
  } catch (e) {
    showResult(resultDiv, '❌ 网络错误: ' + e.message, true);
  }
}

// --- Load Progress ---
async function loadProgress() {
  const bookPath = document.getElementById('progress-book').value;
  const result = document.getElementById('progress-result');
  if (!bookPath) { result.textContent = '请选择书籍'; return; }
  try {
    const res = await fetch(API + `/api/progress?path=${encodeURIComponent(bookPath)}`);
    const data = await res.json();
    result.textContent = `📊 写作进度\n` +
      `总章节: ${data.totalChapters}\n` +
      `总字数: ${data.totalWords}\n` +
      `平均字数/章: ${data.averageWordsPerChapter}\n` +
      `已审计: ${data.auditedChapters}/${data.totalChapters}\n` +
      `已通过: ${data.passedChapters}/${data.totalChapters}`;
  } catch (e) {
    result.textContent = '加载失败: ' + e.message;
  }
}

// --- Helpers ---
function showResult(div, msg, isError) {
  div.textContent = msg;
  div.className = 'result ' + (isError ? 'error' : 'success');
}

// --- Init ---
loadBooks();
