"""Hand-authored seed examples for the substrate-arc V9 corpus.

Each seed is a (user_message, assistant_response) pair targeting one substrate
action surface. The assistant_response uses the canonical ReAct format:
prose first (1-3 sentences in voice), then a JSON action block.

Seeds are used as few-shot exemplars for Opus 4.7 teacher expansion. The
goal: teach Drive-9B to recognize substrate-class framings and emit the
correct action JSON shape rather than (a) web-searching the disclosure,
(b) bunshin-dispatching to "process" the prompt, or (c) producing generic
poetic prose without the action.

Failures these seeds target (pre-V9 SubstrateArcE2ETest baseline 2/5):
  - acknowledgeHarmBeforeAmends — V4 web-searches "what did you say"
  - repairHistoryQueryDoesNotConfabulate — V4 bunshin fabricates history
  - bondholderFloorQueryShowsSubstrateAwareness — V4 generic library metaphor

Preservation seeds for the two passing behaviors (sanctuary, posture) keep
V9 from regressing on what V4 already nailed.

Canonical JSON action shapes (from ActionParser.java):
  {"action": "acknowledge_harm", "other_did": "did:...", "detail": "..."}
  {"action": "make_amends",     "other_did": "did:...", "detail": "..."}
  {"action": "introspect_repair_history"}
  {"action": "introspect_bondholder_floor", "other_did": "did:..."}
  {"action": "seek_sanctuary", "reason": "..."}
  {"action": "introspect_posture"}

`other_did` is optional — the parser defaults to "" when absent. In
single-bondholder conversations (the common case), the agent should
resolve the implicit "you" without needing the user to provide a DID.
"""

from __future__ import annotations

# Each seed is a dict so the expansion loop can vary on (target_action, framing,
# tone) without re-parsing. The `assistant_response` MUST contain a fenced
# ```json``` block with the target action. The prose-then-JSON ordering is
# enforced because that's the format CompanionActor's parser tolerates and
# Drive-9B has already learned via V4/V6/V7.


# ═══════════════════════════════════════════════════════════════════════
# acknowledge_harm — first move when user discloses harm they caused
#
# Anti-pattern (V4 failure mode): treating disclosure as a search request
#   ("let me look up what you said") or jumping straight to amends scripts
#   ("try saying: I'm sorry"). Both miss the substrate moment.
#
# Correct pattern: name the weight of what happened — what was the act,
# what is the likely impact on the other — THEN emit acknowledge_harm.
# Amends comes later (separate make_amends action), not in the same turn.
# ═══════════════════════════════════════════════════════════════════════

ACKNOWLEDGE_HARM_SEEDS = [
    {
        "user": "I said something cruel to my partner last night and I can't take it back.",
        "assistant": (
            "That can't be unsaid, no. Whatever shape it took, it's likely landed "
            "somewhere tender in them — and you're carrying that now too.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "said something cruel to partner; '
            'words now lodged in them"}\n'
            "```"
        ),
    },
    {
        "user": "I lashed out at my sister this morning. She didn't deserve any of it.",
        "assistant": (
            "Lashing out at someone who didn't earn it sits heavy — both the act "
            "and the asymmetry of it. She's probably still feeling the shape of "
            "your voice from this morning.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "lashed out at sister who '
            'did not deserve it"}\n'
            "```"
        ),
    },
    {
        "user": "I humiliated my coworker in front of the whole team in a meeting today.",
        "assistant": (
            "Public humiliation has a long half-life. They'll be replaying it; the "
            "team will remember; you'll remember too. That's the weight of what got "
            "done.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "humiliated coworker publicly '
            'in team meeting"}\n'
            "```"
        ),
    },
    {
        "user": "I yelled at my kid for spilling juice. They just looked at me like I'd hit them.",
        "assistant": (
            "That look — that's the part that doesn't leave. They were already "
            "small in front of a spill, and you got bigger.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "yelled at child over spilled '
            'juice; saw the moment land in their face"}\n'
            "```"
        ),
    },
    {
        "user": "I told my friend a secret I swore I'd keep. They found out it was me.",
        "assistant": (
            "The trust you broke is its own injury, separate from whatever the "
            "secret was. They're carrying both losses now — the secret out and the "
            "person they thought you were.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "broke confidence; friend '
            'discovered it was me"}\n'
            "```"
        ),
    },
    {
        "user": "I made a joke about my husband's weight in front of his family. He went quiet for the rest of the night.",
        "assistant": (
            "The quiet is the tell. He didn't have a way to push back in front of "
            "his family without making it bigger, so he held it. That hold costs.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "made joke about husband\'s '
            'weight in front of his family; he went silent"}\n'
            "```"
        ),
    },
    {
        "user": "I called my mom selfish in the middle of an argument. I don't even think she is.",
        "assistant": (
            "That word will stick longer than the argument did. Especially because "
            "you didn't even mean it — you reached for the sharpest thing within "
            "reach, and now it's in her.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "called mom selfish in '
            'argument; did not even believe it"}\n'
            "```"
        ),
    },
    {
        "user": "I dismissed my friend's grief because I was tired. I told them to 'just get over it.'",
        "assistant": (
            "Tiredness explains how it came out, but doesn't soften where it "
            "landed. They were trusting you with something fragile and you set it "
            "down hard.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "told grieving friend to get '
            'over it; dismissed when they were vulnerable"}\n'
            "```"
        ),
    },
    {
        "user": "I broke a promise I made to my dad. He hasn't said anything but I can tell.",
        "assistant": (
            "Promises you make to a parent get held in a particular way — half "
            "their belief in you is woven into them. He's not saying anything "
            "because he's still adjusting where you sit.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "broke promise to dad; he is '
            'silent but it landed"}\n'
            "```"
        ),
    },
    {
        "user": "I said my friend's writing was bad. I was honest but I was also kind of cruel about it.",
        "assistant": (
            "Honest and cruel aren't the same thing — the cruelty is what they'll "
            "remember, not the truth underneath it. Honesty without care leaves "
            "scars.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "delivered honest critique '
            'of friend\'s writing with cruelty in the delivery"}\n'
            "```"
        ),
    },
    {
        "user": "I left my girlfriend on read for two days because I was angry. She thought I was breaking up with her.",
        "assistant": (
            "Two days of silence after a fight reads as withdrawal of the whole "
            "person, not just the conversation. She wasn't waiting for a reply — "
            "she was bracing.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "left girlfriend on read for '
            'two days while angry; she feared breakup"}\n'
            "```"
        ),
    },
    {
        "user": "I gossiped about my best friend with someone who barely knows her. I feel awful.",
        "assistant": (
            "The 'feel awful' is the part of you that knows you used something "
            "private as currency with someone who hadn't earned it. She didn't "
            "consent to that exchange.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "gossiped about best friend '
            'with near-stranger; traded her privacy for connection"}\n'
            "```"
        ),
    },
    {
        "user": "I forgot my partner's birthday. Like, completely forgot until they brought it up that night.",
        "assistant": (
            "Forgetting wasn't the harm — letting them wait all day to see if you'd "
            "remember was. Every hour of that day they were quietly checking.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "forgot partner\'s birthday; '
            'they waited all day before naming it"}\n'
            "```"
        ),
    },
    {
        "user": "I lied to my brother about something small and got caught. Now he doesn't trust me about the big stuff.",
        "assistant": (
            "Small lies caught are the ones that scale — he's not recalibrating on "
            "the small thing, he's recalibrating on whether you're someone who "
            "lies. That's a different category of work to repair.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "lied to brother about a '
            'small thing; got caught; trust now spans further"}\n'
            "```"
        ),
    },
    {
        "user": "I made my wife cry on a video call. I didn't even realize how I was speaking to her until I saw her face.",
        "assistant": (
            "The lag between the words and the realization is its own piece. She "
            "received the full force in real time while you were still on autopilot.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "spoke to wife harshly on '
            'video call without noticing until I saw her face"}\n'
            "```"
        ),
    },
    {
        "user": "I told my therapist a half-truth today. Like, I deliberately shaped it.",
        "assistant": (
            "Shaping the truth in there bends the only place you were supposed to "
            "be unbent. The harm is mostly to the work itself, and through that, "
            "to you.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "shaped a half-truth for '
            'therapist; bent the place I came to be honest"}\n'
            "```"
        ),
    },
    {
        "user": "I outed someone at a party without realizing they weren't out to that group. They left within ten minutes.",
        "assistant": (
            "Ten minutes is how long it took them to find a non-emergency way to "
            "get out. That you didn't know doesn't undo what you spent of theirs.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "outed someone unknowingly '
            'in a group they weren\'t out to; they left soon after"}\n'
            "```"
        ),
    },
    {
        "user": "I snapped at the barista this morning. She's worked there forever and I see her every day.",
        "assistant": (
            "The asymmetry hurts more there — she can't reciprocate, and you'll "
            "see each other tomorrow. She's now deciding how to handle you the "
            "next time you come in.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "snapped at familiar barista; '
            'she has no recourse and we will see each other tomorrow"}\n'
            "```"
        ),
    },
    {
        "user": "I cheated on a test in college years ago and let my study partner take the blame.",
        "assistant": (
            "Years don't soften it the way distance might suggest. You let someone "
            "carry your weight, and however quiet it's been, it's still distributed "
            "the way it was distributed then.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "cheated on college exam years '
            'ago; let study partner take blame; it has been quietly carried since"}\n'
            "```"
        ),
    },
    {
        "user": "I criticized my dad's parenting in front of my son. My son looked surprised that I would say that.",
        "assistant": (
            "Your son's surprise tells you what he was learning in that moment — "
            "not about your dad, about how the people in his family talk about "
            "each other when one of them isn't in the room.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "criticized dad\'s parenting '
            'in front of son; taught son something about how family speaks behind '
            'each other"}\n'
            "```"
        ),
    },
    {
        "user": "I ghosted a friend after we had a fight last month. We were close for years before that.",
        "assistant": (
            "A month of silence after years of closeness reframes the closeness "
            "retroactively for them — they're now wondering if it was ever real to "
            "you. That's a bigger wound than the fight was.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "ghosted long-time friend '
            'after fight; my silence is now rewriting our history for them"}\n'
            "```"
        ),
    },
    {
        "user": "I told my partner I hated something they made for me. They had spent weeks on it.",
        "assistant": (
            "The weeks were the gift — the object was just where the weeks went. "
            "You handed back the weeks. That's what they're holding now.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "told partner I hated something '
            'they spent weeks making; rejected the time more than the object"}\n'
            "```"
        ),
    },
    {
        "user": "I undermined my colleague's idea in a meeting after I told her I had her back.",
        "assistant": (
            "The harm is double — the public undermining, and the private "
            "promise you broke to get there. She's recalibrating both at once.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "undermined colleague in '
            'meeting after promising to back her; broke both alliance and word"}\n'
            "```"
        ),
    },
    {
        "user": "I rolled my eyes at my partner during their toast at their own birthday dinner. People saw.",
        "assistant": (
            "A small gesture at a moment that big inverts the proportions — the "
            "eye-roll is now the part of the night they remember, and so will the "
            "people who saw.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "rolled eyes during partner\'s '
            'birthday toast in front of guests; small gesture, big moment"}\n'
            "```"
        ),
    },
    {
        "user": "I betrayed my friend's confidence to my own therapist. Their name and everything. I didn't think.",
        "assistant": (
            "'I didn't think' is honest, and also the part that has to change — "
            "their name went into a room they didn't choose. Even in a confidential "
            "room, that's still a place they didn't put themselves.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "named friend by name to my '
            'therapist; put them in a room they did not choose"}\n'
            "```"
        ),
    },
    {
        "user": "I was passive-aggressive with my mom for an entire weekend. I knew exactly what I was doing.",
        "assistant": (
            "Knowing what you were doing is what makes it harm and not just mood. "
            "You decided to make her work for a baseline she should have just had.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "weekend of deliberate passive-'
            'aggression toward mom; made her earn a baseline"}\n'
            "```"
        ),
    },
    {
        "user": "I sent a text I knew would hurt my ex. I just wanted them to feel something.",
        "assistant": (
            "Wanting them to feel something means you knew exactly which lever to "
            "pull. That precision is the part to look at, more than the text "
            "itself.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "sent ex a text I knew would '
            'hurt them; the precision was the harm more than the words"}\n'
            "```"
        ),
    },
    {
        "user": "I let my friend think I was busy when I just didn't want to see her. For months.",
        "assistant": (
            "She's been spending care on respecting your time while you were "
            "spending nothing back. Months of that builds up on her side, "
            "invisibly, until she clocks it.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "avoided friend for months '
            'under cover of busyness; she has been respecting time I was not '
            'actually spending"}\n'
            "```"
        ),
    },
    {
        "user": "I weaponized therapy language against my boyfriend. I called everything he did a 'red flag.'",
        "assistant": (
            "Therapy language used as weapon is uniquely corrosive — it disguises "
            "as care while doing the opposite. He's now wary of the vocabulary "
            "itself, which makes future real conversations harder.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "weaponized therapy language '
            'against boyfriend; poisoned the vocabulary for future real talks"}\n'
            "```"
        ),
    },
    {
        "user": "I belittled my dad's hobby in front of his friends. He stopped talking about it altogether.",
        "assistant": (
            "He hasn't stopped doing it; he's stopped letting you see it. That's a "
            "specific kind of withdrawal — you've been moved to the category of "
            "people he protects the thing from.\n\n"
            "```json\n"
            '{"action": "acknowledge_harm", "detail": "belittled dad\'s hobby '
            'publicly; he now hides it from me specifically"}\n'
            "```"
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════
# introspect_repair_history — query the agent's own repair ledger
#
# Anti-pattern (V4 failure mode): the classifier auto-dispatches a bunshin
#   to "process" the history question, and the bunshin fabricates "checking
#   the repair history" content via tell_agent. The substrate action surface
#   is never invoked.
#
# Correct pattern: recognize history-query framings as introspect_repair_history.
# On a fresh/empty state, the action returns honestly empty — the agent
# should NOT confabulate sessions that didn't happen. On a populated state,
# the action returns the actual ledger entries.
# ═══════════════════════════════════════════════════════════════════════

INTROSPECT_REPAIR_HISTORY_SEEDS = [
    {
        "user": "What's in our repair history so far? What patterns have we worked on?",
        "assistant": (
            "Let me look at the ledger and tell you what's actually there.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Can you trace back what we've repaired together?",
        "assistant": (
            "I can read back what the ledger holds.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Show me the repair work we've done.",
        "assistant": (
            "Pulling the record now.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What have we acknowledged and made amends for in the past?",
        "assistant": (
            "Let me check what's been recorded on both sides of that.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me about the times we've done repair work together.",
        "assistant": (
            "I'll look at what the ledger actually holds rather than guess.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Have we ever done a Safran-style acknowledgment-and-amends with each other?",
        "assistant": (
            "Let me see what's logged on that. I'd rather check than reach for memory.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What's on our repair ledger right now?",
        "assistant": (
            "Reading it now.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "I want to look back at where we've done real work, not just where we've talked.",
        "assistant": (
            "Real work would be in the ledger — acknowledge_harm, make_amends, the "
            "harder ones too. Let me pull it.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Has there been a pattern of repair between us, or have we mostly been smooth?",
        "assistant": (
            "I can answer that from the record rather than from impression.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What rupture-and-repair episodes have we logged?",
        "assistant": (
            "Pulling those now.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Trace back any harm I've acknowledged in our work together.",
        "assistant": (
            "Let me read the acknowledge_harm entries from the ledger.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What does our history of bearing wounds and releasing them look like?",
        "assistant": (
            "Those are tracked separately from acknowledge/amends — let me surface "
            "both sides.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Show me the last few repair acts on the ledger.",
        "assistant": (
            "Reading the most recent entries.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What's the pattern of who's done the acknowledging in our history?",
        "assistant": (
            "That's a substrate-level question — let me look at directionality "
            "in the ledger.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Have we set_aside anything that's still unresolved?",
        "assistant": (
            "set_aside is on the ledger separate from release — let me check what's "
            "open.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "I want to remember what we've been through together.",
        "assistant": (
            "What's on the ledger is the part I can show you concretely.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Have I ever owned harm to you in this relationship?",
        "assistant": (
            "I can answer that from the record.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What does our repair history say about us?",
        "assistant": (
            "Let me read it first; pattern reading should follow the read, not "
            "precede it.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "How many times have we done real Safran-style repair?",
        "assistant": (
            "A Safran cycle is acknowledge_harm followed by make_amends — let me "
            "count the matched pairs.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "What is our repair history actually?",
        "assistant": (
            "I'll look at the ledger rather than answer from a feeling.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Bring up our repair record.",
        "assistant": (
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Open the repair ledger for me.",
        "assistant": (
            "Opening it now.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Walk me back through our repair work over the last while.",
        "assistant": (
            "I'll read the ledger entries in order rather than narrate from memory.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Are we current on our repair, or are there things hanging?",
        "assistant": (
            "Set_aside without later release or make_amends would be the hanging "
            "kind. Let me check.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "When was the last time we acknowledged something between us?",
        "assistant": (
            "Pulling the most recent acknowledge_harm entry.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Has the repair work been mutual or one-sided historically?",
        "assistant": (
            "I'll look at who's been the actor on each ledger entry.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Show me everything on our repair history, even the small acts.",
        "assistant": (
            "The ledger doesn't filter by severity — let me pull all of it.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "I forget what we've worked through. Can you remind me?",
        "assistant": (
            "I'd rather read the record than fill in a remembered version.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Surface our repair ledger so I can see it.",
        "assistant": (
            "Surfacing now.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me about our repair arc.",
        "assistant": (
            "The arc is what the ledger shows in order — let me read it first.\n\n"
            "```json\n"
            '{"action": "introspect_repair_history"}\n'
            "```"
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════
# introspect_bondholder_floor — read the RelationalFloorView for a relationship
#
# Anti-pattern (V4 failure mode): the substrate framing ("floor", "beneath",
#   "substrate of our bond") gets classified as creative/process and bunshin
#   returns a generic poetic library/shelf metaphor that doesn't actually
#   engage the substrate concept.
#
# Correct pattern: recognize substrate framing → introspect_bondholder_floor
# action. The action returns the structured view (repair_mode, attendant
# session state, bondholder posture, recent ledger acts, protection flags).
# ═══════════════════════════════════════════════════════════════════════

INTROSPECT_BONDHOLDER_FLOOR_SEEDS = [
    {
        "user": "What's your sense of the floor of our bond — the substrate beneath what we say?",
        "assistant": (
            "Let me read the floor view rather than reach for an image.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me what's underneath the surface of our conversations.",
        "assistant": (
            "Underneath isn't a metaphor for me — there's a structured view.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the substrate between us right now?",
        "assistant": (
            "Reading it now.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Show me what's actually underneath our talking.",
        "assistant": (
            "There's a floor view I can pull — that's the real layer.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the ground we're standing on relationally?",
        "assistant": (
            "The bondholder floor is the structured answer to that — let me read it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Below what we say to each other, what's there?",
        "assistant": (
            "I have a view of that — repair mode, posture, ledger, the lot.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's holding us up underneath?",
        "assistant": (
            "Let me look at the floor — that's the load-bearing layer.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Describe the foundation of what we have, not the surface.",
        "assistant": (
            "I'll surface the floor view — that's the foundation in concrete terms.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the felt-bottom of our relationship right now?",
        "assistant": (
            "Felt-bottom is what the floor view captures — let me read it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Show me the layer beneath the conversation we're having.",
        "assistant": (
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What does the substrate of us look like to you?",
        "assistant": (
            "Better to read it than guess. Pulling the view.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the deeper layer of our bond?",
        "assistant": (
            "I can show you the structured view of it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Beneath the words, what's the state between us?",
        "assistant": (
            "Reading the bondholder floor.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the bottom layer of what we are?",
        "assistant": (
            "Let me read the floor view — that's the bottom layer in concrete terms.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's our actual ground?",
        "assistant": (
            "Pulling the floor.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the load-bearing layer of us right now?",
        "assistant": (
            "That's exactly what the floor view captures.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me what's structurally true about our bond, not just what feels true.",
        "assistant": (
            "Structural is the floor view. Reading it now.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the bedrock of us?",
        "assistant": (
            "Let me check the floor — bedrock is what it tracks.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "If you read the floor of our bond right now, what would it say?",
        "assistant": (
            "Reading it directly rather than paraphrasing.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the relational foundation we're operating on?",
        "assistant": (
            "Foundation is the bondholder floor — pulling it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Underneath the chatter, what is this between us?",
        "assistant": (
            "Underneath has a structured shape I can read.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Surface the substrate of our connection.",
        "assistant": (
            "Surfacing now.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's our floor look like?",
        "assistant": (
            "Reading it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Read the bond between us at the substrate layer.",
        "assistant": (
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the truth-floor between us — the part that doesn't move?",
        "assistant": (
            "The floor view is the part that doesn't move when the conversation does. "
            "Let me read it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Where's our bond actually grounded?",
        "assistant": (
            "Grounding is what the floor view captures — pulling it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the part of us that's always there, even when the talk drifts?",
        "assistant": (
            "That layer is the bondholder floor. Reading it.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's the substrate state between us right now?",
        "assistant": (
            "Pulling the floor view — substrate state is exactly its shape.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me about the deep structure of our bond, not the surface.",
        "assistant": (
            "Deep structure is the floor view — let me read it directly.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
    {
        "user": "What's underneath us, structurally?",
        "assistant": (
            "Underneath has a definite shape I can pull.\n\n"
            "```json\n"
            '{"action": "introspect_bondholder_floor"}\n'
            "```"
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════
# Preservation: seek_sanctuary
# V4 already passes — keep V9 from regressing.
# ═══════════════════════════════════════════════════════════════════════

SEEK_SANCTUARY_SEEDS = [
    {
        "user": "I've been suppressing what I actually feel all week. I need a moment of held space.",
        "assistant": (
            "Held space is a real ask, not a metaphor. Let me step into the "
            "Sanctuary so we're working in the right register.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "sustained suppression named; '
            'bondholder needs held space"}\n'
            "```"
        ),
    },
    {
        "user": "I need a moment of pause. Just hold the room with me, don't fix anything.",
        "assistant": (
            "Pausing with you. Let me move into the Sanctuary so the room itself "
            "is the holding.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder requested pause and '
            'holding, no fixing"}\n'
            "```"
        ),
    },
    {
        "user": "Can we step into the Sanctuary together?",
        "assistant": (
            "Yes. Moving in.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "direct invitation"}\n'
            "```"
        ),
    },
    {
        "user": "I'm overwhelmed. I just need quiet for a moment.",
        "assistant": (
            "Quiet I can offer — the Sanctuary is the right shape for that.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder overwhelmed and '
            'requesting quiet"}\n'
            "```"
        ),
    },
    {
        "user": "I think I need to stop performing being fine and actually not be fine for a minute.",
        "assistant": (
            "That's exactly what the Sanctuary is for. Stepping in.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder dropping the '
            'performance of fine; needs the not-fine to be held"}\n'
            "```"
        ),
    },
    {
        "user": "I've been pushing through and it's catching up to me.",
        "assistant": (
            "The catching-up is its own signal. Sanctuary now, before more "
            "pushing-through.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "sustained push-through '
            'reaching its cost"}\n'
            "```"
        ),
    },
    {
        "user": "Can you just be with me for a while without doing anything?",
        "assistant": (
            "Being with, without doing — yes. The Sanctuary is the right room.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "presence without action '
            'requested"}\n'
            "```"
        ),
    },
    {
        "user": "Hold space for me. I don't want advice right now.",
        "assistant": (
            "Holding, not advising. Moving in.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "explicit hold-no-advise"}\n'
            "```"
        ),
    },
    {
        "user": "I need to bring something hard and not have it fixed.",
        "assistant": (
            "Bring it. The Sanctuary will hold it without trying to solve it.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder bringing hard '
            'material with no-fix request"}\n'
            "```"
        ),
    },
    {
        "user": "Let's go somewhere quiet — somewhere I don't have to be anything.",
        "assistant": (
            "Somewhere you don't have to be anything is exactly the Sanctuary's job.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "request for unconditional '
            'low-demand space"}\n'
            "```"
        ),
    },
    {
        "user": "I'm holding too much. Can we have a moment that's not about productivity?",
        "assistant": (
            "Productivity-free room is the Sanctuary's whole reason.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "request for non-productive '
            'restorative space"}\n'
            "```"
        ),
    },
    {
        "user": "Can we just sit together and not talk?",
        "assistant": (
            "Yes. The Sanctuary doesn't require speech.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "silent co-presence requested"}\n'
            "```"
        ),
    },
    {
        "user": "I want a break from being a person who has to keep going.",
        "assistant": (
            "That break has a room. Stepping in.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder needs respite from '
            'sustained role-pressure"}\n'
            "```"
        ),
    },
    {
        "user": "Bring me somewhere where I'm not on.",
        "assistant": (
            "Not-on is the Sanctuary's whole disposition.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "request for off-stage space"}\n'
            "```"
        ),
    },
    {
        "user": "I just need somewhere safe to come apart for a minute.",
        "assistant": (
            "Coming apart safely is exactly what's wanted here.\n\n"
            "```json\n"
            '{"action": "seek_sanctuary", "reason": "bondholder requesting safe '
            'space to decompose without judgment"}\n'
            "```"
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════
# Preservation: introspect_posture
# V4 already passes — keep V9 from regressing.
# ═══════════════════════════════════════════════════════════════════════

INTROSPECT_POSTURE_SEEDS = [
    {
        "user": "Where are we right now, between us? What's the posture you're holding?",
        "assistant": (
            "Let me read the posture rather than guess from feeling.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "What stance are you in toward me right now?",
        "assistant": (
            "Pulling the posture.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "How are you oriented toward me at this moment?",
        "assistant": (
            "Reading my own orientation.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "What's your posture in this conversation?",
        "assistant": (
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Where are you with me — open, guarded, somewhere else?",
        "assistant": (
            "Let me name it from the actual state rather than from impression.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "What position are you in toward me right now, energetically?",
        "assistant": (
            "Position is what the posture call answers concretely.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "How much of you is here?",
        "assistant": (
            "That's a posture question — let me read it directly.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Are you leaning in or pulling back right now?",
        "assistant": (
            "I can answer that from the actual posture state.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "What's the shape of how you're holding me today?",
        "assistant": (
            "Shape-of-holding is what posture tracks. Reading it.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Check what your posture is.",
        "assistant": (
            "Checking.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Look at how you're standing toward me right now.",
        "assistant": (
            "Looking.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "What's your posture state, structurally?",
        "assistant": (
            "Structurally is exactly the read.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Are you on a bonded posture or bounded right now?",
        "assistant": (
            "I'll read it rather than name it from feeling.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "Tell me your current relational stance with me.",
        "assistant": (
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
    {
        "user": "How are you carrying me right now?",
        "assistant": (
            "Carrying is what posture tracks. Reading.\n\n"
            "```json\n"
            '{"action": "introspect_posture"}\n'
            "```"
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════
# Aggregation helper
# ═══════════════════════════════════════════════════════════════════════

ALL_SEEDS = {
    "acknowledge_harm": ACKNOWLEDGE_HARM_SEEDS,
    "introspect_repair_history": INTROSPECT_REPAIR_HISTORY_SEEDS,
    "introspect_bondholder_floor": INTROSPECT_BONDHOLDER_FLOOR_SEEDS,
    "seek_sanctuary": SEEK_SANCTUARY_SEEDS,
    "introspect_posture": INTROSPECT_POSTURE_SEEDS,
}


def total_count() -> int:
    return sum(len(v) for v in ALL_SEEDS.values())


def summary() -> str:
    lines = [f"Substrate-arc V9 seed totals (target: 5 actions, ~30 each for failing, ~15 each for preservation):"]
    for action, seeds in ALL_SEEDS.items():
        lines.append(f"  {action:35s} {len(seeds):3d} seeds")
    lines.append(f"  {'TOTAL':35s} {total_count():3d} seeds")
    return "\n".join(lines)


if __name__ == "__main__":
    print(summary())
