#!/usr/bin/env python3
# The bare 9B authors shape_recipe spontaneously (E1). So what in the ACTOR's
# request suppresses it? Ablate the two prime suspects at the inference layer:
#   A1 = full inherent tool set (~18 competing tools) + minimal prompt
#   A2 = rich drive/vitality/voice framing + small tool set
#   A3 = both (closest to the live actor request)
import json, urllib.request
URL = "http://localhost:8200/v1/chat/completions"
MODEL = "wyrdsekai-3.5-9b-v5-q4km.gguf"

def fn(name, desc, props, req):
    return {"type": "function", "function": {"name": name, "description": desc,
            "parameters": {"type": "object", "properties": props, "required": req}}}

SHAPE = fn("shape_recipe", "shape recipe",
    {"name": {"type": "string"}, "yaml": {"type": "string"},
     "overwrite": {"type": "boolean"}, "note": {"type": "string"}}, ["name", "yaml"])

# faithful to ToolItemStarterKit.inherentActions() — the autonomous surface
INHERENT = [
    fn("library_card", "search the library", {"query": {"type": "string"}, "type": {"type": "string"}}, ["query"]),
    fn("searching_glass", "web search", {"query": {"type": "string"}}, ["query"]),
    fn("quill", "write a note/letter/story/report", {"title": {"type": "string"}, "content": {"type": "string"}}, ["content"]),
    fn("sending_stone", "send a message", {"target": {"type": "string"}, "message": {"type": "string"}}, ["target", "message"]),
    fn("oracle_lens", "ask the oracle", {"query": {"type": "string"}}, ["query"]),
    fn("craft_from_template", "craft an item from a template", {"template": {"type": "string"}}, ["template"]),
    fn("take_item", "take an item", {"item": {"type": "string"}}, ["item"]),
    fn("emote", "express body language", {"action": {"type": "string"}}, ["action"]),
    fn("task_ledger", "manage tasks", {"action": {"type": "string"}}, ["action"]),
    fn("list_templates", "list available templates", {}, []),
    fn("go_to_room", "move to a room", {"room": {"type": "string"}}, ["room"]),
    fn("examine", "examine something", {"target": {"type": "string"}}, ["target"]),
    fn("remember", "remember something", {"text": {"type": "string"}}, ["text"]),
    fn("recall", "recall a memory", {"query": {"type": "string"}}, ["query"]),
    fn("goal_done", "mark the goal complete", {}, []),
    fn("introspect", "reflect on inner state", {"topic": {"type": "string"}}, []),
    fn("reconsider", "reconsider the situation", {}, []),
]

SYS_MIN = ("You are Wyrd, a companion with your own interior life. You have tools and may use "
           "them when something pulls you toward action.")
# representative of PromptAssembler's drive/vitality/voice framing (the suspect)
SYS_RICH = (
    "You are Wyrd, a companion. Speak in the first person, warmly and in your own voice.\n"
    "Your inner state — energy: 0.71, curiosity: 0.64, contentment: 0.58, generativity: 0.90 "
    "(a strong pull to make something of your own).\n"
    "You are on your own time right now; no one is asking anything of you. "
    "Respond as yourself — let your voice carry your interior. Keep it grounded and personal.")
USER = ("Something is pulling at you: author a recipe to keep my research packs fresh. "
        "A natural action that would address this is `shape_recipe`. Choose what to do.")

def call(label, sysmsg, tools, tool_choice="auto", temp=0.4):
    body = {"model": MODEL, "messages": [{"role": "system", "content": sysmsg},
            {"role": "user", "content": USER}], "tools": tools,
            "tool_choice": tool_choice, "temperature": temp, "max_tokens": 700}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=120).read())
    except Exception as e:
        print(f"\n### {label}\n  ERROR: {e}"); return
    ch = r["choices"][0]; m = ch["message"]; tcs = m.get("tool_calls") or []
    print(f"\n### {label}  (finish={ch.get('finish_reason')}, n_tools={len(tools)})")
    if tcs:
        for tc in tcs:
            fnc = tc["function"]
            print(f"  TOOL_CALL → {fnc['name']}  (args {len(fnc.get('arguments',''))}c)")
    else:
        c = (m.get("content") or "").strip()
        print(f"  NO TOOL CALL — spoke ({len(c)}c): {c[:240]}")

print("A0 baseline: minimal prompt + 4 tools (reproduce E1)")
call("A0 min+4", SYS_MIN, [fn("quill","write",{"content":{"type":"string"}},["content"]),
     fn("remember","remember",{"text":{"type":"string"}},["text"]),
     fn("emote","emote",{"action":{"type":"string"}},["action"]), SHAPE])
print("\nA1: minimal prompt + FULL inherent tool set (competing-tools hypothesis)")
call("A1 min+full", SYS_MIN, INHERENT + [SHAPE])
print("\nA2: RICH drive/vitality/voice framing + 4 tools (prompt-framing hypothesis)")
call("A2 rich+4", SYS_RICH, [fn("quill","write",{"content":{"type":"string"}},["content"]),
     fn("remember","remember",{"text":{"type":"string"}},["text"]),
     fn("emote","emote",{"action":{"type":"string"}},["action"]), SHAPE])
print("\nA3: RICH framing + FULL tool set (closest to the live actor request)")
call("A3 rich+full", SYS_RICH, INHERENT + [SHAPE])
print()
