"""检测 Set.of(...) 字面量中的重复元素。

Set.of(E...) 在遇到重复元素时会抛 IllegalArgumentException，
且因为是 static final 字段，崩溃发生在类初始化阶段——一旦有人往
姓氏表/停用词表里加了个重复字，整个功能会直接不可用且堆栈难懂。

本脚本把 Set.of(...) 的元素抽出来查重，只报告真有重复的地方。
"""
import os
import re
import sys

BACKSLASH = chr(92)
DQUOTE = chr(34)
SQUOTE = chr(39)


def _skip_string(src, i, quote):
    """src[i] 是 quote 的起始引号，返回结束引号后的下一个下标。"""
    j = i + 1
    while j < len(src):
        if src[j] == BACKSLASH:
            j += 2
            continue
        if src[j] == quote:
            return j + 1
        j += 1
    return len(src)


def _unescape(s):
    return s.replace(BACKSLASH + "n", "\n").replace(BACKSLASH + "t", "\t")


def extract_elements(src, open_paren):
    """抽取 Set.of( ... ) 顶层字面量元素，返回 (元素列表, 结束下标)。"""
    # 从开括号之后开始，深度已在括号内（避免把 '(' 本身并入首个元素）
    depth = 1
    j = open_paren + 1
    elems = []
    buf = []
    while j < len(src):
        c = src[j]
        if c in "([{":
            depth += 1
            buf.append(c)
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
            buf.append(c)
        elif c in (DQUOTE, SQUOTE):
            end = _skip_string(src, j, c)
            # 保留引号原文：后续查重要靠引号判断这是字面量还是变量
            buf.append(src[j:end])
            j = end
            continue
        elif c == "," and depth == 1:
            elems.append("".join(buf).strip())
            buf = []
        else:
            buf.append(c)
        j += 1
    tail = "".join(buf).strip()
    if tail:
        elems.append(tail)
    return [e for e in elems if e], j


def main():
    roots = sys.argv[1:] or ["."]
    found = 0
    checked = 0
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            if "target" in dirpath.split(os.sep):
                continue
            for fn in sorted(files):
                if not fn.endswith(".java"):
                    continue
                full = os.path.join(dirpath, fn)
                src = open(full, encoding="utf-8", errors="replace").read()
                for m in re.finditer(r"\bSet\.of\s*\(", src):
                    elems, _end = extract_elements(src, m.end() - 1)
                    if len(elems) < 2:
                        continue
                    # 元素必须是纯字面量才做查重，含变量的跳过
                    if not all(
                        (e.startswith(DQUOTE) and e.endswith(DQUOTE))
                        or (e.startswith(SQUOTE) and e.endswith(SQUOTE))
                        or re.fullmatch(r"-?\d+", e)
                        for e in elems
                    ):
                        continue
                    checked += 1
                    line = src[: m.start()].count("\n") + 1
                    seen = {}
                    dups = []
                    for e in elems:
                        if e in seen:
                            dups.append(e)
                        seen[e] = True
                    if dups:
                        found += 1
                        print(f"[DUPLICATE] {full}:{line}  Set.of 元素数={len(elems)}")
                        for d in sorted(set(dups)):
                            print(f"    重复元素: {d}")
    print(f"检查 {checked} 个 Set.of 字面量，发现 {found} 处重复。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
