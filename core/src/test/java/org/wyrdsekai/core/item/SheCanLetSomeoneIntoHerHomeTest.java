package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion must be able to let someone into her own Home — by name, from the stone in
 * it — and take it back.
 *
 * <p>Until 2026-09-13 she could not: her provider's ward verbs answered "not
 * steward-held", and the Ward Stone in her Home was a pebble that "glows when your
 * identity is strong". The Home itself stood open the whole time, so the question never
 * came up. Now the door is hers, and this is the hand that opens it.</p>
 */
class SheCanLetSomeoneIntoHerHomeTest {

    private static final Path STONE = Path.of("../scripts/items/ward_stone.js");

    private record Result(boolean ok, String summary, String error, List<String> grants, List<String> revokes) {}

    @Test
    @DisplayName("invite a companion by name: enter and speak, under both her names")
    void inviteACompanionByName() {
        var r = invoke("{ args: 'invite Ember' }", "home-companion-wisp");
        assertTrue(r.ok(), r.error());
        assertTrue(r.summary().contains("Ember may now enter and speak"), r.summary());
        assertEquals(List.of(
            "did:key:ember|enter", "did:key:ember|speak",
            "companion-ember|enter", "companion-ember|speak"), r.grants());
    }

    @Test
    @DisplayName("invite a person by username or display name: the id the door will see")
    void inviteAPersonByName() {
        var r = invoke("{ args: 'invite kaz' }", "home-companion-wisp");
        assertTrue(r.ok(), r.error());
        assertEquals(List.of("u-kaz|enter", "u-kaz|speak"), r.grants());
        var r2 = invoke("{ args: 'invite Kazuo' }", "home-companion-wisp");
        assertTrue(r2.ok(), r2.error());
        assertEquals(List.of("u-kaz|enter", "u-kaz|speak"), r2.grants());
    }

    @Test
    @DisplayName("the sub-verb travels as mode too, the way the dispatcher sends it")
    void modeShape() {
        var r = invoke("{ mode: 'invite', args: 'Ember' }", "home-companion-wisp");
        assertTrue(r.ok(), r.error());
        assertEquals(4, r.grants().size());
        var one = invoke("{ mode: 'invite', args: 'Ember use' }", "home-companion-wisp");
        assertTrue(one.ok(), one.error());
        assertEquals(List.of("did:key:ember|use", "companion-ember|use"), one.grants());
    }

    @Test
    @DisplayName("uninvite melts every key that person holds")
    void uninviteMeltsEveryKey() {
        var r = invoke("{ args: 'uninvite Ember' }", "home-companion-wisp");
        assertTrue(r.ok(), r.error());
        assertEquals(List.of("did:key:ember|enter", "companion-ember|enter", "companion-ember|speak"), r.revokes());
        assertTrue(r.summary().contains("may no longer come in"), r.summary());
    }

    @Test
    @DisplayName("a name nobody here has is refused with the names she does know")
    void unknownNameIsHonest() {
        var r = invoke("{ args: 'invite Nobody' }", "home-companion-wisp");
        assertFalse(r.ok());
        assertTrue(r.error().contains("Ember"), r.error());
        assertTrue(r.error().contains("Kazuo"), r.error());
        assertTrue(r.grants().isEmpty());
    }

    @Test
    @DisplayName("her own keys are never cut twice nor melted")
    void herOwnKeysAreSafe() {
        assertFalse(invoke("{ args: 'invite Wisp' }", "home-companion-wisp").ok());
        var r = invoke("{ args: 'uninvite Wisp' }", "home-companion-wisp");
        assertFalse(r.ok());
        assertTrue(r.revokes().isEmpty(), "nothing of hers is melted");
    }

    @Test
    @DisplayName("who may enter — one line per person, herself as 'you'")
    void whoMayEnter() {
        var r = invoke("{ args: '' }", "home-companion-wisp");
        assertTrue(r.ok(), r.error());
        assertTrue(r.summary().contains("you — enter, speak, take, drop, use, build, admin"), r.summary());
        assertTrue(r.summary().contains("Ember — enter"), r.summary());
        assertFalse(r.summary().contains("did:key:wisp"), "ids are not names: " + r.summary());
    }

    @Test
    @DisplayName("the stone is cool anywhere but a Home")
    void onlyInAHome() {
        var r = invoke("{ args: 'invite Ember' }", "nexus");
        assertFalse(r.ok());
        assertTrue(r.error().contains("only in a Home"), r.error());
        assertTrue(r.grants().isEmpty());
    }

    @Test
    @DisplayName("a refusal from the gate is rendered, not swallowed")
    void gateRefusalIsRendered() {
        var r = invoke("{ args: 'invite Ember' }", "home-companion-ember");
        assertFalse(r.ok());
        assertTrue(r.error().contains("keeper"), r.error());
    }

    /** A fake world: two companions, one person, a ward table that refuses any room but hers. */
    private static Result invoke(String paramsLiteral, String roomId) {
        try (var ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.eval("js", """
                var exports = {};
                var __grants = [];
                var __revokes = [];
                var __wards = [
                  { subject: "did:key:wisp", capability: "enter" }, { subject: "did:key:wisp", capability: "speak" },
                  { subject: "did:key:wisp", capability: "take" },  { subject: "did:key:wisp", capability: "drop" },
                  { subject: "did:key:wisp", capability: "use" },   { subject: "did:key:wisp", capability: "build" },
                  { subject: "did:key:wisp", capability: "admin" },
                  { subject: "companion-wisp", capability: "enter" }, { subject: "companion-wisp", capability: "admin" },
                  { subject: "did:key:ember", capability: "enter" },
                  { subject: "companion-ember", capability: "enter" }, { subject: "companion-ember", capability: "speak" }
                ];
                var world = {
                  room: { id: function () { return "%ROOM%"; } },
                  self: { did: function () { return "did:key:wisp"; }, name: function () { return "Wisp"; } },
                  companions: { list: function () { return [
                    { name: "Wisp", entityId: "companion-wisp", did: "did:key:wisp" },
                    { name: "Ember", entityId: "companion-ember", did: "did:key:ember" }
                  ]; } },
                  household: { members: function () { return [
                    { id: "u-kaz", username: "kaz", displayName: "Kazuo", role: "member" }
                  ]; } },
                  ward: {
                    list: function (room) { return room === "home-companion-wisp" ? __wards : []; },
                    grant: function (room, subject, cap) {
                      if (room !== "home-companion-wisp") return { ok: false, error: "only the room's keeper holds its keys" };
                      __grants.push(subject + "|" + cap);
                      return { ok: true, created: true };
                    },
                    revoke: function (room, subject, cap) {
                      if (room !== "home-companion-wisp") return { ok: false, error: "only the room's keeper holds its keys" };
                      __revokes.push(subject + "|" + cap);
                      return { ok: true };
                    }
                  }
                };
                """.replace("%ROOM%", roomId));
            ctx.eval("js", Files.readString(STONE));
            var v = ctx.eval("js", "exports.invoke(" + paramsLiteral + ")");
            var ok = v.hasMember("ok") && v.getMember("ok").asBoolean();
            var summary = v.hasMember("summary") ? String.valueOf(v.getMember("summary")) : "";
            var error = v.hasMember("error") ? String.valueOf(v.getMember("error")) : null;
            return new Result(ok, summary, error, strings(ctx, "__grants"), strings(ctx, "__revokes"));
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + STONE.toAbsolutePath(), e);
        }
    }

    private static List<String> strings(Context ctx, String name) {
        var arr = ctx.eval("js", name);
        var out = new ArrayList<String>();
        for (long i = 0; i < arr.getArraySize(); i++) out.add(arr.getArrayElement(i).asString());
        return out;
    }
}
