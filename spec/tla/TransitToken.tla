--------------------------- MODULE TransitToken ---------------------------
(***************************************************************************)
(* P1 (, Deliverable A) — cross-zone companion *)
(* relocation handoff (relocate -> arrive -> return).                      *)
(*                                                                         *)
(* Java this models:                                                       *)
(*   core/.../room/ZoneGuardian.java  (RelocateCompanion DEPART / ARRIVE)  *)
(*   between/.../layer/CompanionTransitProtocol.java  (publishTransit)     *)
(*   - DEPART: source captures state, publishes a TransitState to NATS,    *)
(*             then StopForRelocate (stops the local actor).               *)
(*   - ARRIVE: target receives the transit envelope, spawns the actor.     *)
(*   - RETURN: same mechanism in reverse (destination departs back home).  *)
(*                                                                         *)
(* The handoff is a single-owner transfer: the companion must be hosted in *)
(* EXACTLY ONE zone (no duplication — cf #492 "EntityLeft not emitted";    *)
(* no loss). Today the publish is fire-and-forget over core NATS with no   *)
(* ack-before-stop and no epoch/idempotency on the token — so the safety   *)
(* of the transfer depends on NATS never dropping (else LOSS) and never    *)
(* redelivering (else DUPLICATION).                                        *)
(*                                                                         *)
(* Channel reliability is modelled the TraceFix way, as toggles:           *)
(*   AllowDrop       - core NATS at-most-once: a published token may be     *)
(*                     dropped (subscriber briefly disconnected).           *)
(*   AllowRedeliver  - JetStream-style at-least-once: a token may be        *)
(*                     delivered more than once.                            *)
(*                                                                         *)
(* Safety properties (TraceFix checks safety only):                        *)
(*   TypeOK         - well-formedness.                                      *)
(*   NoDuplication  - the companion is hosted in <= 1 zone at all times.    *)
(*   NoLoss         - when the channel is drained, it is hosted somewhere.  *)
(*                                                                         *)
(* Committed gate (.cfg): the RELIABLE configuration (AllowDrop = FALSE,    *)
(* AllowRedeliver = FALSE) — the happy-path handoff is correct, gate green. *)
(* Flip either flag to reproduce the findings (see FINDINGS.md): the bare   *)
(* relocate is neither loss-safe nor duplication-safe on a realistic NATS   *)
(* channel.                                                                 *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS MaxMsgs, AllowDrop, AllowRedeliver

Zones == {"H", "D"}   \* H = canonical home, D = visited destination

(*--algorithm TransitToken {
  variables
    hosted = [z \in Zones |-> (z = "H")],  \* is the live actor present in zone z?
    chan   = << >>;                        \* in-flight transit tokens (target zone ids), FIFO

  define {
    Other(z)  == IF z = "H" THEN "D" ELSE "H"
    Hosts     == { z \in Zones : hosted[z] }

    TypeOK ==
      /\ hosted \in [Zones -> BOOLEAN]
      /\ Len(chan) <= MaxMsgs
      /\ \A i \in 1..Len(chan) : chan[i] \in Zones

    \* The companion is a single-owner entity: never hosted in two zones at once.
    NoDuplication == Cardinality(Hosts) <= 1

    \* No loss: once nothing is in flight, the companion is hosted somewhere.
    NoLoss == (chan = << >>) => Cardinality(Hosts) >= 1
  }

  process (zone \in Zones)
  {
    loop: while (TRUE) {
      either {
        \* DEPART: hosted here, send the companion to the other zone, stop locally.
        \* Fire-and-forget: publish the token AND stop the local actor (atomic in
        \* the model; the Java publishes then stops — the delivery gap is the same).
        when hosted[self] /\ Len(chan) < MaxMsgs;
        hosted[self] := FALSE;
        chan := Append(chan, Other(self));

      } or {
        \* ARRIVE: a token addressed to me is at the head — host the actor here.
        \* `hosted` is a presence BOOLEAN, so `hosted[self] := TRUE` is already
        \* idempotent — it captures the effect of today's re-tether guard
        \* (ZoneGuardian.onRelocateArrive:990 `companions.containsKey(entityId)` =>
        \* no-op spawn). That guard stops a *concurrent in-zone* double-spawn, but
        \* it keys on PRESENCE, not on the token — so it does NOT stop a STALE
        \* token that arrives after this zone has departed (hosted[self]=FALSE
        \* again). That residual is exactly what NoDuplication catches under
        \* AllowRedeliver below: the real fix needs an (entityId, epoch) fence, not
        \* a presence check.
        when chan # << >> /\ Head(chan) = self;
        chan := Tail(chan);
        hosted[self] := TRUE;

      } or {
        \* DROP (AllowDrop): core-NATS at-most-once — the head token is lost.
        when AllowDrop /\ chan # << >>;
        chan := Tail(chan);

      } or {
        \* REDELIVER (AllowRedeliver): at-least-once — duplicate the head token.
        when AllowRedeliver /\ chan # << >> /\ Len(chan) < MaxMsgs;
        chan := Append(chan, Head(chan));
      }
    }
  }
}
*)
\* BEGIN TRANSLATION (chksum(pcal) = "30646ab5" /\ chksum(tla) = "a273a604")
VARIABLES hosted, chan

(* define statement *)
Other(z)  == IF z = "H" THEN "D" ELSE "H"
Hosts     == { z \in Zones : hosted[z] }

TypeOK ==
  /\ hosted \in [Zones -> BOOLEAN]
  /\ Len(chan) <= MaxMsgs
  /\ \A i \in 1..Len(chan) : chan[i] \in Zones


NoDuplication == Cardinality(Hosts) <= 1


NoLoss == (chan = << >>) => Cardinality(Hosts) >= 1


vars == << hosted, chan >>

ProcSet == (Zones)

Init == (* Global variables *)
        /\ hosted = [z \in Zones |-> (z = "H")]
        /\ chan = << >>

zone(self) == \/ /\ hosted[self] /\ Len(chan) < MaxMsgs
                 /\ hosted' = [hosted EXCEPT ![self] = FALSE]
                 /\ chan' = Append(chan, Other(self))
              \/ /\ chan # << >> /\ Head(chan) = self
                 /\ chan' = Tail(chan)
                 /\ hosted' = [hosted EXCEPT ![self] = TRUE]
              \/ /\ AllowDrop /\ chan # << >>
                 /\ chan' = Tail(chan)
                 /\ UNCHANGED hosted
              \/ /\ AllowRedeliver /\ chan # << >> /\ Len(chan) < MaxMsgs
                 /\ chan' = Append(chan, Head(chan))
                 /\ UNCHANGED hosted

Next == (\E self \in Zones: zone(self))

Spec == Init /\ [][Next]_vars

\* END TRANSLATION 
==========================================================================
