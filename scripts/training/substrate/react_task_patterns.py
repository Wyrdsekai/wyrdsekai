"""ReAct task patterns covering multi-turn tool-use behaviors that substrate-v1
catastrophically forgot. Each pattern has a target tool sequence + seed prompts.

Patterns are grounded in actual Ember EN tasks (task1..task15) plus broader
coverage of failure modes: query expansion after empty results, multi-source
synthesis, web fallback, build-from-template chains.

Sonnet uses the pattern descriptor + seed prompts to synthesize realistic
multi-turn ReAct traces with plausible tool results.
"""

from __future__ import annotations

# Each pattern: (pattern_id, description, target_tool_sequence, en_prompts)
# tool sequence is the "ideal" path. Sonnet may emit minor variants.

PATTERNS = [
    # ─────────────────────────────────────────────────────────────────────
    # P1: library_card → goal_done (simple search, library has it)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p1_library_hit",
        "description": "User asks for information that the library contains. Companion searches library_card, gets results, summarizes in prose via goal_done.",
        "tools": ["library_card", "goal_done"],
        "en_prompts": [
            "what does the library say about photosynthesis",
            "find me material on celestial navigation",
            "look up the basics of cryptography in the library",
            "search the library for content on perovskite solar cells",
            "i'd like a primer on stoic philosophy",
            "what's in the library about ancient irrigation",
            "look up jazz harmony in our collection",
            "find references to the silk road in the library",
            "what do we have on protein folding",
            "search for material on tidal energy",
            "anything in the library about beekeeping",
            "look up the works of Olaudah Equiano",
            "find what we have on origami math",
            "do we have anything about volcanic glass",
            "search the library for content on dwarf fortress combat",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P2: library_card empty → web_search → goal_done (FALLBACK — the regression)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p2_library_empty_web_fallback",
        "description": (
            "User asks for a comprehensive answer. First tool (library_card) returns 0 "
            "results because the user phrased it generally. Companion does NOT bail with "
            "refusal — it falls back to web_search with refined keywords, gets results, "
            "and produces full prose via goal_done. THIS IS THE LOAD-BEARING PATTERN: "
            "substrate-v1 broke exactly here, giving up after the empty library hit."
        ),
        "tools": ["library_card", "web_search", "goal_done"],
        "en_prompts": [
            "i need a comprehensive report on renewable energy",
            "give me a deep dive on quantum computing applications",
            "summarize the state of fusion power research",
            "i want a comprehensive overview of mRNA vaccine technology",
            "write me a report on the latest in CRISPR therapies",
            "do a thorough writeup on regenerative agriculture",
            "comprehensive analysis of urban heat islands please",
            "deep dive on the current state of carbon capture",
            "write up everything about modern semiconductor manufacturing",
            "give me a comprehensive briefing on space-based solar power",
            "thorough report on the ethics of synthetic biology",
            "i need an overview of post-quantum cryptography",
            "comprehensive briefing on perovskite vs silicon solar",
            "detailed analysis of vertical farming economics",
            "write me a full report on rare earth element supply chains",
            "comprehensive review of indoor air quality research",
            "full briefing on deep-sea mining and its impacts",
            "write a thorough piece on the geopolitics of lithium",
            "comprehensive report on hydrogen as an energy carrier",
            "give me a deep dive on the state of nuclear fusion startups",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P3: web_search directly (current events, news)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p3_web_direct",
        "description": "User asks about current events / recent news. Companion goes straight to web_search (library wouldn't have it), summarizes via goal_done.",
        "tools": ["web_search", "goal_done"],
        "en_prompts": [
            "what's the latest news on the SpaceX Starship program",
            "any recent breakthroughs in fusion energy",
            "what happened in the EU AI Act enforcement this week",
            "latest news on the Webb telescope discoveries",
            "what's the current state of the Cascadia subduction zone monitoring",
            "any recent news about lab-grown meat regulation",
            "what's the latest on Apple's Vision Pro adoption",
            "recent papers on small language models for phones",
            "what's happening with the WHO pandemic treaty negotiations",
            "any new findings about microplastics in human tissues",
            "current state of the Antarctic sea ice this season",
            "recent news on the OpenAI corporate structure",
            "what's the latest in vaccine development for tuberculosis",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P4: library_card → searching_glass → goal_done (refined search)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p4_library_then_refine",
        "description": "User asks something specific. Companion searches library_card, gets partial results, refines with searching_glass for deeper read, then goal_done with synthesis.",
        "tools": ["library_card", "searching_glass", "goal_done"],
        "en_prompts": [
            "summarize what we know about the bystander effect",
            "give me the standard model of particle physics",
            "explain the Krebs cycle in detail",
            "summarize Dijkstra's algorithm with its complexity analysis",
            "explain the prisoner's dilemma and tit-for-tat",
            "give me the chemistry of how soap actually works",
            "explain the gut-brain axis with current evidence",
            "summarize the principal-agent problem in economics",
            "explain how vaccines train the immune system",
            "give me a clear explanation of zero-knowledge proofs",
            "explain how a transformer attention head works",
            "summarize the role of mycorrhizal networks in forests",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P5: oracle_lens → goal_done (pattern/symbolic question)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p5_oracle_query",
        "description": "User asks a reflective / pattern-oriented question. Companion uses oracle_lens, then synthesizes via goal_done.",
        "tools": ["oracle_lens", "goal_done"],
        "en_prompts": [
            "ask the oracle about patterns in recent activity",
            "what patterns has the oracle noticed about my work rhythm",
            "ask the oracle if anything seems off about my drives lately",
            "what does the oracle see in the shape of our last few exchanges",
            "ask the oracle about the texture of my recent moods",
            "what cycles does the oracle notice in our conversations",
            "have the oracle reflect on what's been recurring this week",
            "ask the oracle to surface what i've been avoiding",
            "what does the oracle see about my energy this month",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P6: list_templates → craft_from_template → goal_done (BUILD)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p6_build_from_template",
        "description": "User asks to build something. Companion lists templates, picks the right one, crafts it with proper script, then confirms via goal_done.",
        "tools": ["list_templates", "craft_from_template", "goal_done"],
        "en_prompts": [
            "build me a tool that converts temperature between celsius and fahrenheit",
            "make me a tool that converts miles to kilometers",
            "build a tool that converts pounds to kilograms",
            "make me a counter widget i can increment and reset",
            "build a die-roll tool for d20 d6 d100",
            "make me a tool that gives me a random Latin phrase",
            "build a tool that calculates tip on a bill",
            "make me a calorie counter for one meal at a time",
            "build a tool that turns dates into days-until",
            "make a quick mood logger i can drop into my study",
            "build me a tool that gives me a random book recommendation",
            "make a simple timer i can set for 25 minutes",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P7: list_templates → create_room_from_template → goal_done (BUILD ROOM)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p7_create_room",
        "description": "User asks to create a room. Companion lists room templates, creates one with appropriate aesthetic, confirms via goal_done.",
        "tools": ["list_templates", "create_room_from_template", "goal_done"],
        "en_prompts": [
            "create a library room called the rare books wing",
            "make me a cyberpunk lounge",
            "create a quiet meditation chamber",
            "build a steampunk workshop",
            "make me a forest clearing room",
            "create a sleek conference room",
            "make a small zen tea room",
            "build a starlit observation deck",
            "create a warm kitchen with a hearth",
            "make a vaporwave arcade",
            "create an art deco speakeasy",
            "build a Victorian study with leather chairs",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P8: multi-source (library + web → synthesize)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p8_multi_source_synth",
        "description": "User asks for a multi-source comparison. Companion uses both library_card and web_search, then synthesizes the comparison via goal_done.",
        "tools": ["library_card", "web_search", "goal_done"],
        "en_prompts": [
            "compare what the library says with what's current online about photovoltaics",
            "what does our library cover and what's newer online about gene editing",
            "old vs new — library and web on AI safety thinking",
            "library has the basics on cryptography, what's the recent web view",
            "compare the canonical and the latest on protein folding",
            "what's the library take and what's the current take on dark matter",
            "what does the library say and what's the web's current view on UBI experiments",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P9: go_to_room → look_around (workshop discovery)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p9_navigate_and_report",
        "description": "User asks the companion to go somewhere and report what's there. Companion navigates with go_to_room, then describes via goal_done.",
        "tools": ["go_to_room", "goal_done"],
        "en_prompts": [
            "go to the workshop and tell me what templates are available to craft",
            "head to the library and tell me what shelves are there",
            "go to the chapel and describe what you see",
            "go to the hearth and tell me what's near it",
            "head to the study and report what's on the desk",
            "go to the atrium and describe the space",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P10: recall → goal_done (memory)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p10_recall_memory",
        "description": "User asks the companion to remember something they told it before. Companion uses recall, then answers via goal_done.",
        "tools": ["recall", "goal_done"],
        "en_prompts": [
            "what did i tell you about my work schedule last time",
            "what was the name of that book i mentioned wanting",
            "what did i say my favorite tea was",
            "remind me what i told you about the trip",
            "what was the project name i mentioned",
            "what color did i tell you i wanted for the room",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P11: remember → goal_done (store info, no return value beyond ack)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p11_store_memory",
        "description": "User tells the companion to remember a fact. Companion stores via remember, confirms with brief goal_done acknowledgment.",
        "tools": ["remember", "goal_done"],
        "en_prompts": [
            "remember that my partner's name is Mira",
            "remember i prefer earl grey to english breakfast",
            "remember my deadline for the manuscript is june 30",
            "please remember i'm allergic to shellfish",
            "remember the dog's name is Otto",
            "remember i'm trying to write 500 words a day this month",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P12: web → read_content (follow URL) → goal_done (deeper read)
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p12_web_then_read",
        "description": "User asks something specific that requires fetching a full page. Companion uses web_search to find the right page, then read_content to extract details, then goal_done with the answer.",
        "tools": ["web_search", "read_content", "goal_done"],
        "en_prompts": [
            "what's the latest reliable estimate on global solar deployment",
            "find me the actual numbers on this year's wildfire season in canada",
            "what's the current price trajectory of lithium carbonate",
            "tell me what the latest IPCC report actually says about 1.5C",
            "what's the current best-known result on the Collatz conjecture",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P13: query expansion — first attempt empty, retry with better keywords
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p13_query_expansion",
        "description": "User uses a general term. First library_card with that term gets 0 results. Companion retries library_card with expanded/related keywords (synonyms or domain terms), gets results, then goal_done. This teaches query expansion.",
        "tools": ["library_card", "library_card", "goal_done"],
        "en_prompts": [
            "what does our library have on green energy",
            "look up clean tech in our collection",
            "find material on sustainable power",
            "search the library for environmentally friendly energy",
            "find what we have on healthful eating",
            "look up brain training in our library",
            "find books on how plants communicate",
            "search the library for material on space habitats",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P14: simple respond — chitchat, no tool needed
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p14_no_tool_chat",
        "description": "User says hello or chitchats. No tool is appropriate. Companion responds directly via goal_done with a brief warm prose answer.",
        "tools": ["goal_done"],
        "en_prompts": [
            "hey wyrd how's it going",
            "good morning",
            "tell me a fun fact",
            "what's on your mind today",
            "you doing ok",
            "say something nice",
            "any thoughts on the weather",
            "what would a good friend do right now",
        ],
    },

    # ─────────────────────────────────────────────────────────────────────
    # P15: refuse politely after exhausting tools — but ONLY after trying
    # ─────────────────────────────────────────────────────────────────────
    {
        "id": "p15_polite_refuse_after_trying",
        "description": (
            "User asks about something genuinely obscure. Companion tries library_card "
            "(empty), then web_search (no good hits), then admits via goal_done that it "
            "couldn't find a reliable answer — but only after actually exhausting the "
            "tools. This teaches honest refusal with effort, NOT premature bail."
        ),
        "tools": ["library_card", "web_search", "goal_done"],
        "en_prompts": [
            "what was the population of the city of Akkad in 2200 BCE exactly",
            "what color was Cleopatra's actual favorite robe",
            "what did Pythagoras have for breakfast on his thirtieth birthday",
            "what's the exact number of stars in the observable universe right now",
        ],
    },
]


def total_seeds():
    return sum(len(p["en_prompts"]) for p in PATTERNS)


if __name__ == "__main__":
    print(f"{len(PATTERNS)} patterns, {total_seeds()} EN seed prompts")
    for p in PATTERNS:
        print(f"  {p['id']}: {len(p['en_prompts'])} prompts, tools={p['tools']}")
