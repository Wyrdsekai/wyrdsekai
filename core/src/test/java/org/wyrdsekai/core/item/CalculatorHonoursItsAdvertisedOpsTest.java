package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool must honour the operations it advertises.
 *
 * <p>Found live on a clean install, 2026-07-14. Asked for 48273 × 9182, a companion called
 * {@code {"op": "mul", "values": [48273, 9182]}} — the structured form the calculator's own error
 * message tells callers to use — and was refused. The op table held only AGGREGATES (sum, mean,
 * median, stddev, min, max, quantile…). <b>There was no way to multiply two numbers through the
 * very parameter shape the tool published.</b> She reached for the obvious op, the contract did not
 * honour it, and she had to tell her bondholder she had failed.
 *
 * <p>This is the same bug as the hash-ordered tool menu wearing different clothes: the agent
 * behaved sensibly and the system had handed her a false world. She is not the thing to fix.
 * A calculator that cannot multiply is not a calculator.
 *
 * <p>So this test reads the op names the script ADVERTISES (in its {@code op} param description and
 * in its own error strings) and asserts every one of them actually computes. An item may not name
 * an operation it will not perform.
 */
class CalculatorHonoursItsAdvertisedOpsTest {

    private static final Path SCRIPT = Path.of("../scripts/items/calculator.js");

    /** The regression itself: the exact call that failed on home-server. */
    @Test
    void mulIsAnOpBecauseTheModelWillReachForIt() {
        var r = invoke("{ op: 'mul', values: [48273, 9182] }");
        assertTrue(r.ok(),
            "{op:'mul'} must work — a companion asked for 48273 x 9182 exactly this way and the "
                + "tool refused, because its published op list had no multiply in it");
        assertEquals(443242686L, (long)(double) r.result());
    }

    @Test
    void theOtherArithmeticOpsWorkToo() {
        assertEquals(50.0, invoke("{ op: 'sub', values: [80, 30] }").result());
        assertEquals(4.0, invoke("{ op: 'div', values: [20, 5] }").result());
        assertEquals(12.0, invoke("{ op: 'add', values: [5, 7] }").result());
        // The model will say any of these; refusing on the synonym is the same bug again.
        assertEquals(6.0, invoke("{ op: 'multiply', values: [2, 3] }").result());
        assertEquals(6.0, invoke("{ op: 'product', values: [2, 3] }").result());
    }

    /** The expression path — still the primary entry, and still correct. */
    @Test
    void theExpressionPathStillWorks() {
        var r = invoke("{ expression: '48273 * 9182' }");
        assertTrue(r.ok());
        assertEquals(443242686L, (long)(double) r.result());
    }

    /**
     * Statistics now live INSIDE the expression language, because `expression` is the tool's one
     * required param and therefore its one call shape. Before this, a model asked for a standard
     * deviation had no legal way to say so.
     */
    @Test
    void statisticsAreExpressibleAsFunctionCalls() {
        var sd = invoke("{ expression: 'stddev(12, 47, 8, 93, 21, 66, 5)' }");
        assertTrue(sd.ok(), "stddev(...) must evaluate — it is the only shape the model is offered");
        assertEquals(33.7046, sd.result(), 0.01);

        assertEquals(2.0, invoke("{ expression: 'mean(1, 2, 3)' }").result());
        assertEquals(6.0, invoke("{ expression: 'sum(1, 2, 3)' }").result());
        assertEquals(4.0, invoke("{ expression: 'sqrt(16)' }").result());
        assertEquals(8.0, invoke("{ expression: 'pow(2, 3)' }").result());
        assertEquals(9.0, invoke("{ expression: 'max(3, 9, 1)' }").result());
    }

    /**
     * The home-server regression, exactly as it arrived on the wire.
     *
     * <p>Two legal call shapes and one required slot meant the model packed the structured call
     * INTO the required string: {@code expression: "{values=[12.0, 47.0, ...], op=\"stddev\"}"}.
     * It knew the right call and had nowhere to put it. The schema no longer offers a second shape,
     * but a model may still reason its way to the old one — so recognise it and answer, rather than
     * refuse and make her tell her bondholder she failed.
     */
    @Test
    void aStructuredCallPackedIntoTheStringIsUnderstoodNotRefused() {
        var r = invoke("{ expression: '{values=[12.0, 47.0, 8.0, 93.0, 21.0, 66.0, 5.0], op=\"stddev\"}' }");
        assertTrue(r.ok(),
            "this is the exact string the model sent on home-server — refusing it produced 'I tried to "
                + "use my tool for that, but it failed'");
        assertEquals(33.7046, r.result(), 0.01);
    }

    /** Reading an unambiguous intent is fine. GUESSING is not — garbage must still fail loudly. */
    @Test
    void anUnparseableStringStillFailsLoudly() {
        assertTrue(!invoke("{ expression: 'the vibes of the room' }").ok());
        assertTrue(!invoke("{ expression: '{values=[not, numbers], op=\"stddev\"}' }").ok());
    }

    /**
     * THE home-server regression, second edition. Handed one free-text slot and a statistics problem, the
     * model did not use the {@code stddev(…)} syntax I invented — <b>it wrote Python</b>:
     *
     * <pre>
     *   import math; data = [12.0, 47.0, …]; mean = sum(data)/len(data);
     *   variance = sum((x-mean)**2 for x in data)/(len(data)-1); math.sqrt(variance)
     * </pre>
     *
     * <p>Of course it did — a language model reaches for the language it knows, not for a DSL in a
     * tool description. We cannot safely read that code (it names sum, mean, variance AND sqrt;
     * choosing one would be a confident wrong answer). But the human's own question is unambiguous,
     * and the dispatcher already gives it to us as {@code query}. Read the request, not the code.
     */
    @Test
    void whenTheModelWritesCodeWeReadTheHumansQuestionInstead() {
        var r = invoke("""
            { query: "what's the standard deviation of 12, 47, 8, 93, 21, 66, 5?",
              expression: "import math; data = [12.0, 47.0, 8.0, 93.0, 21.0, 66.0, 5.0]; \
mean = sum(data) / len(data); variance = sum((x - mean)**2 for x in data) / (len(data)-1); \
math.sqrt(variance)" }""");
        assertTrue(r.ok(),
            "she asked a clear question and the model answered it in Python — we must still get "
                + "her an answer, not 'I tried to use my tool for that, but it failed'");
        assertEquals(33.7046, r.result(), 0.01);
    }

    /**
     * home-server, third attempt. Told the function was {@code stddev(...)}, the model wrote
     * {@code std([12.0, 47.0, 8.0, 93.0, 21.0, 66.0, 5])} — numpy's shorthand, with a LIST literal.
     * The intent is unmistakable; refusing it is the tool being pedantic about spelling. Every
     * round of this has been the same lesson: <b>accept the call she makes, don't insist on the
     * call you imagined.</b>
     */
    @Test
    void theNumpyShorthandSheActuallyWroteIsAccepted() {
        var r = invoke("{ expression: 'std([12.0, 47.0, 8.0, 93.0, 21.0, 66.0, 5])' }");
        assertTrue(r.ok(), "std([...]) is what she wrote — it must work");
        assertEquals(33.7046, r.result(), 0.01);

        assertEquals(2.0, invoke("{ expression: 'avg([1, 2, 3])' }").result());
        assertEquals(2.0, invoke("{ expression: 'stdev([1, 5, 3, 1, 5])' }").result(), 1.0);
    }

    @Test
    void theHumansPlainQuestionIsUnderstoodForEachStatistic() {
        assertEquals(2.0, invoke("{ query: 'what is the average of 1, 2, 3?', expression: 'zzz' }").result());
        assertEquals(2.0, invoke("{ query: 'median of 1, 2, 3', expression: 'zzz' }").result());
        assertEquals(9.0, invoke("{ query: 'the largest of 3, 9, 1', expression: 'zzz' }").result());
    }

    /**
     * The safety property. We read the PERSON's words, never the model's code — and even then only
     * when the request names exactly ONE statistic. Two intents, or none, means we do not know what
     * was wanted, and a confident wrong number is worse than an honest failure.
     */
    @Test
    void anAmbiguousRequestRefusesRatherThanGuessing() {
        assertTrue(!invoke("{ query: 'give me the mean and the median of 1, 2, 3', expression: 'zzz' }").ok(),
            "two statistics named — we must not silently pick one");
        assertTrue(!invoke("{ query: 'how are you feeling today?', expression: 'zzz' }").ok(),
            "no statistic named — nothing to compute");
        assertTrue(!invoke("{ query: 'the average of 7', expression: 'zzz' }").ok(),
            "one number is not a list");
    }

    /**
     * The model must not be shown a second door it cannot fit through. op/values still WORK for
     * programmatic callers (pinboards, other scripts) — they are simply no longer advertised.
     */
    @Test
    void theModelFacingSchemaOffersExactlyOneShape() throws IOException {
        var src = Files.readString(SCRIPT);
        var schema = src.substring(src.indexOf("params: ["), src.indexOf("};"));
        assertTrue(schema.contains("\"expression\"") || schema.contains("name: \"expression\""),
            "expression must be the anchor");
        assertTrue(!schema.contains("name: \"op\""),
            "op must NOT be in the model-facing schema — a second call shape alongside a single "
                + "required param is what made the model pack {op,values} into the string");
        assertTrue(!schema.contains("name: \"values\""),
            "values must NOT be in the model-facing schema");
    }

    /** Honest failure beats a confident wrong number: no values, no answer. */
    @Test
    void arithmeticWithoutEnoughValuesFailsLoudlyRatherThanGuessing() {
        var r = invoke("{ op: 'mul', values: [7] }");
        assertTrue(!r.ok(),
            "one value is not a multiplication — it must refuse, not silently return 7");
    }

    @Test
    void divisionByZeroRefuses() {
        assertTrue(!invoke("{ op: 'div', values: [4, 0] }").ok());
    }

    /**
     * The contract check. Every op the script NAMES in its own description and error text must
     * actually be implemented — otherwise the tool is teaching the model calls that cannot work.
     */
    @Test
    void everyAdvertisedOpIsAnOpThatActuallyRuns() throws IOException {
        var src = Files.readString(SCRIPT);
        var advertised = new ArrayList<String>();
        // "(ops: mul, div, add, ...)" and "valid ops: mul/div/add/sub{values}, sum, ..."
        var m = Pattern.compile("(?:\\(ops:|valid ops:)([^)\"]+)").matcher(src);
        while (m.find()) {
            // Strip the {arg,arg} shapes FIRST — otherwise splitting "clamp{value,lo,hi}" on the
            // comma invents ops called "lo" and "hi".
            var list = m.group(1).replaceAll("\\{[^}]*\\}", "");
            for (var raw : list.split("[,/]")) {
                var name = raw.trim().toLowerCase(Locale.ROOT);
                if (name.matches("[a-z_]+")) advertised.add(name);
            }
        }
        assertTrue(advertised.size() > 8, "should have parsed the advertised op list, got " + advertised);

        // Ops needing a shape other than {op, values} are exercised by their own cases above.
        var shaped = List.of("clamp", "sqrt", "pow", "quantile", "json_diff", "diff");
        var broken = new ArrayList<String>();
        for (var op : advertised) {
            if (shaped.contains(op)) continue;
            var r = invoke("{ op: '" + op + "', values: [8, 2] }");
            if (!r.ok()) broken.add(op);
        }
        assertTrue(broken.isEmpty(),
            "the calculator ADVERTISES these ops but refuses them: " + broken
                + " — a tool that names an operation it will not perform sends the model down a "
                + "path that cannot work, and the agent takes the blame for the tool's lie");
    }

    // ─── harness ────────────────────────────────────────────────
    // calculator.js's arithmetic path is pure JS; world.math.* is only needed by the aggregate
    // ops, so a thin stub is enough to exercise the whole invoke() surface.
    private static Result invoke(String paramsLiteral) {
        try (var ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.eval("js", """
                var exports = {};
                var world = {
                  math: {
                    sum:  function (v) { var s = 0; for (var i in v) s += Number(v[i]); return s; },
                    mean: function (v) { return world.math.sum(v) / v.length; },
                    median: function (v) { var s = v.slice().sort(function (a,b){return a-b;});
                                           return s[Math.floor(s.length/2)]; },
                    // SAMPLE stddev (n-1) — matches production world.math.stddev, verified live on home-server:
                    // stddev(12,47,8,93,21,66,5) = 33.704599092705436, not the population 31.2.
                    stddev: function (v) { var mu = world.math.mean(v), t = 0;
                                           for (var i in v) t += Math.pow(v[i]-mu, 2);
                                           return Math.sqrt(t / (v.length - 1)); },
                    min:  function (v) { return Math.min.apply(null, v); },
                    max:  function (v) { return Math.max.apply(null, v); },
                    clamp: function (x, lo, hi) { return Math.min(hi, Math.max(lo, x)); },
                    sqrt: function (x) { return Math.sqrt(x); },
                    pow:  function (b, e) { return Math.pow(b, e); },
                    quantile: function (v, q) { var s = v.slice().sort(function (a,b){return a-b;});
                                                return s[Math.floor(q * (s.length - 1))]; }
                  },
                  json: {
                    diff: function (a, b) { return { a: a, b: b }; },
                    stringify: function (o) { return JSON.stringify(o); }
                  }
                };
                """);
            ctx.eval("js", Files.readString(SCRIPT));
            ctx.eval("js", "var __invoke = (typeof invoke === 'function') ? invoke : exports.invoke;");
            // Read the values out BEFORE the context closes — a Value is only live while its
            // context is open, and returning one from inside try-with-resources hands the caller
            // a corpse.
            var v = ctx.eval("js", "__invoke(" + paramsLiteral + ")");
            var ok = v.hasMember("ok") && v.getMember("ok").asBoolean();
            Double result = v.hasMember("result") && v.getMember("result").isNumber()
                ? v.getMember("result").asDouble() : null;
            var error = v.hasMember("error") ? String.valueOf(v.getMember("error")) : null;
            return new Result(ok, result, error);
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + SCRIPT.toAbsolutePath(), e);
        }
    }

    /** A snapshot of invoke()'s return, detached from the (now closed) JS context. */
    private record Result(boolean ok, Double result, String error) {}
}
