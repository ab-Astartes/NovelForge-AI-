"""扫描 Java 源码中 Map.of/Set.of 的参数个数与重复键风险。

Map.of / Set.of 的已知坑：
  1) 最多只支持 10 组键值对（20 个参数），超出会在运行期抛 IllegalArgumentException
  2) 键重复会直接抛 IllegalArgumentException（不是覆盖，是崩溃）
本脚本只做静态粗略统计，输出超过 8 组的可疑点，供人工复核。
"""
import os
import re
import sys

BACKSLASH = chr(92)
QUOTE = chr(34)


def scan(src):
    """返回 (行号, 调用形式, 顶层参数个数, 源码片段) 列表。"""
    out = []
    for m in re.finditer(r"\b(Map|Set)\.of\s*\(", src):
        i = m.end() - 1
        depth = 0
        j = i
        commas = 0
        while j < len(src):
            c = src[j]
            # 需同时跟踪 {}，否则 new String[]{...} 内的逗号会被误计为顶层参数
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
                if depth == 0:
                    break
            elif c == QUOTE:
                j += 1
                while j < len(src) and src[j] != QUOTE:
                    if src[j] == BACKSLASH:
                        j += 1
                    j += 1
            elif c == "," and depth == 1:
                commas += 1
            j += 1
        nargs = commas + 1
        line = src[: m.start()].count("\n") + 1
        snippet = src[m.start() : j + 1].replace("\n", " ")
        out.append((line, m.group(0), nargs, snippet[:120]))
    return out


def main():
    roots = sys.argv[1:] or ["."]
    suspicious = 0
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            if "target" in dirpath.split(os.sep):
                continue
            for fn in sorted(files):
                if not fn.endswith(".java"):
                    continue
                full = os.path.join(dirpath, fn)
                src = open(full, encoding="utf-8", errors="replace").read()
                for line, call, nargs, snippet in scan(src):
                    pairs = nargs // 2 if call.startswith("Map") else nargs
                    if pairs >= 8:
                        suspicious += 1
                        print(f"{full}:{line}  {call} 参数={nargs} 约{pairs}组")
                        print(f"    {snippet}")
    if suspicious == 0:
        print("OK: 未发现接近上限(>=8组)的 Map.of/Set.of 调用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
