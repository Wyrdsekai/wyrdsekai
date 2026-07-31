----------------------- MODULE PeerHandshakeFenced -----------------------
(***************************************************************************)
(* P0 — FENCED variant of PeerHandshake ( Del. A). *)
(*                                                                         *)
(* This is the "what if we fix gaps #2/#3" demonstration spec. It is the    *)
(* same bilateral federation handshake as PeerHandshake.tla, with ONE       *)
(* change: every agreement carries a monotonic EPOCH (a fencing token), and *)
(* every status transition is gated on it. The question this answers:       *)
(*                                                                         *)
(*   Does an epoch fence make the handshake self-consistent on its own —     *)
(*   i.e. does NoHalfOpenLink hold as a pure SAFETY invariant, WITHOUT       *)
(*   depending on the F6 reconcile loop ever running?                        *)
(*                                                                         *)
(* (The bare PeerHandshake violates NoHalfOpenLink in 516 distinct states:   *)
(* a receiver auto-accepts a Propose the sender already retracted, and       *)
(* nothing repairs it unless a reconcile probe happens to run.)              *)
(*                                                                         *)
(* THE FENCE (maps directly to a code change):                              *)
(*   - epoch[z] : the highest agreement epoch zone z has committed to.       *)
(*     In code: a monotonic `epoch` column on bilateral_agreements, carried  *)
(*     on every propose/accept/revoke envelope.                              *)
(*   - Epoch space is PARTITIONED BY ZONE so two independent proposals are   *)
(*     always comparable (no ties): A uses even epochs, B uses odd. In code  *)
(*     this is "(counter, zoneId)" lexicographic ordering — a Lamport-style  *)
(*     tiebreak — so the mesh has a total order on attempts without a shared  *)
(*     counter.                                                              *)
(*   - A receiver acts on a message ONLY if its epoch is not stale:          *)
(*       Propose(e): adopt + go ACTIVE only if e > epoch[self]   (a newer    *)
(*                   attempt); a lower/equal epoch Propose is DROPPED.        *)
(*       Accept(e) : PENDING -> ACTIVE only if e = epoch[self].              *)
(*       Revoke(e) : -> NONE only if e >= epoch[self].                       *)
(*     In code: updateAgreementStatus (and the handleInbound handlers) reject  *)
(*     any transition whose epoch is < the stored epoch — the same idea as the *)
(*     status-guard already shipped for handleInboundAccept, generalised.     *)
(*                                                                         *)
(* NOTE: this spec deliberately has NO reconcile actions. If NoHalfOpenLink  *)
(* holds here, the fence ALONE makes the crossing-message case safe — the    *)
(* reconcile loop (gap #2, a periodic timer) is then only needed to recover  *)
(* from message LOSS, not to paper over a protocol divergence.               *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS MaxMsgs, MaxEpoch   \* MaxEpoch bounds the state space (epochs <= MaxEpoch)

Zones    == {"A", "B"}
Statuses == {"NONE", "PENDING", "ACTIVE", "REVOKED"}

\* Per-zone epoch parity so two independent proposals never collide.
\*   A proposes EVEN epochs, B proposes ODD — a fixed zone-id tiebreak.
NextEpoch(z, e) == IF z = "A"
                     THEN IF e % 2 = 0 THEN e + 2 ELSE e + 1   \* next even > e
                     ELSE IF e % 2 = 1 THEN e + 2 ELSE e + 1   \* next odd  > e

(*--algorithm PeerHandshakeFenced {
  variables
    status = [z \in Zones |-> "NONE"],
    epoch  = [z \in Zones |-> 0],              \* highest agreement epoch committed
    chan   = [from \in Zones |-> [to \in Zones |-> << >>]];  \* FIFO of [k |-> kind, e |-> epoch]

  define {
    Other(z)     == IF z = "A" THEN "B" ELSE "A"
    CanSend(f,t) == Len(chan[f][t]) < MaxMsgs
    Drained      == \A f \in Zones : \A t \in Zones : chan[f][t] = << >>

    TypeOK ==
      /\ status \in [Zones -> Statuses]
      /\ epoch \in [Zones -> 0..MaxEpoch]
      /\ \A f \in Zones : \A t \in Zones :
            /\ Len(chan[f][t]) <= MaxMsgs
            /\ \A i \in 1..Len(chan[f][t]) :
                  /\ chan[f][t][i].k \in {"Propose","Accept","Revoke"}
                  /\ chan[f][t][i].e \in 0..MaxEpoch

    \* With no reconcile actions, NoProbes is vacuously true, so the half-open
    \* invariant reduces to a pure SAFETY claim about the message path:
    \* once nothing is in flight, you can never be stuck one-side-ACTIVE/other-NONE.
    NoHalfOpenLink ==
      Drained =>
        ~ \E z \in Zones : status[z] = "ACTIVE" /\ status[Other(z)] = "NONE"

    NoLostRevoke ==
      Drained =>
        ~ \E z \in Zones : status[z] = "REVOKED" /\ status[Other(z)] = "ACTIVE"
  }

  fair process (zone \in Zones)
  {
    loop: while (TRUE) {
      either {
        \* Propose a FRESH agreement at a new, higher, correctly-par\'d epoch.
        when status[self] \in {"NONE", "REVOKED"}
             /\ CanSend(self, Other(self))
             /\ NextEpoch(self, epoch[self]) <= MaxEpoch;
        \* Bind the new epoch ONCE: PlusCal sequences assignments within a label,
        \* so reading epoch[self] in a later statement would see the bumped value.
        with (ne = NextEpoch(self, epoch[self])) {
          epoch[self]  := ne;
          status[self] := "PENDING";
          chan[self][Other(self)] :=
            Append(chan[self][Other(self)], [k |-> "Propose", e |-> ne]);
        };

      } or {
        \* Receive Propose: accept ONLY a strictly-newer attempt (the fence).
        \* A stale/equal Propose (the sender has since revoked or we already
        \* hold a newer epoch) is DROPPED, not acted on — this is the fix.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]).k = "Propose";
        with (m = Head(chan[Other(self)][self])) {
          if (m.e > epoch[self] /\ CanSend(self, Other(self))) {
            chan := [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                                 ![self][Other(self)] =
                                   Append(chan[self][Other(self)], [k |-> "Accept", e |-> m.e])];
            epoch[self]  := m.e;
            status[self] := "ACTIVE";
          } else {
            \* stale or no room to reply: drop the head, no state change
            chan[Other(self)][self] := Tail(chan[Other(self)][self]);
          }
        };

      } or {
        \* Receive Accept: PENDING -> ACTIVE only for OUR current epoch.
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]).k = "Accept";
        with (m = Head(chan[Other(self)][self])) {
          chan[Other(self)][self] := Tail(chan[Other(self)][self]);
          if (m.e = epoch[self] /\ status[self] = "PENDING") {
            status[self] := "ACTIVE";
          };
        };

      } or {
        \* Revoke: tell the peer at our current epoch.
        when status[self] \in {"ACTIVE", "PENDING"} /\ CanSend(self, Other(self));
        status[self] := "REVOKED";
        chan[self][Other(self)] :=
          Append(chan[self][Other(self)], [k |-> "Revoke", e |-> epoch[self]]);

      } or {
        \* Receive Revoke: drop to NONE only if not stale (>= our epoch).
        when chan[Other(self)][self] # << >>
             /\ Head(chan[Other(self)][self]).k = "Revoke";
        with (m = Head(chan[Other(self)][self])) {
          chan[Other(self)][self] := Tail(chan[Other(self)][self]);
          if (m.e >= epoch[self]) {
            epoch[self]  := m.e;
            status[self] := "NONE";
          };
        };
      }
    }
  }
}
*)
\* BEGIN TRANSLATION (chksum(pcal) = "190a0eed" /\ chksum(tla) = "805acabb")
VARIABLES status, epoch, chan

(* define statement *)
Other(z)     == IF z = "A" THEN "B" ELSE "A"
CanSend(f,t) == Len(chan[f][t]) < MaxMsgs
Drained      == \A f \in Zones : \A t \in Zones : chan[f][t] = << >>

TypeOK ==
  /\ status \in [Zones -> Statuses]
  /\ epoch \in [Zones -> 0..MaxEpoch]
  /\ \A f \in Zones : \A t \in Zones :
        /\ Len(chan[f][t]) <= MaxMsgs
        /\ \A i \in 1..Len(chan[f][t]) :
              /\ chan[f][t][i].k \in {"Propose","Accept","Revoke"}
              /\ chan[f][t][i].e \in 0..MaxEpoch




NoHalfOpenLink ==
  Drained =>
    ~ \E z \in Zones : status[z] = "ACTIVE" /\ status[Other(z)] = "NONE"

NoLostRevoke ==
  Drained =>
    ~ \E z \in Zones : status[z] = "REVOKED" /\ status[Other(z)] = "ACTIVE"


vars == << status, epoch, chan >>

ProcSet == (Zones)

Init == (* Global variables *)
        /\ status = [z \in Zones |-> "NONE"]
        /\ epoch = [z \in Zones |-> 0]
        /\ chan = [from \in Zones |-> [to \in Zones |-> << >>]]

zone(self) == \/ /\ status[self] \in {"NONE", "REVOKED"}
                    /\ CanSend(self, Other(self))
                    /\ NextEpoch(self, epoch[self]) <= MaxEpoch
                 /\ LET ne == NextEpoch(self, epoch[self]) IN
                      /\ epoch' = [epoch EXCEPT ![self] = ne]
                      /\ status' = [status EXCEPT ![self] = "PENDING"]
                      /\ chan' = [chan EXCEPT ![self][Other(self)] = Append(chan[self][Other(self)], [k |-> "Propose", e |-> ne])]
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]).k = "Propose"
                 /\ LET m == Head(chan[Other(self)][self]) IN
                      IF m.e > epoch[self] /\ CanSend(self, Other(self))
                         THEN /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self]),
                                                      ![self][Other(self)] =
                                                        Append(chan[self][Other(self)], [k |-> "Accept", e |-> m.e])]
                              /\ epoch' = [epoch EXCEPT ![self] = m.e]
                              /\ status' = [status EXCEPT ![self] = "ACTIVE"]
                         ELSE /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                              /\ UNCHANGED << status, epoch >>
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]).k = "Accept"
                 /\ LET m == Head(chan[Other(self)][self]) IN
                      /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                      /\ IF m.e = epoch[self] /\ status[self] = "PENDING"
                            THEN /\ status' = [status EXCEPT ![self] = "ACTIVE"]
                            ELSE /\ TRUE
                                 /\ UNCHANGED status
                 /\ epoch' = epoch
              \/ /\ status[self] \in {"ACTIVE", "PENDING"} /\ CanSend(self, Other(self))
                 /\ status' = [status EXCEPT ![self] = "REVOKED"]
                 /\ chan' = [chan EXCEPT ![self][Other(self)] = Append(chan[self][Other(self)], [k |-> "Revoke", e |-> epoch[self]])]
                 /\ epoch' = epoch
              \/ /\ chan[Other(self)][self] # << >>
                    /\ Head(chan[Other(self)][self]).k = "Revoke"
                 /\ LET m == Head(chan[Other(self)][self]) IN
                      /\ chan' = [chan EXCEPT ![Other(self)][self] = Tail(chan[Other(self)][self])]
                      /\ IF m.e >= epoch[self]
                            THEN /\ epoch' = [epoch EXCEPT ![self] = m.e]
                                 /\ status' = [status EXCEPT ![self] = "NONE"]
                            ELSE /\ TRUE
                                 /\ UNCHANGED << status, epoch >>

Next == (\E self \in Zones: zone(self))

Spec == /\ Init /\ [][Next]_vars
        /\ \A self \in Zones : WF_vars(zone(self))

\* END TRANSLATION 
==========================================================================
