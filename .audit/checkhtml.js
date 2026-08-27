const fs = require('fs');
const path = require('path');
const base = path.join(__dirname, '..', 'packages', 'novelforge-studio', 'src', 'main', 'resources', 'studio');
const html = fs.readFileSync(path.join(base, 'index.html'), 'utf8');
const lines = html.split('\n');

// duplicate attributes within a single tag
const tagRe = /<([a-zA-Z][\w-]*)((?:\s+[^<>]*?)?)\/?>/g;
let m, dupTags = [];
while ((m = tagRe.exec(html)) !== null) {
  const attrs = m[2] || '';
  const names = [...attrs.matchAll(/(?:^|\s)([a-zA-Z-]+)\s*=/g)].map(x => x[1].toLowerCase());
  const dup = names.filter((n, i) => names.indexOf(n) !== i);
  if (dup.length) {
    const line = html.slice(0, m.index).split('\n').length;
    dupTags.push({ line, tag: m[1], dup: [...new Set(dup)].join(','), snippet: m[0].slice(0, 120) });
  }
}
console.log('=== [F] 同一标签重复属性 (' + dupTags.length + ') ===');
dupTags.forEach(d => console.log('  L' + d.line + ' <' + d.tag + '> 重复:' + d.dup + '\n      ' + d.snippet));

// tag balance
const voids = new Set(['br','hr','img','input','meta','link','source','area','base','col','embed','param','track','wbr']);
const stack = [];
const errs = [];
const re2 = /<(\/?)([a-zA-Z][\w-]*)([^<>]*?)(\/?)>/g;
while ((m = re2.exec(html)) !== null) {
  const closing = m[1] === '/', name = m[2].toLowerCase(), selfClose = m[4] === '/';
  if (voids.has(name) || selfClose) continue;
  const line = html.slice(0, m.index).split('\n').length;
  if (!closing) stack.push({ name, line });
  else {
    if (!stack.length) { errs.push('L' + line + ' 多余闭合 </' + name + '>'); continue; }
    if (stack[stack.length - 1].name === name) stack.pop();
    else {
      const idx = stack.map(s => s.name).lastIndexOf(name);
      if (idx === -1) errs.push('L' + line + ' 闭合 </' + name + '> 无匹配开标签');
      else {
        for (let i = stack.length - 1; i > idx; i--) errs.push('L' + stack[i].line + ' <' + stack[i].name + '> 未闭合（被 L' + line + ' </' + name + '> 截断）');
        stack.length = idx;
      }
    }
  }
}
console.log('\n=== [G] 标签闭合问题 (' + (errs.length + stack.length) + ') ===');
errs.slice(0, 25).forEach(e => console.log('  ' + e));
stack.slice(0, 15).forEach(s => console.log('  L' + s.line + ' <' + s.name + '> 始终未闭合'));

// inline style count (should be in CSS)
const inline = [...html.matchAll(/style="/g)].length;
console.log('\n=== [H] 行内 style 数量: ' + inline + '（建议下沉到 CSS）===');

// display:none via style vs .hidden class
const dispNone = [...html.matchAll(/style="[^"]*display:\s*none/g)].length;
console.log('    其中 display:none 行内写法: ' + dispNone);
