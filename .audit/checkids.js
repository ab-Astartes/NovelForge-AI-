const fs = require('fs');
const path = require('path');
const base = path.join(__dirname, '..', 'packages', 'novelforge-studio', 'src', 'main', 'resources', 'studio');
const js = ['app.js', 'graph.js', 'factionmap.js', 'naming.js', 'glossary.js', 'ledger.js']
  .map(f => fs.readFileSync(path.join(base, f), 'utf8')).join('\n');
const html = fs.readFileSync(path.join(base, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(base, 'style.css'), 'utf8');

const htmlIds = new Set([...html.matchAll(/id="([^"]+)"/g)].map(m => m[1]));
const jsCreated = new Set([...js.matchAll(/\.id\s*=\s*['"]([^'"]+)['"]/g)].map(m => m[1]));
[...js.matchAll(/id="([^"'+]+)"/g)].forEach(m => jsCreated.add(m[1]));
[...js.matchAll(/id='([^'"+]+)'/g)].forEach(m => jsCreated.add(m[1]));

const used = [...js.matchAll(/getElementById\(\s*['"]([^'"]+)['"]\s*\)/g)].map(m => m[1]);
const missing = [...new Set(used)].filter(id => !htmlIds.has(id) && !jsCreated.has(id));
console.log('HTML ids: ' + htmlIds.size + ' | JS 引用唯一 id: ' + new Set(used).size);
console.log('\n=== [A] 前端引用但不存在的 DOM id (' + missing.length + ') ===');
missing.forEach(id => {
  const lines = js.split('\n').map((l, i) => (l.includes("'" + id + "'") || l.includes('"' + id + '"')) ? i + 1 : 0).filter(Boolean);
  console.log('  ' + id + '  -> app.js:' + lines.slice(0, 5).join(','));
});

const handlers = [...new Set([...html.matchAll(/on(?:click|change|input|submit|keyup)="([a-zA-Z_$][\w$]*)\(/g)].map(m => m[1]))];
const defined = new Set([...js.matchAll(/(?:^|\n)\s*(?:async\s+)?function\s+([\w$]+)/g)].map(m => m[1]));
[...js.matchAll(/(?:const|let|var|window\.)\s*([\w$]+)\s*=\s*(?:async\s*)?(?:function|\()/g)].forEach(m => defined.add(m[1]));
const undef = handlers.filter(h => !defined.has(h));
console.log('\n=== [B] HTML 绑定但 JS 未定义的函数 (' + undef.length + ') ===');
undef.forEach(h => console.log('  ' + h));

// duplicate function definitions
const fnNames = [...js.matchAll(/(?:^|\n)(?:async\s+)?function\s+([\w$]+)/g)].map(m => m[1]);
const dupes = fnNames.filter((n, i) => fnNames.indexOf(n) !== i);
console.log('\n=== [C] 重复定义的函数 (' + new Set(dupes).size + ') ===');
[...new Set(dupes)].forEach(n => {
  const lines = js.split('\n').map((l, i) => new RegExp('^(async )?function ' + n + '\\b').test(l) ? i + 1 : 0).filter(Boolean);
  console.log('  ' + n + '  -> 行 ' + lines.join(', '));
});

// html classes not in css
const usedClasses = new Set();
[...html.matchAll(/class="([^"]+)"/g)].forEach(m => m[1].split(/\s+/).forEach(c => c && usedClasses.add(c)));
const cssClasses = new Set([...css.matchAll(/\.([a-zA-Z][\w-]*)/g)].map(m => m[1]));
const noCss = [...usedClasses].filter(c => !cssClasses.has(c));
console.log('\n=== [D] HTML 用到但 CSS 无定义的 class (' + noCss.length + ') ===');
console.log('  ' + noCss.join(', '));

// panels vs nav
const panels = [...html.matchAll(/id="panel-([\w-]+)"/g)].map(m => m[1]);
const navs = [...new Set([...html.matchAll(/showPanel\('([\w-]+)'\)/g)].map(m => m[1]))];
console.log('\n=== [E] 面板 vs 导航 ===');
console.log('  已定义 section: ' + panels.join(', '));
console.log('  导航调用: ' + navs.join(', '));
console.log('  ! 导航调用但无 section: ' + navs.filter(n => !panels.includes(n)).join(', ') || '  无');
