// Tier 1 universal compute demo.
//
// Demonstrates that an item can use world.math.* and world.json.* without
// declaring any capabilities — these are implicit Tier 1 surfaces every
// item gets for free. No steward consent, no rate limits.
exports.manifest = {
  name: "calculator",
  version: "1.0.0",
  description: "A pocket calculator: math + JSON serialization, no capabilities required.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} taps a few keys on the pocket calculator — soft click of brass, a totted-up figure on the display."
  },
  // Items-as-tools contract — invoke() reads structured params (params.op +
  // params.values, etc.), not the args string; the bare entry runs the
  // default op (sum over provided values).
  commands: [
    { label: "Tally values (default op: sum)", args: "" },
    { label: "Evaluate an expression (e.g. 17 * 3)", args: "<expression>" }
  ],
  // The schema the MODEL sees. Previously this item advertised one undescribed
  // free-form `query`, so the drive model packed a whole sentence into it —
  // {query: "What does the calculator return for multiplying 17 by 3, and confirm
  // this result with a numeric string."} — because no typed slot existed to hold an
  // expression. Give it one and say what belongs in it.
  // `expression` is REQUIRED, and that is the whole point.
  //
  // second-node 2026-07-13, same build, same model, same turn: morning_briefing declares
  // `address` as required and the model filled it ("San Francisco, CA 94102"); the
  // calculator declared everything optional and the model called it with NOTHING —
  // {"action":"calculator"} — so it fell to op=sum over an empty list and refused.
  // An optional parameter is a parameter the model will not fill. Every tool needs
  // one required anchor: the thing it cannot do its job without.
  //
  // ONE required param, and therefore ONE call shape. This is the correction to the
  // 2026-07-13 "every tool needs a required anchor" rule, which was right about the
  // disease and wrong about the dose.
  //
  // The schema used to ALSO advertise {op, values} for statistics. So the tool had two
  // legitimate shapes and exactly one required slot — and on home-server, 2026-07-14, asked for
  // the standard deviation of a list, the model did the only thing it could:
  //
  //   {"action":"calculator","expression":"{values=[12.0, 47.0, ...], op=\"stddev\"}"}
  //
  // It knew the right call was {op:"stddev", values:[…]} and it PACKED THAT INTO THE
  // REQUIRED STRING, because a required param is one the model must fill. The call was
  // then rejected as unparseable and the companion had to tell her bondholder she'd
  // failed. She was not confused; she was obeying two rules that contradicted each other.
  //
  // A required anchor only works when the tool has ONE shape. So statistics moved INTO the
  // expression language as functions — stddev(12, 47, 8) — and op/values left the
  // model-facing schema entirely. invoke() still honours {op, values} for programmatic
  // callers (pinboards, other scripts); the model simply is no longer told about a second
  // door it cannot fit through.
  params: [
    { name: "expression", type: "string", required: true,
      description: "The calculation to evaluate. Arithmetic: \"17 * 3\", \"(2 + 3) * 4\", "
                 + "\"2 ^ 37\". Statistics over a list, as a function call: "
                 + "\"stddev(12, 47, 8, 93)\", \"mean(1, 2, 3)\", \"median(...)\", "
                 + "\"sum(...)\", \"min(...)\", \"max(...)\". Also sqrt(x), pow(x, y). "
                 + "Send the calculation ONLY — never a sentence." }
  ]
};

// Arithmetic the model actually asks for. The drive model reaches for the
// calculator with a natural-language `query` ("multiply 17 by 3"), not with
// {op, values} — so an expression parser is the primary entry, and the
// structured op/values form stays for callers that build params properly.
//
// 2026-07-13: before this existed, "what is 17 times 3?" produced
// {ok:true, op:"sum", result:0} — the NL query was ignored, `op` defaulted to
// sum, `values` defaulted to [], and sum([]) = 0 was reported as SUCCESS. A
// wrong answer dressed as a right one is worse than an error, so an
// unparseable request must now fail loudly (ok:false) rather than tally
// nothing and call it zero.
var OPERATORS = ["+", "-", "*", "/", "^", "%"];

// Map the WORD forms of operators onto symbols. Kept deliberately small.
//
// This is not a natural-language engine and must not grow into one. Every phrasing
// added here is a chance to mis-read operand ORDER and hand back a confident wrong
// number — which is the exact failure this whole change exists to kill. So the rule
// is: only phrasings whose operand order is unambiguous. "10 less than 50" is NOT
// (it reads right-to-left), so "less" is deliberately absent; a query using it fails
// loudly and the caller is told to send an expression.
//
// Surrounding prose needs no stripping — the tokenizer below extracts only numbers
// and operators, and a stray extra number leaves the RPN stack unbalanced, which
// fails rather than guesses.
function normalizeExpression(text) {
  return String(text)
    .toLowerCase()
    // "subtract 8 from 20" means 20 - 8, not 8 - 20 — the one reversed phrasing
    // common enough to be worth handling explicitly rather than refusing.
    .replace(/\b(?:subtract|take)\s+(\d+\.?\d*)\s+from\s+(\d+\.?\d*)/g, " $2 - $1 ")
    .replace(/\bmultiplied\s+by\b|\bmultiplying\b|\bmultiply\b|\btimes\b/g, "*")
    .replace(/\bdivided\s+by\b|\bdividing\b|\bdivide\b/g, "/")
    .replace(/\bplus\b|\badding\b|\badd\b/g, "+")
    .replace(/\bminus\b|\bsubtracting\b|\bsubtract\b/g, "-")
    .replace(/\bto\s+the\s+power\s+of\b|\braised\s+to\b/g, "^")
    .replace(/\bmodulo\b|\bmod\b/g, "%")
    .replace(/\bby\b|\band\b/g, " ")
    .replace(/[?!,]/g, " ")
    .trim();
}

/**
 * The model phrases operations in PREFIX form — "multiplying 17 by 3" normalizes to
 * "* 17 3" — while the evaluator below is infix. Rewrite `op a b` to `a op b`.
 * Guarded so a leading unary minus ("-5 + 3" → ["-","5","+","3"]) is left alone:
 * the swap only fires when an operator is followed by TWO numbers.
 */
function prefixToInfix(tokens) {
  if (tokens.length >= 3
      && OPERATORS.indexOf(tokens[0]) >= 0
      && /^\d/.test(tokens[1])
      && /^\d/.test(tokens[2])) {
    return [tokens[1], tokens[0]].concat(tokens.slice(2));
  }
  return tokens;
}

// Function-call form: stddev(12, 47, 8), mean(1,2,3), sqrt(16), pow(2, 37).
//
// This is how statistics reach the calculator now that `expression` is the ONE call shape
// (see the manifest note). A model asked for "the standard deviation of 12, 47, 8" has
// somewhere to put that request; before, it had only an arithmetic-operator grammar that
// could not express it, so it stuffed a {op, values} object into the string and failed.
//
// Deliberately top-level only: a whole expression is either a function call or arithmetic.
// Nesting a call inside arithmetic ("stddev(1,2) * 3") is not supported and fails loudly
// rather than half-parsing — a wrong number returned confidently is the failure this file
// exists to prevent.
// Aliases matter. Told the function was `stddev(...)`, the model wrote `std([12.0, 47.0, …])` —
// the shorthand it knows from numpy, with a LIST literal for the args. Refusing that is the tool
// being pedantic about spelling while the intent is unmistakable. Accept the names she uses.
var FUNCTIONS = {
  stddev: function (v) { return world.math.stddev(v); },
  std:    function (v) { return world.math.stddev(v); },
  stdev:  function (v) { return world.math.stddev(v); },
  mean:   function (v) { return world.math.mean(v); },
  avg:    function (v) { return world.math.mean(v); },
  average: function (v) { return world.math.mean(v); },
  median: function (v) { return world.math.median(v); },
  sum:    function (v) { return world.math.sum(v); },
  total:  function (v) { return world.math.sum(v); },
  min:    function (v) { return world.math.min(v); },
  max:    function (v) { return world.math.max(v); },
  sqrt:   function (v) { return world.math.sqrt(v[0]); },
  pow:    function (v) { return world.math.pow(v[0], v[1]); },
  product: function (v) { var p = 1; for (var i = 0; i < v.length; i++) p *= v[i]; return p; }
};

function evaluateFunctionCall(text) {
  var m = /^\s*([a-z_]+)\s*\(([^()]*)\)\s*$/i.exec(String(text));
  if (!m) return null;
  var name = m[1].toLowerCase();
  var fn = FUNCTIONS[name];
  if (!fn) return null;

  // "std([12, 47, 8])" — she passes a list literal, as she would to numpy. The brackets carry no
  // meaning we need; strip them rather than fail on them.
  var argText = m[2].replace(/[\[\]]/g, " ");
  var parts = argText.split(",");
  var values = [];
  for (var i = 0; i < parts.length; i++) {
    var raw = parts[i].trim();
    if (raw === "") continue;
    var n = parseFloat(raw);
    if (isNaN(n)) return null;             // not a number list — fail, don't guess
    values.push(n);
  }
  if (values.length === 0) return null;
  if ((name === "sqrt" && values.length !== 1)
      || (name === "pow" && values.length !== 2)) return null;

  var result = fn(values);
  if (result === null || result === undefined || isNaN(result)) return null;
  return { expression: name + "(" + values.join(", ") + ")", result: result };
}

// Shunting-yard → RPN → evaluate. Deliberately tiny and total: it accepts only
// numbers, the operators above, and parentheses. Anything else is a parse
// failure, NOT a zero.
function evaluateExpression(text) {
  var called = evaluateFunctionCall(text);
  if (called) return called;

  var norm = normalizeExpression(text);
  var tokens = norm.match(/\d+\.?\d*|[-+*/^%()]/g);
  if (!tokens || tokens.length === 0) return null;
  tokens = prefixToInfix(tokens);
  // Reject a bare number with no operation — "17" is not a calculation, and
  // silently echoing it back would be another quiet wrong answer.
  var hasOp = false;
  for (var t = 0; t < tokens.length; t++) {
    if (OPERATORS.indexOf(tokens[t]) >= 0) { hasOp = true; break; }
  }
  if (!hasOp) return null;

  var prec = { "+": 1, "-": 1, "*": 2, "/": 2, "%": 2, "^": 3 };
  var out = [], ops = [];
  for (var i = 0; i < tokens.length; i++) {
    var tok = tokens[i];
    if (/^\d/.test(tok)) {
      out.push(parseFloat(tok));
    } else if (tok === "(") {
      ops.push(tok);
    } else if (tok === ")") {
      while (ops.length && ops[ops.length - 1] !== "(") out.push(ops.pop());
      if (!ops.length) return null;          // unbalanced
      ops.pop();
    } else if (prec[tok]) {
      while (ops.length && prec[ops[ops.length - 1]] >= prec[tok] && tok !== "^") {
        out.push(ops.pop());
      }
      ops.push(tok);
    } else {
      return null;
    }
  }
  while (ops.length) {
    var op = ops.pop();
    if (op === "(") return null;             // unbalanced
    out.push(op);
  }

  var stack = [];
  for (var j = 0; j < out.length; j++) {
    var o = out[j];
    if (typeof o === "number") { stack.push(o); continue; }
    if (stack.length < 2) return null;
    var b = stack.pop(), a = stack.pop();
    if (o === "+") stack.push(a + b);
    else if (o === "-") stack.push(a - b);
    else if (o === "*") stack.push(a * b);
    else if (o === "/") { if (b === 0) return null; stack.push(a / b); }
    else if (o === "%") { if (b === 0) return null; stack.push(a % b); }
    else if (o === "^") stack.push(Math.pow(a, b));
    else return null;
  }
  if (stack.length !== 1 || !isFinite(stack[0])) return null;
  // Render the expression from the CANONICAL tokens, never from the normalized text.
  // The normalizer leaves the surrounding prose in place ("...confirm this result with
  // a numeric string"), and this string is spoken — echoing that back at the user is
  // the same raw-plumbing leak the digest guard exists to prevent.
  return { expression: renderExpression(tokens), result: stack[0] };
}

/** "17 * 3" from the parsed tokens — spaces around operators, none inside parens. */
function renderExpression(tokens) {
  var out = "";
  for (var i = 0; i < tokens.length; i++) {
    var t = tokens[i];
    if (t === "(") out += "(";
    else if (t === ")") out = out.replace(/\s+$/, "") + ")";
    else if (OPERATORS.indexOf(t) >= 0) out += " " + t + " ";
    else out += t;
  }
  return out.replace(/\s+/g, " ").replace(/\(\s+/g, "(").trim();
}

// Supported ops: sum, mean, median, stddev, clamp, sqrt, pow, json_diff.
// The script returns a JSON-stringified result for diegetic readability —
// pinboards and chat surfaces render it directly.
// A stringified {op, values} that arrived in the expression slot. The schema no longer
// offers op/values to the model, but a model that has seen the older shape (or reasons its
// way to it) may still pack it into the required string — that is exactly what happened on
// home-server. Recognising it costs nothing and turns a hard failure into the answer she asked
// for. Recovering an unambiguous intent is not guessing; it is reading.
function unwrapStructuredCall(text) {
  var s = String(text);
  var opMatch = /["']?op["']?\s*[:=]\s*["']?([a-z_]+)["']?/i.exec(s);
  var valMatch = /["']?values["']?\s*[:=]\s*\[([^\]]*)\]/i.exec(s);
  if (!opMatch || !valMatch) return null;

  var values = [];
  var parts = valMatch[1].split(",");
  for (var i = 0; i < parts.length; i++) {
    var raw = parts[i].trim();
    if (raw === "") continue;
    var n = parseFloat(raw);
    if (isNaN(n)) return null;             // anything unparseable → fail, don't guess
    values.push(n);
  }
  if (values.length === 0) return null;
  return { op: opMatch[1].toLowerCase(), values: values };
}

// What the PERSON asked for, in their own words → {op, values}.
//
// Applied ONLY to params.query (the human's request), never to the model's own output. That
// distinction is the whole safety property: a human asking "what's the standard deviation of
// 12, 47, 8?" names exactly one operation, while a model's Python names four (sum, mean,
// variance, sqrt) and picking one of those would be a guess. We read the request, not the code.
//
// Refuses on ambiguity: if the sentence names two different statistics, or names none, or
// carries fewer than two numbers, we return null and the caller fails loudly. A confident
// wrong number is worse than an honest failure.
var STAT_PHRASES = [
  { re: /\bstandard\s+deviation\b|\bstd\s*dev(iation)?\b|\bstddev\b|\bstdev\b|\bstd\b/, op: "stddev" },
  { re: /\bmedian\b/,                                        op: "median" },
  { re: /\baverage\b|\bmean\b|\bavg\b/,                       op: "mean" },
  { re: /\bsum\b|\btotal\b/,                                  op: "sum" },
  { re: /\bsmallest\b|\bminimum\b|\bmin\b/,                   op: "min" },
  { re: /\blargest\b|\bmaximum\b|\bmax\b/,                    op: "max" }
];

function statsFromRequest(text) {
  var s = String(text).toLowerCase();

  var matched = [];
  for (var i = 0; i < STAT_PHRASES.length; i++) {
    if (STAT_PHRASES[i].re.test(s)) matched.push(STAT_PHRASES[i].op);
  }
  // Exactly one intent, or we do not know what was wanted.
  if (matched.length !== 1) return null;

  var nums = s.match(/-?\d+\.?\d*/g);
  if (!nums || nums.length < 2) return null;

  var values = [];
  for (var j = 0; j < nums.length; j++) values.push(parseFloat(nums[j]));
  return { op: matched[0], values: values };
}

function invoke(params) {
  params = params || {};

  // Expression path — what the model actually sends. Covers params.query
  // ("multiply 17 by 3"), params.expression, and the bare args string.
  var freeText = params.query || params.expression || params.args || params.text;

  if (freeText && !params.op) {
    var packed = unwrapStructuredCall(freeText);
    if (packed) { params.op = packed.op; params.values = packed.values; freeText = null; }
  }

  if (freeText && !params.op) {
    var evaluated = evaluateExpression(freeText);
    if (evaluated) {
      return {
        ok: true,
        op: "evaluate",
        expression: evaluated.expression,
        result: evaluated.result,
        summary: evaluated.expression + " = " + evaluated.result
      };
    }

    // Last resort, and the most important one: ask the PERSON what they wanted.
    //
    // home-server 2026-07-14. Asked for the standard deviation of a list, the model did not use the
    // stddev(…) syntax I had invented for it. It wrote Python:
    //
    //   "import math; data = [12.0, 47.0, …]; mean = sum(data)/len(data);
    //    variance = sum((x-mean)**2 for x in data)/(len(data)-1); math.sqrt(variance)"
    //
    // Of course it did. Given one free-text slot and a statistics problem, a language model
    // reaches for the language it knows — not for a DSL described in a tool schema. I had
    // replaced one wrong guess about how it would call the tool with another wrong guess,
    // and my test passed because I tested the call *I* invented rather than the call *she
    // makes*.
    //
    // We cannot safely interpret that code (it mentions sum, mean, variance AND sqrt — picking
    // one would be a confident wrong answer, the exact failure this file exists to prevent).
    // But we do not have to: the dispatcher already hands us `params.query`, the human's own
    // words — "what's the standard deviation of 12, 47, 8, 93, 21, 66, 5?" — and THAT is
    // unambiguous. The person's request is the ground truth for intent; the model's code is
    // just its attempt to serve it. When the attempt is unreadable, read the request instead.
    // Prefer the human's words; fall back to whatever text we were given. (freeText may ALREADY
    // be params.query — the dispatcher puts the request there — so never gate on them differing.)
    var fromRequest = statsFromRequest(params.query || freeText);
    if (fromRequest) {
      params.op = fromRequest.op;
      params.values = fromRequest.values;
      freeText = null;
    } else {
      return {
        ok: false,
        error: "I couldn't read that as a calculation: \"" + String(freeText) + "\". "
             + "Send the calculation only — an expression like \"17 * 3\", or a function "
             + "like \"stddev(12, 47, 8)\" / \"mean(1, 2, 3)\". Not code, not a sentence."
      };
    }
  }

  var op = params.op || "sum";
  var values = params.values || [];

  // A tally op with nothing to tally is a malformed request, not a zero.
  //
  // The message is written for the CALLER that got it wrong — which in practice is the
  // model — so it names the parameter that actually exists now. It said "pass `query`"
  // long after `query` stopped being the contract, and that text reaches a human's ear
  // when the tool-failure line is spoken. Say the thing that would fix the call.
  var TALLY_OPS = ["sum", "mean", "median", "stddev", "min", "max", "quantile"];
  if (TALLY_OPS.indexOf(op) >= 0 && (!values || values.length === 0)) {
    return {
      ok: false,
      error: "I need something to calculate. Send an expression, "
           + "e.g. {expression: \"17 * 3\"} — or, for statistics, an op with a "
           + "non-empty values list, e.g. {op: \"mean\", values: [1, 2, 3]}."
    };
  }

  // Elementwise arithmetic over `values`.
  //
  // These were missing, and their absence was found the hard way: asked for 48273 × 9182,
  // a companion called {op: "mul", values: [48273, 9182]} — the structured form this tool
  // itself advertises — and was refused, because the op table held only AGGREGATES
  // (sum/mean/median/stddev/…). There was no way to multiply two numbers through the very
  // parameter shape the error message tells you to use. She reached for the obvious op,
  // the published contract didn't honour it, and she had to tell her bondholder she'd
  // failed. The instinct was right and the tool was pedantic; a calculator that cannot
  // multiply is not a calculator. Aliases included because the model will reasonably say
  // any of these.
  var ARITH = {
    mul: "*", multiply: "*", product: "*", times: "*",
    sub: "-", subtract: "-", minus: "-", difference: "-",
    div: "/", divide: "/", quotient: "/",
    add: "+", plus: "+"
  };
  if (ARITH[op]) {
    if (!values || values.length < 2) {
      return { ok: false, error: "op \"" + op + "\" needs at least two values, "
                                 + "e.g. {op: \"" + op + "\", values: [48273, 9182]}." };
    }
    var sym = ARITH[op];
    var acc = Number(values[0]);
    for (var i = 1; i < values.length; i++) {
      var n = Number(values[i]);
      if (sym === "*") acc = acc * n;
      else if (sym === "-") acc = acc - n;
      else if (sym === "/") {
        if (n === 0) return { ok: false, error: "division by zero" };
        acc = acc / n;
      } else acc = acc + n;
    }
    var expr = values.join(" " + sym + " ");
    return { ok: true, op: op, expression: expr, result: acc,
             summary: expr + " = " + acc };
  }

  var result;
  switch (op) {
    case "sum":      result = world.math.sum(values); break;
    case "mean":     result = world.math.mean(values); break;
    case "median":   result = world.math.median(values); break;
    case "stddev":   result = world.math.stddev(values); break;
    case "min":      result = world.math.min(values); break;
    case "max":      result = world.math.max(values); break;
    case "clamp":    result = world.math.clamp(params.value, params.lo, params.hi); break;
    case "sqrt":     result = world.math.sqrt(params.value); break;
    case "pow":      result = world.math.pow(params.base, params.exp); break;
    case "quantile": result = world.math.quantile(values, params.q || 0.5); break;
    case "json_diff":
      var diff = world.json.diff(params.a, params.b);
      return { ok: true, op: op, diff: diff,
               summary: world.json.stringify(diff, true) };
    default:
      return { ok: false, error: "unknown op: " + op + " — valid ops: mul/div/add/sub{values}, sum, mean, median, stddev, min, max, clamp{value,lo,hi}, sqrt{value}, pow{base,exp}, quantile{values,q}, diff{a,b}" };
  }

  return {
    ok: true,
    op: op,
    result: result,
    summary: world.json.stringify({ op: op, result: result })
  };
}
