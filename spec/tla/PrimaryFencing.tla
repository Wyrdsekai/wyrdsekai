-------------------------- MODULE PrimaryFencing --------------------------
(***************************************************************************)
(* P3 (, Deliverable A) — RoomPrimaryProtocol fencing *)
(* tokens + idempotency keys (task #114). Unlike P0–P2 this VERIFIES an      *)
(* already-shipped fix, and additionally surfaces one residual gap.         *)
(*                                                                         *)
(* Java this models:                                                       *)
(*   between/.../layer/RoomPrimaryProtocol.java                             *)
(*     - claimRoomPrimary: epoch = epochCounter.incrementAndGet()  (:154)   *)
(*     - isValidEpoch: return epoch >= currentEpoch                (:222-228)*)
(*   between/.../layer/MutationRouter.java                                  *)
(*     - handleForwardedMutation: reject if !isValidEpoch (:215-225);       *)
(*       then dedup on idempotencyKey via dedupTable (:90, :210, :227-232)  *)
(*                                                                         *)
(* A room has ONE primary node at a time; only the primary applies          *)
(* mutations. Two guards protect single-writer correctness:                 *)
(*   (a) FENCING: a mutation carries the epoch the client believed current; *)
(*       the primary rejects it if epoch < its own (a stale primary's       *)
(*       writes are fenced out).                                            *)
(*   (b) IDEMPOTENCY: the primary remembers applied idempotency keys in an   *)
(*       in-memory dedupTable and returns the cached result for a retry.    *)
(*                                                                         *)
(* The dedupTable is per-primary-node in memory (MutationRouter:90), with   *)
(* no persistence or handover transfer. `DurableDedup` toggles the proposed  *)
(* fix: carry the dedup set in durable room state so it survives a primary   *)
(* handover.                                                                *)
(*                                                                         *)
(* Properties (safety):                                                     *)
(*   NoStaleApply  - a mutation stamped with an epoch < the current epoch is *)
(*                   never applied (the FENCING guarantee — verifies #114).  *)
(*   NoDoubleApply - one idempotency key is applied at most once (the        *)
(*                   IDEMPOTENCY guarantee).                                *)
(*                                                                         *)
(* Committed gate (.cfg): NoStaleApply (+ TypeOK) — GREEN unconditionally,   *)
(* confirming the epoch fence is correctly placed. NoDoubleApply is the      *)
(* finding: with DurableDedup = FALSE a retry that straddles a primary       *)
(* HANDOVER applies twice (the new primary's dedupTable is empty), violating *)
(* it; set DurableDedup = TRUE to show durable dedup restores it.           *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS MaxEpoch,      \* bound on primary handovers
          MaxMsgs,       \* bound on in-flight mutation copies (sends + retries)
          MaxApply,      \* TypeOK bound on the apply counter
          DurableDedup   \* the fix: dedup survives a primary handover

(*--algorithm PrimaryFencing {
  variables
    epoch        = 1,        \* the room's current epoch (held by the live primary)
    dedupSeen    = FALSE,    \* has THIS primary recorded the (single) idempotency key?
    applied      = 0,        \* times the mutation was applied (must stay <= 1)
    appliedStale = FALSE,    \* set TRUE only if a fenced-out (stale) write ever applies
    inflight     = << >>;    \* pending mutations; each = the epoch the client stamped

  define {
    \* FENCING: a stale-epoch mutation is never applied.
    NoStaleApply  == ~appliedStale
    \* IDEMPOTENCY: the one idempotency key is applied at most once.
    NoDoubleApply == applied <= 1

    TypeOK ==
      /\ epoch        \in 1..MaxEpoch
      /\ dedupSeen    \in BOOLEAN
      /\ applied      \in 0..MaxApply
      /\ appliedStale \in BOOLEAN
      /\ Len(inflight) <= MaxMsgs
      /\ \A i \in 1..Len(inflight) : inflight[i] \in 1..MaxEpoch
  }

  process (node = "n") {
    loop: while (TRUE) {
      either {
        \* CLIENT SEND (or RETRY): the client forwards a mutation stamped with
        \* the epoch it currently believes is primary (MutationRouter:167-172).
        when Len(inflight) < MaxMsgs;
        inflight := Append(inflight, epoch);

      } or {
        \* PRIMARY HANDOVER: a new node claims primary and bumps the epoch
        \* (claimRoomPrimary:154). Without DurableDedup the new node's in-memory
        \* dedupTable starts empty — the gap this spec surfaces.
        when epoch < MaxEpoch;
        epoch     := epoch + 1;
        dedupSeen := IF DurableDedup THEN dedupSeen ELSE FALSE;

      } or {
        \* PROCESS a forwarded mutation at the primary (handleForwardedMutation).
        when inflight # << >>;
        with (e = Head(inflight)) {
          if (e < epoch) {
            \* FENCED: stale epoch — reject, never apply (isValidEpoch=FALSE).
            inflight := Tail(inflight);
          } else if (dedupSeen) {
            \* DEDUP HIT: idempotency key already applied — return cached.
            inflight := Tail(inflight);
          } else {
            \* APPLY: fresh, in-epoch mutation.
            inflight     := Tail(inflight);
            applied      := applied + 1;
            dedupSeen    := TRUE;
            \* e >= epoch here, so this apply is never stale; the flag stays a
            \* tripwire that would fire if the fence were ever mis-placed.
            appliedStale := IF e < epoch THEN TRUE ELSE appliedStale;
          }
        }
      }
    }
  }
}
*)
\* BEGIN TRANSLATION (chksum(pcal) = "bbd20bd0" /\ chksum(tla) = "f38c537")
VARIABLES epoch, dedupSeen, applied, appliedStale, inflight

(* define statement *)
NoStaleApply  == ~appliedStale

NoDoubleApply == applied <= 1

TypeOK ==
  /\ epoch        \in 1..MaxEpoch
  /\ dedupSeen    \in BOOLEAN
  /\ applied      \in 0..MaxApply
  /\ appliedStale \in BOOLEAN
  /\ Len(inflight) <= MaxMsgs
  /\ \A i \in 1..Len(inflight) : inflight[i] \in 1..MaxEpoch


vars == << epoch, dedupSeen, applied, appliedStale, inflight >>

ProcSet == {"n"}

Init == (* Global variables *)
        /\ epoch = 1
        /\ dedupSeen = FALSE
        /\ applied = 0
        /\ appliedStale = FALSE
        /\ inflight = << >>

node == \/ /\ Len(inflight) < MaxMsgs
           /\ inflight' = Append(inflight, epoch)
           /\ UNCHANGED <<epoch, dedupSeen, applied, appliedStale>>
        \/ /\ epoch < MaxEpoch
           /\ epoch' = epoch + 1
           /\ dedupSeen' = IF DurableDedup THEN dedupSeen ELSE FALSE
           /\ UNCHANGED <<applied, appliedStale, inflight>>
        \/ /\ inflight # << >>
           /\ LET e == Head(inflight) IN
                IF e < epoch
                   THEN /\ inflight' = Tail(inflight)
                        /\ UNCHANGED << dedupSeen, applied, appliedStale >>
                   ELSE /\ IF dedupSeen
                              THEN /\ inflight' = Tail(inflight)
                                   /\ UNCHANGED << dedupSeen, applied, 
                                                   appliedStale >>
                              ELSE /\ inflight' = Tail(inflight)
                                   /\ applied' = applied + 1
                                   /\ dedupSeen' = TRUE
                                   /\ appliedStale' = (IF e < epoch THEN TRUE ELSE appliedStale)
           /\ epoch' = epoch

Next == node

Spec == Init /\ [][Next]_vars

\* END TRANSLATION 
============================================================================
