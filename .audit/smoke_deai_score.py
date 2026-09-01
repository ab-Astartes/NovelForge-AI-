"""冒烟：/api/deai/score 对 AI 腔文本与人写文本的判别力对比。"""
import json
import sys
import urllib.request

BASE = "http://localhost:8977"
BOOK = "C:/Users/13631/NovelForge/books/SmokeAI3X"

AI_LIKE = (
    "值得注意的是，这场战斗不仅仅是力量的较量，更像是命运的安排。"
    "总而言之，他心中五味杂陈，百感交集。"
    "因此，他深吸一口气，缓缓闭上了双眼。"
    "与此同时，空气中弥漫着浓重的血腥气息，气氛凝重到了极点。"
    "毫无疑问，这是一场不可避免的对决，也是宿命的必然结果。"
    "众所周知，真正的强者不仅需要强大的实力，而且需要坚定的意志。"
    "在这个瞬间，时间仿佛静止了，仿佛整个世界都在注视着他。"
    "总而言之，无论结果如何，他都将义无反顾地走下去。"
    "此外，他的眼神中闪过一丝决然，仿佛在诉说着某种无声的誓言。"
    "由此可见，命运的齿轮已经开始转动，一切都已注定。"
    "事实上，他早已做好了准备，实际上他从未退缩过。"
    "总的来说，这不仅仅是生死的搏杀，更像是一种信念的碰撞。"
)

HUMAN_LIKE = (
    "刀来了。\n"
    "他偏头，刀锋擦着耳根过去，削断几缕头发，钉进身后的柱子，嗡嗡直颤。\n"
    "没时间想。他一矮身，肩膀顶进对方怀里，两人一起撞翻了桌子，碗碟碎了一地。\n"
    "你疯了？\n"
    "疯？也许吧。他只觉得手心全是汗，握不住刀柄，就反手把刀背磕在对方膝弯上。骨头发出的声音让他自己都牙酸。\n"
    "那人跪下去，又撑着地要起来。他退了半步，喘。\n"
    "屋外雨大，压住了别的所有声音。他听见自己的心跳，一下，一下，撞得肋骨发麻。\n"
    "血从袖口往下滴，在手背上洇开一小片。他这才觉出疼。\n"
    "死了？没有。指头动了动。\n"
    "他一屁股坐在碎瓷片上，笑出声，笑得胸口发闷，最后变成咳。\n"
    "算了。他抹了把脸，把刀插回鞘里，站起来，腿软。\n"
)


def post(path, obj):
    """POST 并返回解析后的 JSON。4xx 属预期（如空文本/越界路径），需正常读取错误体。"""
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(obj).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"__httpError": e.code, "__body": raw[:200]}


def show(title, d):
    print(f"\n===== {title} =====")
    if not d.get("ok"):
        print("  失败:", d.get("error"))
        return None
    print(f"  总分 {d['score']}  等级 {d['level']}  {d['verdict']}"
          f"{'  [样本偏短]' if d.get('lowConfidence') else ''}")
    st = d["stats"]
    print(f"  字数 {st['chars']} 句 {st['sentences']} 段 {st['paragraphs']} "
          f"平均句长 {st['avgSentenceLen']}")
    print("  维度：")
    for dim in d["dimensions"]:
        print(f"    {dim['label']:<8} 值={dim['display']:<12} 分={dim['score']:>5} "
              f"权重={dim['weight']} 判定={dim['verdict']}")
    if d["hits"]:
        top = "、".join(f"{h['phrase']}×{h['count']}" for h in d["hits"][:8])
        print(f"  命中({len(d['hits'])}): {top}")
    print("  建议: " + (d["advice"][0] if d["advice"] else "无"))
    return d["score"]


def main():
    ai = show("AI 腔文本", post("/api/deai/score", {"path": BOOK, "text": AI_LIKE}))
    hu = show("人写文本", post("/api/deai/score", {"path": BOOK, "text": HUMAN_LIKE}))

    print("\n===== 边界与异常 =====")
    print("  空文本:",
          post("/api/deai/score", {"path": BOOK, "text": ""}))
    print("  越界路径:",
          post("/api/deai/score", {"path": "C:/Windows/System32", "text": AI_LIKE}))

    if ai is not None and hu is not None:
        print(f"\n===== 结论：AI {ai} vs 人写 {hu}，分差 {round(ai - hu, 1)} =====")
        ok = ai > hu and (ai - hu) >= 20
        print("  判别力：" + ("通过" if ok else "不足，需调参"))
        return 0 if ok else 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
