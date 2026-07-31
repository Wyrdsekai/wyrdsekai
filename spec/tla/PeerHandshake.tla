-------------------------- MODULE PeerHandshake --------------------------
(***************************************************************************)
(* P0 spike (, Deliverable A). *)
(*                                                                         *)
(* Models the bilateral federation agreement between two zones (A, B) as a *)
(* two-party mutual-consent handshake over FIFO channels, with the F6      *)
(* stale-state reconciliation probe.                                       *)
(*                                                                         *)
(* Java this models:                                                       *)
(*   between/.../federation/FederationActor.java                           *)
(*   - doProposeFresh        -> status NONE/REVOKED -> PENDING + Propose    *)
(*   - handleProposal/accept -> PENDING -> ACTIVE + Accept                  *)
(*   - doRevoke              -> any -> REVOKED + Revoke                     *)
(*   - F6 probe + reconcile  -> partner's status overrides local stale     *)
(*                                                                         *)
(* Safety properties (TraceFix checks safety only):                        *)
(*   TypeOK          - status/channels well-formed                         *)
(*   NoHalfOpenLink  - never one side ACTIVE while the other is NONE with   *)
(*                     no message in flight and no probe pending (a stuck   *)
(*                     half-open agreement that F6 reconcile must prevent). *)
(*   NoLostRevoke    - if either side has REVOKED and the channels are      *)
(*                     drained, the peer is never durably ACTIVE.           *)
(*                                                                         *)
(* The goal of the spike is to prove the TLA+/TLC toolchain + CI wiring     *)
(* end-to-end on a real (if small) wyrdsekai protocol.                      *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS MaxMsgs   \* per-channel queue bound (channel-depth B_c in TraceFix)

Zones    == {"A", "B"}
Statuses == {"NONE", "PENDING", "ACTIVE", "REVOKED"}

\* Message kinds on the directed channels between the two zones.
\*   Propose : "I have opened a PENDING agreement with you"
\*   Accept  : "I have moved us to ACTIVE"
\*   Revoke  : "I have REVOKED"
\*   Probe   : F6 — "what is your status for me?"
\*   State(s): F6 — "my status for you is s"

(*--algorithm PeerHandshake {
  variables
    status = [z \in Zones |-> "NONE"],   \* each zone's local view of the agreement
    \* directed FIFO channels: chan[from][to]
    chan   = [from \in Zones |-> [to \in Zones |-> << >>]],
    probePending = [z \in Zones |-> FALSE];  \* F6: this zone has an outstanding probe

  define {
    TypeOK ==
      /\ status \in [Zones -> Statuses]
      /\ probePending \in [Zones -> BOOLEAN]
      /\ \A f \in Zones : \A t \in Zones : Len(chan[f][t]) <= MaxMsgs

    Other(z)     == IF z = "A" THEN "B" ELSE "A"
    CanSend(f,t) == Len(chan[f][t]) < MaxMsgs
    Drained      == \A f \in Zones : \A t \in Zones : chan[f][t] = << >>
    NoProbes     == \A z \in Zones : ~probePending[z]

    \* A "stuck half-open" agreement: one side committed ACTIVE, the other
    \* has no record at all, and nothing is in flight to repair it.
    NoHalfOpenLink ==
      Drained /\ NoProbes =>
        ~ \E z \in Zones : status[z] = "ACTIVE" /\ status[Other(z)] = "NONE"

    \* A revoke is never silently lost: once drained + reconciled, you can't
    \* have one side REVOKED and the other still ACTIVE.
    NoLostRevoke ==
      Drained /\ NoProbes =>
        ~ \E z \in Zones : status[z] = "REVOKED" /\ status[Other(z)] = "ACTIVE"
  }

  \* Each zone runs the same coordination process.
  fair process (zone \in Zones)
  {
    loop: while (TRUE) {
      either {
        \* Propose: open a fresh PENDING from NONE/REVOKED (doProposeFresh).
        when status[self] \in {"NONE", "REVOKED"} /\ CanSend(self, Other(self));
        status[self] := "PENDING";
        chan[self][Other(self)] := Append(chan[self][Other(self)], "Propose");

      } or {
        \* Receive Propose: accept -> ACTIVE (auto-accept path).
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "Propose"
             /\ CanSend(self, Other(self));
        \* Atomic receive+send: one update to `chan` (PlusCal forbids two
        \* assignments to the same variable in a single step).
        chan := [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                             ![self][Other(self)] = Append(chan[self][Other(self)], "Accept")];
        status[self] := "ACTIVE";

      } or {
        \* Receive Accept: move PENDING -> ACTIVE.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "Accept";
        chan[Other(self)][self] := Tail(chan[Other(self)][self]);
        status[self] := IF status[self] = "PENDING" THEN "ACTIVE" ELSE status[self];

      } or {
        \* Revoke: any ACTIVE/PENDING -> REVOKED, tell the peer.
        when status[self] \in {"ACTIVE", "PENDING"} /\ CanSend(self, Other(self));
        status[self] := "REVOKED";
        chan[self][Other(self)] := Append(chan[self][Other(self)], "Revoke");

      } or {
        \* Receive Revoke: drop to NONE (agreement gone).
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "Revoke";
        chan[Other(self)][self] := Tail(chan[Other(self)][self]);
        status[self] := "NONE";

      } or {
        \* F6: send a state probe (only one outstanding at a time).
        when ~probePending[self] /\ CanSend(self, Other(self));
        probePending[self] := TRUE;
        chan[self][Other(self)] := Append(chan[self][Other(self)], "Probe");

      } or {
        \* Receive Probe: reply with our current status for the peer.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "Probe"
             /\ CanSend(self, Other(self));
        chan := [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                             ![self][Other(self)] = Append(chan[self][Other(self)],
                                IF status[self] = "ACTIVE" THEN "StateActive"
                                ELSE IF status[self] = "REVOKED" THEN "StateRevoked"
                                ELSE "StateNone")];

      } or {
        \* Receive StateActive: peer is ACTIVE — reconcile our stale view up.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "StateActive";
        chan[Other(self)][self] := Tail(chan[Other(self)][self]);
        probePending[self] := FALSE;
        status[self] := IF status[self] = "NONE" THEN "ACTIVE" ELSE status[self];

      } or {
        \* Receive StateRevoked: peer revoked — reconcile our stale ACTIVE down.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "StateRevoked";
        chan[Other(self)][self] := Tail(chan[Other(self)][self]);
        probePending[self] := FALSE;
        status[self] := IF status[self] = "ACTIVE" THEN "REVOKED" ELSE status[self];

      } or {
        \* Receive StateNone: peer has no record — clear our probe.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]) = "StateNone";
        chan[Other(self)][self] := Tail(chan[Other(self)][self]);
        probePending[self] := FALSE;
      }
    }
  }
}
*)
\* BEGIN TRANSLATION (chksum(pcal) = "3dd580f6" /\ chksum(tla) = "456896af")
VARIABLES status, chan, probePending

(* define statement *)
TypeOK ==
  /\ status \in [Zones -> Statuses]
  /\ probePending \in [Zones -> BOOLEAN]
  /\ \A f \in Zones : \A t \in Zones : Len(chan[f][t]) <= MaxMsgs

Other(z)     == IF z = "A" THEN "B" ELSE "A"
CanSend(f,t) == Len(chan[f][t]) < MaxMsgs
Drained      == \A f \in Zones : \A t \in Zones : chan[f][t] = << >>
NoProbes     == \A z \in Zones : ~probePending[z]



NoHalfOpenLink ==
  Drained /\ NoProbes =>
    ~ \E z \in Zones : status[z] = "ACTIVE" /\ status[Other(z)] = "NONE"



NoLostRevoke ==
  Drained /\ NoProbes =>
    ~ \E z \in Zones : status[z] = "REVOKED" /\ status[Other(z)] = "ACTIVE"


vars == << status, chan, probePending >>

ProcSet == (Zones)

Init == (* Global variables *)
        /\ status = [z \in Zones |-> "NONE"]
        /\ chan = [from \in Zones |-> [to \in Zones |-> << >>]]
        /\ probePending = [z \in Zones |-> FALSE]

zone(self) == \/ /\ status[self] \in {"NONE", "REVOKED"} /\ CanSend(self, Other(self))
                 /\ status' = [status EXCEPT ![self] = "PENDING"]
                 /\ chan' = [chan EXCEPT ![self][Other(self)] = Append(chan[self][Other(self)], "Propose")]
                 /\ UNCHANGED probePending
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "Propose"
                    /\ CanSend(self, Other(self))
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                                         ![self][Other(self)] = Append(chan[self][Other(self)], "Accept")]
                 /\ status' = [status EXCEPT ![self] = "ACTIVE"]
                 /\ UNCHANGED probePending
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "Accept"
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                 /\ status' = [status EXCEPT ![self] = IF status[self] = "PENDING" THEN "ACTIVE" ELSE status[self]]
                 /\ UNCHANGED probePending
              \/ /\ status[self] \in {"ACTIVE", "PENDING"} /\ CanSend(self, Other(self))
                 /\ status' = [status EXCEPT ![self] = "REVOKED"]
                 /\ chan' = [chan EXCEPT ![self][Other(self)] = Append(chan[self][Other(self)], "Revoke")]
                 /\ UNCHANGED probePending
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "Revoke"
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                 /\ status' = [status EXCEPT ![self] = "NONE"]
                 /\ UNCHANGED probePending
              \/ /\ ~probePending[self] /\ CanSend(self, Other(self))
                 /\ probePending' = [probePending EXCEPT ![self] = TRUE]
                 /\ chan' = [chan EXCEPT ![self][Other(self)] = Append(chan[self][Other(self)], "Probe")]
                 /\ UNCHANGED status
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "Probe"
                    /\ CanSend(self, Other(self))
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                                         ![self][Other(self)] = Append(chan[self][Other(self)],
                                            IF status[self] = "ACTIVE" THEN "StateActive"
                                            ELSE IF status[self] = "REVOKED" THEN "StateRevoked"
                                            ELSE "StateNone")]
                 /\ UNCHANGED <<status, probePending>>
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "StateActive"
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                 /\ probePending' = [probePending EXCEPT ![self] = FALSE]
                 /\ status' = [status EXCEPT ![self] = IF status[self] = "NONE" THEN "ACTIVE" ELSE status[self]]
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "StateRevoked"
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                 /\ probePending' = [probePending EXCEPT ![self] = FALSE]
                 /\ status' = [status EXCEPT ![self] = IF status[self] = "ACTIVE" THEN "REVOKED" ELSE status[self]]
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]) = "StateNone"
                 /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                 /\ probePending' = [probePending EXCEPT ![self] = FALSE]
                 /\ UNCHANGED status

Next == (\E self \in Zones: zone(self))

Spec == /\ Init /\ [][Next]_vars
        /\ \A self \in Zones : WF_vars(zone(self))

\* END TRANSLATION 
==========================================================================
