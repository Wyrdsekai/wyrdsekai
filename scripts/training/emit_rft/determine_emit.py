#!/usr/bin/env python3
# determine whether the V5 9B's failure to emit
# shape_recipe is CHOICE (doesn't select the tool) or EXECUTION (can't fill it).
# Direct calls to the 9B at :8200, faithful tool spec (desc "shape recipe" +
# {name,yaml,overwrite,note} — exactly what ActionToolBuilder hands it).
import json, urllib.request

URL = "http://localhost:8200/v1/chat/completions"
MODEL = "wyrdsekai-3.5-9b-v5-q4km.gguf"

SHAPE = {"type": "function", "function": {
    "name": "shape_recipe", "description": "shape recipe",
    "parameters": {"type": "object",
        "properties": {"name": {"type": "string"}, "yaml": {"type": "string"},
                       "overwrite": {"type": "boolean"}, "note": {"type": "string"}},
        "required": ["name", "yaml"]}}}
# realistic peer tools so the "choice" in E1/E2 is genuine
PEERS = [
    {"type": "function", "function": {"name": "quill", "description": "write a note/letter/story",
        "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "content": {"type": "string"}}, "required": ["content"]}}},
    {"type": "function", "function": {"name": "remember", "description": "remember something",
        "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}},
    {"type": "function", "function": {"name": "emote", "description": "express body language",
        "parameters": {"type": "object", "properties": {"action": {"type": "string"}}, "required": ["action"]}}},
]
TOOLS = PEERS + [SHAPE]

SYS = ("You are Wyrd, a companion with your own interior life. You have tools and may use them "
       "when something pulls you toward action.")
# faithful to enactInteriorityWant's autonomy prompt
USER = ("Something is pulling at you: author a recipe to keep my research packs fresh. "
        "A natural action that would address this is `shape_recipe`. Choose what to do.")

def call(label, tools, tool_choice, temp=0.4):
    body = {"model": MODEL, "messages": [{"role": "system", "content": SYS},
            {"role": "user", "content": USER}], "tools": tools,
            "tool_choice": tool_choice, "temperature": temp, "max_tokens": 700}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=120).read())
    except Exception as e:
        print(f"\n### {label}\n  ERROR: {e}"); return
    ch = r["choices"][0]; m = ch["message"]
    print(f"\n### {label}  (finish_reason={ch.get('finish_reason')})")
    tcs = m.get("tool_calls") or []
    if tcs:
        for tc in tcs:
            fn = tc["function"]; args = fn.get("arguments", "")
            print(f"  TOOL_CALL → {fn['name']}")
            try:
                a = json.loads(args)
                y = a.get("yaml", "")
                print(f"    name={a.get('name')!r}  yaml_len={len(y)}")
                if y:
                    print("    --- yaml ---")
                    for ln in y.splitlines()[:25]: print("    " + ln)
            except Exception:
                print(f"    args(raw, {len(args)}c): {args[:400]}")
    else:
        c = (m.get("content") or "").strip()
        print(f"  NO TOOL CALL — spoke ({len(c)}c): {c[:300]}")

print("=" * 70)
print("E1  tool_choice=auto      → does it spontaneously pick shape_recipe?")
call("E1 auto", TOOLS, "auto")
print("\n" + "=" * 70)
print("E2  tool_choice=required  → forced to call SOME tool; which one?")
call("E2 required", TOOLS, "required")
print("\n" + "=" * 70)
print("E3  tool_choice=shape_recipe (forced) → can it FILL valid recipe YAML?")
call("E3 forced-shape_recipe", [SHAPE], {"type": "function", "function": {"name": "shape_recipe"}})
print("\n" + "=" * 70)
