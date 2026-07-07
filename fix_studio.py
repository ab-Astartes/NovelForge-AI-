import pathlib

f = pathlib.Path(r'C:\Users\13631\Desktop\ab\demo\NovelForge\packages\novelforge-studio\src\main\java\com\novelforge\studio\StudioServer.java')
content = f.read_text(encoding='utf-8')

# Pattern in Java source: "{\"error\":\"" + e.getMessage() + "\"}"
old = '{"\\\\"error\\\\":\\""' + ' + e.getMessage() + ' + '"\\"}"'
# Let's just find the exact string in the file and replace
# Looking for: e.getMessage() that appears inside string concatenation for JSON error fields

# Simple approach: replace e.getMessage() with sanitizeForJson(e.getMessage()) 
# when it appears in sendJson calls (not in log statements)
lines = content.split('\n')
count = 0
for i, line in enumerate(lines):
    if 'sendJson(exchange, 500,' in line and 'e.getMessage()' in line:
        lines[i] = line.replace('e.getMessage()', 'sanitizeForJson(e.getMessage())')
        count += 1

content = '\n'.join(lines)
f.write_text(content, encoding='utf-8')
print(f'Replaced {count} instances')
