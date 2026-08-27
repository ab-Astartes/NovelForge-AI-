// 合并同一标签内重复的 class 属性（HTML 解析器只认第一个，导致 .hidden/.mt-2 等全部失效）
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'packages', 'novelforge-studio', 'src', 'main', 'resources', 'studio', 'index.html');
let html = fs.readFileSync(file, 'utf8');
let fixed = 0;

html = html.replace(/<([a-zA-Z][\w-]*)((?:\s+[^<>]*?)?)(\/?)>/g, (full, tag, attrs, selfClose) => {
  if (!attrs) return full;
  const classVals = [...attrs.matchAll(/(?:^|\s)class\s*=\s*"([^"]*)"/g)].map(m => m[1]);
  if (classVals.length < 2) return full;
  fixed++;
  // 去掉所有 class 属性
  let rest = attrs.replace(/(?:^|\s)class\s*=\s*"[^"]*"/g, '');
  // 合并、去重
  const merged = [...new Set(classVals.join(' ').split(/\s+/).filter(Boolean))].join(' ');
  rest = rest.replace(/\s+/g, ' ').trim();
  return '<' + tag + ' class="' + merged + '"' + (rest ? ' ' + rest : '') + selfClose + '>';
});

fs.writeFileSync(file, html, 'utf8');
console.log('已合并重复 class 属性的标签数: ' + fixed);
