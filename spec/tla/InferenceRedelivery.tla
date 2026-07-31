------------------------ MODULE InferenceRedelivery ------------------------
(***************************************************************************)
(* P2 (, Deliverable A) — cross-zone NATS inference *)
(* request/reply, modelling the #264 redelivery hazard class.              *)
(*                                                                         *)
(* Java this models:                                                       *)
(*   server/.../inference/NatsInferenceServer.java   (onRequest -> infer    *)
(*                                                    -> publish chunks)     *)
(*   between/.../inference/NatsInferenceClient.java  (subscribe by streamId, *)
(*                                                    publish request)       *)
(*   between/.../inference/NatsInferenceProtocol.java                        *)
(*                                                                         *)
(* The request is correlated to its reply ONLY by a streamId (a UUID); the  *)
(* protocol carries NO message id / dedup header (no Nats-Msg-Id) and the   *)
(* provider does NOT remember which requests it has already served. Today    *)
(* this rides core NATS (at-most-once), so the provider executes inference   *)
(* exactly once. The #264 incident was an amplification *loop* in the relay  *)
(* bridge (now fixed by excluding `federation.inference.*` from bridge       *)
(* forwarding); but the underlying protocol has no defence if the transport  *)
(* is ever at-least-once (JetStream) — a redelivered Request re-executes     *)
(* inference and re-publishes a second reply.                               *)
(*                                                                         *)
(* This spec asks: with no provider-side dedup, does redelivery cause the    *)
(* provider to run inference more than once for a single request?           *)
(*                                                                         *)
(* Channel reliability is modelled the TraceFix way, as a toggle:           *)
(*   AllowRedeliver  - JetStream-style at-least-once: a queued request may   *)
(*                     be delivered (re-queued) more than once.             *)
(* Fix toggle:                                                              *)
(*   ProviderDedup   - the provider remembers served streamIds and drops a   *)
(*                     redelivered request instead of re-running inference.  *)
(*                     (= a dedup table keyed on streamId / a Nats-Msg-Id.)  *)
(*                                                                         *)
(* Safety property (TraceFix checks safety only):                           *)
(*   AtMostOnceServed - the provider runs inference <= 1 time for the        *)
(*                      request (so the requestor never accumulates two      *)
(*                      overlapping replies = the "garbled output" failure). *)
(*                                                                         *)
(* Committed gate (.cfg): the RELIABLE transport (AllowRedeliver = FALSE) is  *)
(* single-serve correct regardless of ProviderDedup — green. Flip            *)
(* AllowRedeliver = TRUE with ProviderDedup = FALSE to reproduce the finding; *)
(* set ProviderDedup = TRUE to show the streamId-dedup fix restores it.      *)
(***************************************************************************)
EXTENDS Naturals, TLC

CONSTANTS MaxMsgs,        \* bound on in-flight copies of the request
          AllowRedeliver, \* at-least-once transport may duplicate a delivery
          ProviderDedup   \* the fix: provider drops an already-served streamId

(*--algorithm InferenceRedelivery {
  variables
    pending   = 1,        \* copies of the (single) request queued at the provider
    served    = FALSE,    \* has the provider already run inference for this streamId?
    execCount = 0;        \* number of times inference actually ran (the harm metric)

  define {
    \* The provider must run inference at most once for one logical request;
    \* a second run means a duplicate reply stream = garbled accumulation.
    AtMostOnceServed == execCount <= 1

    TypeOK ==
      /\ pending   \in 0..MaxMsgs
      /\ served    \in BOOLEAN
      /\ execCount \in 0..MaxMsgs
  }

  process (broker = "b") {
    loop: while (TRUE) {
      either {
        \* DELIVER: hand a queued request copy to the provider.
        \*   - With ProviderDedup, an already-served streamId is dropped (no re-run).
        \*   - Without it, every delivery runs inference and publishes a reply.
        when pending > 0;
        if (ProviderDedup /\ served) {
          pending := pending - 1;                 \* dedup: consume, do not re-run
        } else {
          pending   := pending - 1;
          served    := TRUE;
          execCount := execCount + 1;             \* infer + publish a reply stream
        };

      } or {
        \* REDELIVER (AllowRedeliver): at-least-once — re-queue a copy of the
        \* request (an un-acked JetStream message redelivered to the provider).
        when AllowRedeliver /\ pending < MaxMsgs;
        pending := pending + 1;
      }
    }
  }
}
*)
\* BEGIN TRANSLATION (chksum(pcal) = "ffff63c3" /\ chksum(tla) = "85782232")
VARIABLES pending, served, execCount

(* define statement *)
AtMostOnceServed == execCount <= 1

TypeOK ==
  /\ pending   \in 0..MaxMsgs
  /\ served    \in BOOLEAN
  /\ execCount \in 0..MaxMsgs


vars == << pending, served, execCount >>

ProcSet == {"b"}

Init == (* Global variables *)
        /\ pending = 1
        /\ served = FALSE
        /\ execCount = 0

broker == \/ /\ pending > 0
             /\ IF ProviderDedup /\ served
                   THEN /\ pending' = pending - 1
                        /\ UNCHANGED << served, execCount >>
                   ELSE /\ pending' = pending - 1
                        /\ served' = TRUE
                        /\ execCount' = execCount + 1
          \/ /\ AllowRedeliver /\ pending < MaxMsgs
             /\ pending' = pending + 1
             /\ UNCHANGED <<served, execCount>>

Next == broker

Spec == Init /\ [][Next]_vars

\* END TRANSLATION 
============================================================================
