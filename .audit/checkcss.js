const fs = require('fs');
const path = require('path');
const base = path.join(__dirname, '..', 'packages', 'novelforge-studio', 'src', 'main', 'resources', 'studio');
const css = fs.readFileSync(path.join(base, 'style.css'), 'utf8');
const html = fs.readFileSync(path.join(base, 'index.html'), 'utf8');
const js = fs.readFileSync(path.join(base, 'app.js'), 'utf8');
const all = css + html + js;

const declared = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map(m => m[1]));
const used = [...all.matchAll(/var\(\s*(--[\w-]+)/g)].map(m => m[1]);
const missing = [...new Set(used)].filter(v => !declared.has(v));
console.log('=== [I] 使用了但未声明的 CSS 变量 (' + missing.length + ') ===');
missing.forEach(v => {
  const n = used.filter(x => x === v).length;
  console.log('  ' + v + '  (引用 ' + n + ' 次)');
});

// --- Scope-aware duplicate selector detection ---
// A selector defined once in global scope AND once inside an @media block is a
// legitimate responsive override, NOT a true duplicate. Only flag selectors
// that appear more than once WITHIN THE SAME scope.
function extractSelectors(text) {
  return [...text.matchAll(/(^|\n)\s*([.#][\w-][^{,\n]*?)\s*\{/g)].map(m => m[2].trim());
}

// 1. Pull out every @media block (balanced braces) and its inner body.
const mediaSpans = [];
const mediaRe = /@media[^{]*\{/g;
let mm;
while ((mm = mediaRe.exec(css)) !== null) {
  const openBrace = mm.index + mm[0].length - 1;
  let d = 1, k = openBrace + 1;
  while (k < css.length && d > 0) {
    if (css[k] === '{') d++;
    else if (css[k] === '}') d--;
    k++;
  }
  mediaSpans.push({ start: mm.index, end: k, label: mm[0].trim(), body: css.slice(openBrace + 1, k - 1) });
}

// 2. Build the global (non-media) css by removing media spans.
let globalCss = '';
let cursor = 0;
mediaSpans.forEach(s => {
  globalCss += css.slice(cursor, s.start);
  cursor = s.end;
});
globalCss += css.slice(cursor);

const counts = {}; // key = scope + '|' + selector
function inc(scope, sel) {
  const key = scope + '|' + sel;
  counts[key] = (counts[key] || 0) + 1;
}
extractSelectors(globalCss).forEach(s => inc('global', s));
mediaSpans.forEach((s, idx) => extractSelectors(s.body).forEach(sel => inc('media#' + idx, sel)));

// 3. A selector is a true duplicate only if it repeats WITHIN the same scope.
const bySel = {};
Object.entries(counts).forEach(([key, c]) => {
  const sel = key.split('|').slice(1).join('|');
  if (c > 1) bySel[sel] = (bySel[sel] || 0) + 1;
});
const dups = Object.entries(bySel).sort((a, b) => b[1] - a[1]);
console.log('\n=== [J] 真正重复的选择器（同作用域内多次定义）(' + dups.length + ') ===');
if (dups.length === 0) console.log('  (无)');
dups.slice(0, 20).forEach(([s, c]) => console.log('  ' + s + '  x' + c));
