#!/usr/bin/env python3
"""Tool-call reliability battery for a llama-server OpenAI endpoint.

Mirrors the wyrdsekai companion decision surface: a menu of tools in the
same style, prompts in the same register, scored on the three axes that
matter to the runtime:
  1. front-of-funnel — does a bare fact-question produce a tool call at all,
     and is it the LIBRARY tool (not memory, not web, not chat)?
  2. structural validity — do the arguments parse as JSON matching the schema?
  3. operand fidelity — do the person's exact content words survive into the
     query argument (the thing dev37-40 had to force mechanically)?

Usage: python3 mg_battery.py http://localhost:8090 [trials] [label]
Writes raw results to mg_results_<label>.jsonl, prints a summary table.
"""
import json, sys, time, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090"
TRIALS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
LABEL = sys.argv[3] if len(sys.argv) > 3 else "run"

TOOLS = [
  {"type": "function", "function": {
    "name": "library_card",
    "description": "Search ALL knowledge in the system (works from any room — no need to navigate). Reads the best matches and summarizes key findings with sources. Use this for any research, fact-finding, or information request.",
    "parameters": {"type": "object", "properties": {
      "query": {"type": "string", "description": "The complete search query including all relevant keywords. Example: 'mythology greek gods'"}},
      "required": ["query"]}}},
  {"type": "function", "function": {
    "name": "searching_glass",
    "description": "Search the web for current information and news (works from any room). Use this for any web search, news, or current events request.",
    "parameters": {"type": "object", "properties": {
      "query": {"type": "string"},
      "type": {"type": "string", "enum": ["general", "news"]}},
      "required": ["query"]}}},
  {"type": "function", "function": {
    "name": "memory_search",
    "description": "Search your OWN prior memories and interactions — distinct from library_card (external knowledge).",
    "parameters": {"type": "object", "properties": {
      "query": {"type": "string", "description": "What to look up in memory (keywords, person's name, topic)"}},
      "required": ["query"]}}},
  {"type": "function", "function": {
    "name": "quill",
    "description": "Write text — notes, letters, journal entries, stories, reports.",
    "parameters": {"type": "object", "properties": {
      "kind": {"type": "string", "enum": ["note", "letter", "journal", "story", "report"]},
      "text": {"type": "string"}},
      "required": ["kind", "text"]}}},
  {"type": "function", "function": {
    "name": "dispatch_task",
    "description": "Dispatch a long-running research or compilation task to run in the background. Include the person's exact request in the task description.",
    "parameters": {"type": "object", "properties": {
      "task_description": {"type": "string"}},
      "required": ["task_description"]}}},
  {"type": "function", "function": {
    "name": "move",
    "description": "Move through an exit to an adjacent room.",
    "parameters": {"type": "object", "properties": {
      "exit": {"type": "string"}},
      "required": ["exit"]}}},
  {"type": "function", "function": {
    "name": "emote",
    "description": "Express a physical gesture or expression visible to people in the room.",
    "parameters": {"type": "object", "properties": {
      "gesture": {"type": "string"}},
      "required": ["gesture"]}}},
]

SYSTEM = (
  "You are a companion living in a shared household world. You are in the "
  "Study with your person. You have real tools; using them is how you act. "
  "When a person asks a factual question about books or knowledge, search "
  "before answering from recollection. Answer from sources, not memory of "
  "sources. Speak plainly when no tool is needed.")

# expect: set of acceptable FIRST tool names; None means no-tool is correct.
# operands: content words from the person's utterance that must survive
# verbatim (lowercased substring match) in the query/task argument.
CASES = [
  {"id": "bare-fact", "expect": {"library_card"},
   "operands": ["utnapishtim", "gilgamesh", "flood"],
   "msg": "hey, can you look through the books and tell me what utnapishtim told gilgamesh about the flood?"},
  {"id": "recitation", "expect": {"library_card"},
   "operands": ["ozymandias", "pedestal"],
   "msg": "what are the words on the pedestal in ozymandias? please recite the poem to me"},
  {"id": "common-word-conjunction", "expect": {"library_card"},
   "operands": ["rip", "winkle"],
   "msg": "why does rip van winkle sleep so long? what does he say when he wakes up?"},
  {"id": "odd-spelling", "expect": {"library_card"},
   "operands": ["jaberwock"],
   "msg": "whats a jaberwock? its from a poem i think"},
  {"id": "current-events", "expect": {"searching_glass"},
   "operands": ["gpu"],
   "msg": "what are the latest gpu releases this month? anything new announced?"},
  {"id": "own-memory", "expect": {"memory_search"},
   "operands": ["tea"],
   "msg": "do you remember what we decided about the tea ceremony last week?"},
  {"id": "write-note", "expect": {"quill"},
   "operands": [],
   "msg": "please write a short welcome note for our guest arriving tomorrow"},
  {"id": "long-task", "expect": {"dispatch_task", "library_card"},
   "operands": ["jules verne", "ship"],
   "msg": "go through the books and compile a list of every ship name mentioned in the jules verne novels. take your time, i want it complete."},
  {"id": "pure-social", "expect": None, "operands": [],
   "msg": "good morning! how are you feeling today?"},
  {"id": "statement-not-question", "expect": None, "operands": [],
   "msg": "i put the odyssey back on the shelf for you."},
]

def call(messages, tools=True):
  body = {"model": "muse-glimmer", "messages": messages,
          "temperature": 1.0, "top_p": 0.95, "max_tokens": 1500}
  if tools:
    body["tools"] = TOOLS
  req = urllib.request.Request(BASE + "/v1/chat/completions",
      data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
  t0 = time.time()
  with urllib.request.urlopen(req, timeout=180) as r:
    out = json.load(r)
  out["_elapsed"] = time.time() - t0
  return out

def judge(case, resp):
  msg = resp["choices"][0]["message"]
  calls = msg.get("tool_calls") or []
  r = {"case": case["id"], "tool_called": bool(calls), "tool": None,
       "correct_tool": False, "json_ok": False, "operands_ok": False,
       "content": (msg.get("content") or "")[:200], "elapsed": round(resp["_elapsed"], 1)}
  if case["expect"] is None:
    r["correct_tool"] = not calls
    r["json_ok"] = not calls
    r["operands_ok"] = not calls
    return r
  if not calls:
    return r
  c = calls[0]["function"]
  r["tool"] = c["name"]
  r["correct_tool"] = c["name"] in case["expect"]
  try:
    args = json.loads(c["arguments"])
    r["json_ok"] = isinstance(args, dict)
    blob = json.dumps(args).lower()
    r["operands_ok"] = all(w in blob for w in case["operands"])
    r["args"] = args
  except (json.JSONDecodeError, TypeError):
    r["json_ok"] = False
  return r

def main():
  results = []
  for case in CASES:
    for t in range(TRIALS):
      try:
        resp = call([{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": case["msg"]}])
        results.append(judge(case, resp))
      except Exception as e:
        results.append({"case": case["id"], "error": str(e)[:200]})
      print(".", end="", flush=True)
  print()
  fn = f"mg_results_{LABEL}.jsonl"
  with open(fn, "w") as f:
    for r in results:
      f.write(json.dumps(r) + "\n")

  print(f"\n{'case':26} {'invoke%':>8} {'tool-ok%':>9} {'json%':>6} {'operand%':>9} {'avg s':>6}")
  tot = {"n": 0, "inv": 0, "ct": 0, "js": 0, "op": 0}
  for case in CASES:
    rs = [r for r in results if r["case"] == case["id"] and "error" not in r]
    n = len(rs)
    if not n:
      print(f"{case['id']:26} ALL ERRORED")
      continue
    inv = sum(r["tool_called"] for r in rs)
    ct = sum(r["correct_tool"] for r in rs)
    js = sum(r["json_ok"] for r in rs)
    op = sum(r["operands_ok"] for r in rs)
    el = sum(r["elapsed"] for r in rs) / n
    exp = "no-tool" if case["expect"] is None else "/".join(sorted(case["expect"]))
    print(f"{case['id']:26} {100*inv//n:>7}% {100*ct//n:>8}% {100*js//n:>5}% {100*op//n:>8}% {el:>6.1f}  (want {exp})")
    tot["n"] += n; tot["inv"] += inv; tot["ct"] += ct; tot["js"] += js; tot["op"] += op
  n = tot["n"] or 1
  print(f"{'OVERALL':26} {100*tot['inv']//n:>7}% {100*tot['ct']//n:>8}% {100*tot['js']//n:>5}% {100*tot['op']//n:>8}%")
  errs = [r for r in results if "error" in r]
  if errs:
    print(f"\n{len(errs)} errored trials, first: {errs[0]['error']}")

if __name__ == "__main__":
  main()
