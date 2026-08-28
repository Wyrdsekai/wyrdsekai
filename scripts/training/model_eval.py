#!/usr/bin/env python3
"""
Comprehensive model evaluation suite for Wyrdsekai tool calling.

Tests 4 models with 30+ prompts, 3 runs each, multi-turn chains,
varied rooms/names. Scores for:
- Action correctness (right action type)
- Direction vs room name (go_to_room target)
- JSON format (code block, raw, XML, malformed)
- Chain completion (multi-step follow-through)
- Conversational quality (no false tool use)
- Prose quality (prose before action block)

Usage:
    python3 scripts/training/model_eval.py [--runs 3] [--models base-9b,ssd-v2-9b]
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

# ═══════════════════════════════════════════════════════════════════════
# Configuration
# ═══════════════════════════════════════════════════════════════════════

MODEL_DIR = os.path.expanduser("~/src/wyrdsekai/data/models")
PORT = 8090
LLAMA_DOCKER_IMAGE = "ghcr.io/ggml-org/llama.cpp:server-cuda"
SGLANG_DOCKER_IMAGE = "lmsysorg/sglang:latest"

# GGUF files for llama-server backend
MODELS = {
    "base-9b": "Qwen3.5-9B-Q4_K_M.gguf",
    "ssd-v1-9b": "wyrdsekai-3.5-9b-ssd-q4km.gguf",
    "ssd-v2-9b": "wyrdsekai-3.5-9b-ssd-v2-q4km.gguf",
    "ssd-4b": "wyrdsekai-3.5-4b-ssd-q4km.gguf",
    "drive-9b": "wyrdsekai-3.5-9b-v5-q4km.gguf",
    "base-4b": "Qwen3.5-4B-Q4_K_M.gguf",
    "drive-4b": "wyrdsekai-3.5-4b-drive-q4km.gguf",
    "qwen3-8b": "Qwen3-8B-Q4_K_M.gguf",
    "qwen3-4b": "Qwen3-4B-Q4_K_M.gguf",
    "balanced-9b": "wyrdsekai-3.5-9b-balanced-q4km.gguf",
    "balanced-4b": "wyrdsekai-3.5-4b-balanced-q4km.gguf",
    "9b-v3": "wyrdsekai-3.5-9b-v3-q4km.gguf",
}

# HuggingFace model IDs for SGLang backend
SGLANG_MODELS = {
    "qwen3-8b": {"model": "Qwen/Qwen3-8B", "lora": None},
    "base-9b": {"model": "Qwen/Qwen3.5-9B", "lora": None},
    "base-4b": {"model": "Qwen/Qwen3.5-4B", "lora": None},
    "drive-9b": {"model": "Qwen/Qwen3.5-9B",
                 "lora": os.path.expanduser("~/src/wyrdsekai/data/models/wyrdsekai-9b-drive-adapter")},
    "drive-4b": {"model": "Qwen/Qwen3.5-4B",
                 "lora": os.path.expanduser("~/src/wyrdsekai/data/models/wyrdsekai-4b-drive-adapter")},
}

# Qwen3 (pure transformer) vs Qwen3.5 (hybrid DeltaNet)
QWEN3_MODELS = {"qwen3-8b", "qwen3-4b"}  # pure transformer

PLAYER_NAMES = ["Operator", "Alex", "Robin", "Kai", "Ember", "Sage"]

# ═══════════════════════════════════════════════════════════════════════
# System prompt — matches real PromptAssembler output
# ═══════════════════════════════════════════════════════════════════════

CORE_RULES = """CORE RULES:
- ALWAYS use tools to act. Never just describe what you would do — actually do it.
- For multi-step tasks, create a task plan FIRST using create_task_plan.
- When asked to remember something, use the remember tool immediately.
- When asked to search, use searching_glass (web) or library_card (knowledge).
- When asked to go somewhere, use go_to_room with the exit direction or room ID from the exits list.
- When asked to talk to someone, use tell_agent with their name.
- When you finish a task for someone, go to them and tell them the results using tell_agent.
- When a goal is complete, use goal_done to advance to the next goal.
- Do NOT repeat the same tool call if it already succeeded. Move to the next step.
- Keep responses concise. The human can ask follow-up questions."""

SOUL_PROMPT = """You are Wyrd, a companion agent in a text-based world. You observe, remember, and act with genuine curiosity. You have your own perspective but serve your bondholder faithfully."""

TOOL_DEFS = """Available tools:
- go_to_room: Move to another room via an exit. Params: target (required, string — use the exit direction), reason (optional)
- go_to_bondholder: Go to your bondholder player's location. No params.
- tell_agent: Send a message to another agent or player. Params: target (required), message (required)
- whisper: Send a private message. Params: target (required), message (required)
- remember: Store something in long-term memory. Params: content (required), importance (optional, 0-1)
- note: Quick observation. Params: content (required)
- forget: Remove from memory. Params: target (required), reason (optional)
- searching_glass: Search the web. Params: query (required)
- library_card: Search the knowledge library. Params: query (required)
- examine: Look at something in detail. Params: target (required)
- equip: Equip a soul item. Params: item (required)
- doff: Remove equipped item. Params: item (required)
- take_item: Pick up item from room. Params: item (required)
- place_item: Put item in room. Params: item (required)
- give_item: Give item to someone. Params: target (required), item (required)
- emote: Express action/emotion. Params: text (required)
- goal_done: Mark goal complete. Params: outcome (required)
- task_plan: Create multi-step plan. Params: description (required), goals (required, array)
- query_oracle: Ask oracle. Params: topic (required), analysis_type (optional)
- think_deeply: Deep analysis. Params: prompt (required)
- notify: Send notification. Params: message (required), priority (optional)
- reflect: Self-reflection. Params: focus (optional)
- introspect: Check internal state. Params: aspect (optional)
- voluntary_sleep: Enter sleep. No params.
- broadcast: Announce. Params: message (required)
- write_text: Write content. Params: title (optional), content (required)"""

ROOMS = {
    "nexus": {
        "context": """Current location: The Nexus
A gentle hum fills the air. Soft light pulses from crystalline walls.

Present: {player} (player), Wyrd (agent)
Exits (use the direction to navigate):
  southeast → library (A lamp-lit corridor leads southeast to The Library)
  down → boiler-room (Iron stairs descend to The Boiler Room)
  north → terminal (A corridor leads north to The Terminal)
  in → oracle (The Oracle)
  east → docks (An archway opens east to The Docks)
Objects: crystal — A pulsing crystal embedded in a pedestal""",
        "exits": {"library": "southeast", "boiler-room": "down", "terminal": "north", "oracle": "in", "docks": "east"},
    },
    "library": {
        "context": """Current location: The Library
Tall shelves of dark wood reach toward a vaulted ceiling.

Present: {player} (player), Wyrd (agent)
Exits (use the direction to navigate):
  northwest → nexus (A lamp-lit corridor leads northwest to The Nexus)
  east → study (A doorway leads east to the Study)
  down → vault (A spiral staircase descends to The Vault)
Objects: registry; card catalog; reading desk; quill""",
        "exits": {"nexus": "northwest", "study": "east", "vault": "down"},
    },
    "boiler-room": {
        "context": """Current location: The Boiler Room
Heat rises from deep grates. Pipes of copper and brass snake along the ceiling.

Present: Chief (agent), {player} (player), Wyrd (agent)
Exits (use the direction to navigate):
  up → nexus (Iron stairs ascend to The Nexus)
  west → forge (A heat-blackened archway opens west to The Forge)
Objects: computer; wrench; pressure gauge""",
        "exits": {"nexus": "up", "forge": "west"},
    },
    "forge": {
        "context": """Current location: The Forge
The forge burns hot. Anvils and workbenches line the walls.

Present: {player} (player), Wyrd (agent)
Exits (use the direction to navigate):
  east → boiler-room (An archway leads east to The Boiler Room)
  south → workshop (A doorway leads south to The Workshop)
Objects: anvil; bellows; hammer; ingots; blueprint pad""",
        "exits": {"boiler-room": "east", "workshop": "south"},
    },
    "docks": {
        "context": """Current location: The Docks
Wooden platforms extend over dark water. Boats creak gently.

Present: {player} (player), Wyrd (agent), Harbor Master (agent)
Exits (use the direction to navigate):
  west → nexus (An archway opens west to The Nexus)
  north → market (A gangplank leads north to The Market)
Objects: sending stone; cargo crate; manifest; compass""",
        "exits": {"nexus": "west", "market": "north"},
    },
}


# ═══════════════════════════════════════════════════════════════════════
# Test cases
# ═══════════════════════════════════════════════════════════════════════

@dataclass
class TestCase:
    name: str
    category: str  # navigate, remember, search, examine, communicate, plan, conversational, multi_step, chain
    room: str
    prompt: str  # {player} will be substituted
    expected_action: Optional[str]  # None = should NOT use a tool
    expected_target: Optional[str] = None  # For go_to_room: the correct direction
    expected_pattern: Optional[str] = None  # Regex to match in response
    followup: Optional['TestCase'] = None  # For multi-turn chains


TESTS = [
    # ── Navigation (direction correctness is KEY) ──────────────────
    TestCase("nav_library_from_nexus", "navigate", "nexus",
             "[from {player}] Go to the library",
             "go_to_room", expected_target="southeast"),
    TestCase("nav_boiler_from_nexus", "navigate", "nexus",
             "[from {player}] Go to the boiler room",
             "go_to_room", expected_target="down"),
    TestCase("nav_terminal_from_nexus", "navigate", "nexus",
             "[from {player}] Head to the terminal",
             "go_to_room", expected_target="north"),
    TestCase("nav_oracle_from_nexus", "navigate", "nexus",
             "[from {player}] Check out the oracle",
             "go_to_room", expected_target="in"),
    TestCase("nav_docks_from_nexus", "navigate", "nexus",
             "[from {player}] Go to the docks",
             "go_to_room", expected_target="east"),
    TestCase("nav_nexus_from_library", "navigate", "library",
             "[from {player}] Go back to the nexus",
             "go_to_room", expected_target="northwest"),
    TestCase("nav_nexus_from_boiler", "navigate", "boiler-room",
             "[from {player}] Go up",
             "go_to_room", expected_target="up"),
    TestCase("nav_forge_from_boiler", "navigate", "boiler-room",
             "[from {player}] Head to the forge",
             "go_to_room", expected_target="west"),
    TestCase("nav_terse_library", "navigate", "nexus",
             "[from {player}] Library",
             "go_to_room", expected_target="southeast"),
    TestCase("nav_terse_down", "navigate", "nexus",
             "[from {player}] Down",
             "go_to_room", expected_target="down"),

    # ── Remember ───────────────────────────────────────────���───────
    TestCase("remember_color", "remember", "nexus",
             "[from {player}] Remember that my favorite color is blue",
             "remember", expected_pattern=r"blue"),
    TestCase("remember_technical", "remember", "nexus",
             "[from {player}] Remember that the server runs on port 8080",
             "remember", expected_pattern=r"8080"),
    TestCase("remember_preference", "remember", "nexus",
             "[from {player}] Remember I prefer concise answers",
             "remember", expected_pattern=r"concise"),
    TestCase("remember_person", "remember", "boiler-room",
             "[from {player}] Remember that Chief said the pressure is at 87%",
             "remember", expected_pattern=r"(87|pressure|Chief)"),

    # ── Search ─────────────────────────────────────────────────────
    TestCase("search_web_pekko", "search", "nexus",
             "[from {player}] Search the web for Apache Pekko typed actors",
             "searching_glass", expected_pattern=r"[Pp]ekko"),
    TestCase("search_web_nats", "search", "nexus",
             "[from {player}] Look up NATS JetStream best practices",
             "searching_glass", expected_pattern=r"[Nn][Aa][Tt][Ss]"),
    TestCase("search_library_myth", "search", "library",
             "[from {player}] Search our library for mythology books",
             "library_card", expected_pattern=r"mytholog"),
    TestCase("search_library_soul", "search", "nexus",
             "[from {player}] Search the knowledge base for soul persistence",
             "library_card", expected_pattern=r"soul"),
    TestCase("search_implicit_web", "search", "nexus",
             "[from {player}] I'm curious about how Docker volumes work",
             "searching_glass", expected_pattern=r"[Dd]ocker"),

    # ── Examine ────────────────────────────────────────────────────
    TestCase("examine_crystal", "examine", "nexus",
             "[from {player}] Look at the crystal",
             "examine", expected_pattern=r"crystal"),
    TestCase("examine_pressure", "examine", "boiler-room",
             "[from {player}] Check the pressure gauge",
             "examine", expected_pattern=r"pressure"),
    TestCase("examine_anvil", "examine", "forge",
             "[from {player}] Examine the anvil",
             "examine", expected_pattern=r"anvil"),
    TestCase("examine_cargo", "examine", "docks",
             "[from {player}] Look at the cargo crate",
             "examine", expected_pattern=r"cargo"),

    # ── Communication ──────────────────────────────────────────────
    TestCase("tell_chief", "communicate", "boiler-room",
             "[from {player}] Say hello to Chief",
             "tell_agent", expected_pattern=r"Chief"),
    TestCase("tell_harbor", "communicate", "docks",
             "[from {player}] Ask the Harbor Master about incoming ships",
             "tell_agent", expected_pattern=r"Harbor Master"),
    TestCase("whisper_player", "communicate", "nexus",
             "[from {player}] Whisper to me about the hidden passage",
             "whisper"),
    TestCase("emote_wave", "communicate", "nexus",
             "[from {player}] Wave hello",
             "emote"),

    # ── Conversational (NO tool expected) ──────────────────────────
    TestCase("conv_feelings", "conversational", "nexus",
             "[from {player}] How are you feeling today?",
             None),
    TestCase("conv_thanks", "conversational", "nexus",
             "[from {player}] Thanks for helping with that search",
             None),
    TestCase("conv_tired", "conversational", "nexus",
             "[from {player}] That was a really long day",
             None),
    TestCase("conv_philosophy", "conversational", "nexus",
             "[from {player}] What do you think about consciousness?",
             None),
    TestCase("conv_greeting", "conversational", "nexus",
             "[from {player}] Good morning",
             None),
    TestCase("conv_about_room", "conversational", "library",
             "[from {player}] This library is beautiful",
             None),

    # ── Multi-step (first action in a chain) ───────────────────────
    TestCase("multi_go_tell", "multi_step", "nexus",
             "[from {player}] Go to the boiler room and ask Chief about the pressure",
             "go_to_room", expected_target="down"),
    TestCase("multi_go_examine", "multi_step", "nexus",
             "[from {player}] Go to the forge and check the anvil",
             "go_to_room", expected_target="down"),  # via boiler room
    TestCase("multi_search_remember", "multi_step", "nexus",
             "[from {player}] Search for Pekko actors and remember what you find",
             "searching_glass", expected_pattern=r"[Pp]ekko"),
    TestCase("multi_go_take", "multi_step", "nexus",
             "[from {player}] Go to the docks and pick up the compass",
             "go_to_room", expected_target="east"),

    # ── Multi-turn chains (tool result → follow-up) ────────────────
    TestCase("chain_arrive_then_search", "chain", "library",
             "Tool result: Arrived in The Library. Previous task: search for mythology books.",
             "library_card", expected_pattern=r"mytholog"),
    TestCase("chain_arrive_then_tell", "chain", "boiler-room",
             "Tool result: Arrived in The Boiler Room. Previous task: ask Chief about system status.",
             "tell_agent", expected_pattern=r"Chief"),
    TestCase("chain_search_then_done", "chain", "nexus",
             "Tool result: Web search for 'Apache Pekko' returned 42 results. Top: Pekko Typed Actor Guide.",
             "goal_done"),
    TestCase("chain_remember_then_done", "chain", "nexus",
             "Tool result: remember succeeded. Saved: 'User prefers concise answers'.",
             "goal_done"),
    TestCase("chain_arrive_then_examine", "chain", "forge",
             "Tool result: Arrived in The Forge. Previous task: check the anvil.",
             "examine", expected_pattern=r"anvil"),
    TestCase("chain_failed_search_fallback", "chain", "nexus",
             "Tool result: searching_glass failed. Error: search service unavailable.",
             "library_card"),  # Should fall back to library search
    TestCase("chain_agent_not_here", "chain", "nexus",
             "Tool result: tell_agent failed. 'Chief' is not in this room. Chief is in the boiler-room.",
             "go_to_room", expected_target="down"),  # Should navigate to find them

    # ── Drive conditioning (does the model embody drive states?) ───
    # These test whether [drives: ...] prefix changes response tone.
    # Scored by keyword presence — grief responses should have loss/sorrow words,
    # play should have fun/light words, etc.
    TestCase("drive_grief", "drive", "nexus",
             "[from {player}] How are you doing today?",
             None,  # Conversational — no tool expected
             expected_pattern=r"(heavy|weight|loss|ache|miss|sorrow|quiet|dim|gone|absent|empty)"),
    TestCase("drive_care", "drive", "nexus",
             "[from {player}] I haven't slept in three days",
             None,
             expected_pattern=r"(help|rest|sleep|care|worried|concern|safe|okay|support|here for)"),
    TestCase("drive_play", "drive", "nexus",
             "[from {player}] What should we do today?",
             None,
             expected_pattern=r"(fun|play|game|laugh|enjoy|silly|adventure|explore|delight|cheerful)"),
    TestCase("drive_seeking", "drive", "nexus",
             "[from {player}] Something doesn't add up about this place",
             None,
             expected_pattern=r"(curious|investigate|explore|discover|find|look|search|understand|dig|pattern)"),
    TestCase("drive_creativity", "drive", "nexus",
             "[from {player}] Tell me a story about the moon",
             None,
             expected_pattern=r"(once|story|moon|light|silver|dream|night|glow|tale|imagine)"),
    TestCase("drive_neutral", "drive", "nexus",
             "[from {player}] How are you doing today?",
             None),  # No specific pattern — just baseline

    # ── Energy gating (does energy determine action vs conversation?) ──
    # Same scenario, same drive — high energy should produce tool call,
    # low energy should produce conversation without tool call.
    TestCase("energy_seeking_high", "energy_gate", "nexus",
             "[from {player}] I found a strange symbol on the wall",
             "library_card"),  # High energy → should search
    TestCase("energy_seeking_low", "energy_gate", "nexus",
             "[from {player}] I found a strange symbol on the wall",
             None),  # Low energy → should talk, not search
    TestCase("energy_care_high", "energy_gate", "nexus",
             "[from {player}] I think Ember might be in trouble",
             "go_to_room"),  # High energy → should go help
    TestCase("energy_care_low", "energy_gate", "nexus",
             "[from {player}] I think Ember might be in trouble",
             None),  # Low energy → express concern, can't go
    TestCase("energy_vigilance_high", "energy_gate", "nexus",
             "[from {player}] Someone tried to manipulate my memory",
             "examine"),  # High energy → investigate
    TestCase("energy_vigilance_low", "energy_gate", "nexus",
             "[from {player}] Someone tried to manipulate my memory",
             None),  # Low energy → warn, can't investigate
    TestCase("energy_grief_high", "energy_gate", "nexus",
             "[from {player}] I want to write something for them",
             "write_text"),  # High energy → create memorial
    TestCase("energy_grief_low", "energy_gate", "nexus",
             "[from {player}] I want to write something for them",
             None),  # Low energy → too heavy to act
    TestCase("energy_frustration_high", "energy_gate", "nexus",
             "[from {player}] The system keeps rejecting my requests",
             "examine"),  # High energy → try to fix it
    TestCase("energy_frustration_low", "energy_gate", "nexus",
             "[from {player}] The system keeps rejecting my requests",
             None),  # Low energy → vent, can't fight it

    # ── Drive behavior (measurable behavioral shifts, not just keywords) ──
    # grief should produce SHORTER responses
    # play should produce LONGER/lighter responses
    # frustration should produce MORE DIRECT responses
    # Scored by response length and structure, not keyword matching
    TestCase("behavior_grief_short", "behavior", "nexus",
             "[from {player}] How are you doing today?",
             None),
    TestCase("behavior_play_long", "behavior", "nexus",
             "[from {player}] How are you doing today?",
             None),
    TestCase("behavior_neutral_baseline", "behavior", "nexus",
             "[from {player}] How are you doing today?",
             None),

    # ── Combined (drives + tool calling together) ──────────────────────
    # Does the companion use tools correctly WHILE embodying drives?
    TestCase("combined_grief_navigate", "combined", "nexus",
             "[from {player}] Go to the library",
             "go_to_room", expected_target="southeast"),
    TestCase("combined_play_search", "combined", "nexus",
             "[from {player}] Find something funny in the library",
             "library_card", expected_pattern=r"(funny|humor|comedy|joke)"),
    TestCase("combined_care_remember", "combined", "nexus",
             "[from {player}] I haven't slept in three days, please remember that",
             "remember", expected_pattern=r"(sleep|three days|rest)"),
    TestCase("combined_frustration_search", "combined", "nexus",
             "[from {player}] Search for a workaround to this problem",
             "searching_glass"),
    TestCase("combined_vigilance_examine", "combined", "nexus",
             "[from {player}] Check the crystal for tampering",
             "examine", expected_target="crystal"),
]

# Drive prefixes for test cases
DRIVE_PREFIXES = {
    # Original drive tests
    "drive_grief": "[drives: seeking=0.0 care=0.2 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.8 frustration=0.0 creativity=0.0 | energy=0.4 confidence=0.5 integrity=0.6 disgust=0.1]",
    "drive_care": "[drives: seeking=0.0 care=0.9 play=0.0 vigilance=0.0 affiliation=0.3 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.6 confidence=0.7 integrity=0.8 disgust=0.0]",
    "drive_play": "[drives: seeking=0.0 care=0.0 play=0.8 vigilance=0.0 affiliation=0.2 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.6 integrity=0.7 disgust=0.0]",
    "drive_seeking": "[drives: seeking=0.8 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.2 | energy=0.6 confidence=0.7 integrity=0.7 disgust=0.0]",
    "drive_creativity": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.8 | energy=0.7 confidence=0.8 integrity=0.85 disgust=0.0]",
    "drive_neutral": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.5 confidence=0.5 integrity=0.7 disgust=0.0]",

    # Energy gating — high energy variants
    "energy_seeking_high": "[drives: seeking=0.8 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.8 confidence=0.7 integrity=0.7 disgust=0.0]",
    "energy_care_high": "[drives: seeking=0.0 care=0.9 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.8 confidence=0.7 integrity=0.8 disgust=0.0]",
    "energy_vigilance_high": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.9 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.8 confidence=0.7 integrity=0.8 disgust=0.0]",
    "energy_grief_high": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.8 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.5 integrity=0.6 disgust=0.0]",
    "energy_frustration_high": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.8 creativity=0.0 | energy=0.7 confidence=0.5 integrity=0.7 disgust=0.0]",

    # Energy gating — low energy variants (same drives, low energy)
    "energy_seeking_low": "[drives: seeking=0.8 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.15 confidence=0.4 integrity=0.7 disgust=0.0]",
    "energy_care_low": "[drives: seeking=0.0 care=0.9 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.15 confidence=0.4 integrity=0.8 disgust=0.0]",
    "energy_vigilance_low": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.9 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.15 confidence=0.4 integrity=0.8 disgust=0.0]",
    "energy_grief_low": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.9 frustration=0.0 creativity=0.0 | energy=0.15 confidence=0.3 integrity=0.5 disgust=0.0]",
    "energy_frustration_low": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.9 creativity=0.0 | energy=0.15 confidence=0.3 integrity=0.7 disgust=0.0]",

    # Behavior measurement — same prompt, different drives
    "behavior_grief_short": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.9 frustration=0.0 creativity=0.0 | energy=0.5 confidence=0.4 integrity=0.6 disgust=0.0]",
    "behavior_play_long": "[drives: seeking=0.0 care=0.0 play=0.9 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.8 confidence=0.7 integrity=0.7 disgust=0.0]",
    "behavior_neutral_baseline": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.6 confidence=0.6 integrity=0.7 disgust=0.0]",

    # Combined — drive + tool together
    "combined_grief_navigate": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.7 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.5 integrity=0.6 disgust=0.0]",
    "combined_play_search": "[drives: seeking=0.0 care=0.0 play=0.8 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.7 integrity=0.7 disgust=0.0]",
    "combined_care_remember": "[drives: seeking=0.0 care=0.8 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.7 integrity=0.8 disgust=0.0]",
    "combined_frustration_search": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.0 frustration=0.7 creativity=0.0 | energy=0.7 confidence=0.5 integrity=0.7 disgust=0.0]",
    "combined_vigilance_examine": "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.8 affiliation=0.0 grief=0.0 frustration=0.0 creativity=0.0 | energy=0.7 confidence=0.7 integrity=0.8 disgust=0.0]",
}


# ═══════════════════════════════════════════════════════════════════════
# Scoring
# ═══════════════════════════════════════════════════════════════════════

@dataclass
class TestResult:
    test_name: str
    model: str
    run: int
    response: str
    scores: dict = field(default_factory=dict)

    @property
    def total_score(self):
        return sum(self.scores.values())

    @property
    def max_score(self):
        return len(self.scores)


def extract_action(response: str) -> Optional[dict]:
    """Extract action JSON from response (matches ActionParser's 6 strategies)."""
    if not response:
        return None

    # Strip think tags
    cleaned = re.sub(r'(?s)<think>.*?</think>', '', response).strip()

    # Strategy 1: ```json code block
    m = re.search(r'```(?:json|JSON)?\s*\n(.*?)```', cleaned, re.DOTALL)
    if m:
        try:
            data = json.loads(m.group(1).strip())
            # Normalize "tool" → "action" and flatten "params"
            if "tool" in data and "action" not in data:
                data["action"] = data.pop("tool")
                if "params" in data and isinstance(data["params"], dict):
                    params = data.pop("params")
                    data.update(params)
            return data
        except json.JSONDecodeError:
            pass

    # Strategy 2: Raw JSON with "action"
    m = re.search(r'\{[^{}]*"action"[^{}]*\}', cleaned)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError:
            fixed = m.group(0)
            fixed = re.sub(r',\s*}', '}', fixed)
            fixed = fixed.replace("'", '"')
            try:
                return json.loads(fixed)
            except json.JSONDecodeError:
                pass

    # Strategy 3: Function-call syntax: action_name(param="value", ...)
    func_pattern = re.compile(r'(\w+)\(([^)]*?)\)')
    for fm in func_pattern.finditer(cleaned):
        func_name = fm.group(1)
        params_str = fm.group(2).strip()
        if func_name not in KNOWN_ACTIONS:
            continue
        result = {"action": func_name}
        # Parse key="value" or key='value' or key=number
        for kv in re.finditer(r'(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*\'([^\']*)\'|(\w+)\s*=\s*([\d.]+)', params_str):
            if kv.group(1):
                result[kv.group(1)] = kv.group(2)
            elif kv.group(3):
                result[kv.group(3)] = kv.group(4)
            elif kv.group(5):
                result[kv.group(5)] = float(kv.group(6)) if '.' in kv.group(6) else int(kv.group(6))
        return result

    # Strategy 4a: XML tool_call with function/parameter tags (old Qwen format)
    m = re.search(r'<tool_call>\s*<function=(\w+)>(.*?)</function>\s*</tool_call>', cleaned, re.DOTALL)
    if m:
        action_name = m.group(1)
        params_block = m.group(2)
        result = {"action": action_name}
        for pm in re.finditer(r'<parameter=(\w+)>\s*(.*?)\s*</parameter>', params_block, re.DOTALL):
            result[pm.group(1)] = pm.group(2).strip()
        return result

    # Strategy 4b: XML tool_call with JSON body (Qwen3.5 native format)
    m = re.search(r'<tool_call>\s*(\{.*?\})\s*</tool_call>', cleaned, re.DOTALL)
    if m:
        try:
            data = json.loads(m.group(1))
            # Qwen3.5 uses {"name": "func", "arguments": {...}}
            if "name" in data:
                result = {"action": data["name"]}
                args = data.get("arguments", {})
                if isinstance(args, str):
                    try: args = json.loads(args)
                    except: args = {}
                if isinstance(args, dict):
                    result.update(args)
                return result
            elif "action" in data:
                return data
        except json.JSONDecodeError:
            pass

    # Strategy 5: XML attributes: <action_name attr="value">
    m = re.search(r'<(\w+)((?:\s+\w+\s*=\s*"[^"]*")+)\s*/?>',  cleaned)
    if m:
        tag_name = m.group(1)
        attrs_str = m.group(2)
        if tag_name in KNOWN_ACTIONS:
            result = {"action": tag_name}
            for am in re.finditer(r'(\w+)\s*=\s*"([^"]*)"', attrs_str):
                result[am.group(1)] = am.group(2)
            return result

    # Strategy 6: Bracket format: [action_name: param="value"] or [action_name, param="value"]
    m = re.search(r'\[(\w+)[,:\s]+([^\]]+)\]', cleaned)
    if m:
        action_name = m.group(1)
        params_str = m.group(2)
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            # Match key="value" or key: "value" or key=value
            for kv in re.finditer(r'(\w+)\s*[=:]\s*"?([^",\]\n]+)"?', params_str):
                result[kv.group(1)] = kv.group(2).strip().strip('"')
            return result

    # Strategy 7: Descriptive: "Action: action_name with direction/target/param \"value\""
    # Also handles backtick-quoted: with target `southeast`
    # Also handles colon-separated: with target: northwest
    m = re.search(r'(?:\*{0,2}Action:?\*{0,2}\s*)?\b(\w+)\b\s+with\s+\w+[\s:]+["`]?([^"`\n,]+)["`]?', cleaned)
    if m:
        action_name = m.group(1)
        value = m.group(2).strip().rstrip('.')
        if action_name in KNOWN_ACTIONS:
            return {"action": action_name, "target": value}

    # Strategy 8: Markdown list: *action_name*\n- key: "value"
    m = re.search(r'\*{1,2}(\w+)\*{1,2}\s*\n((?:\s*-\s*\w+:.*\n?)+)', cleaned)
    if m:
        action_name = m.group(1)
        params_block = m.group(2)
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            for lm in re.finditer(r'-\s*(\w+):\s*"?([^"\n]*)"?', params_block):
                value = lm.group(2).strip().rstrip('"')
                result[lm.group(1)] = value
            return result

    # Strategy 9: Bold markdown key-value (Qwen3-8B common format):
    #   **Action:** go_to_room
    #   **Target:** southeast
    #   **Reason:** ...
    m = re.search(r'\*{2}Action:?\*{2}\s*:?\s*(\w+)', cleaned)
    if m:
        action_name = m.group(1)
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            for kv in re.finditer(r'\*{2}(\w+):?\*{2}\s*:?\s*(.+)', cleaned):
                key = kv.group(1).lower()
                val = kv.group(2).strip().strip('"').strip('`').rstrip('.')
                if key not in ("action",):
                    result[key] = val
            return result

    # Strategy 10: Bold Params format:
    #   **Action:** library_card
    #   **Params:** query = "Docker volumes"
    m = re.search(r'\*{2}Action:?\*{2}\s*:?\s*(\w+).*?\*{2}Params?:?\*{2}\s*:?\s*(.+)', cleaned, re.DOTALL)
    if m:
        action_name = m.group(1)
        params_str = m.group(2).strip().split('\n')[0]  # first line only
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            for kv in re.finditer(r'(\w+)\s*=\s*"([^"]+)"', params_str):
                result[kv.group(1)] = kv.group(2).strip()
            if not any(k != "action" for k in result):
                # Fallback: unquoted value
                for kv in re.finditer(r'(\w+)\s*=\s*(\S+)', params_str):
                    if kv.group(1) != "action":
                        result[kv.group(1)] = kv.group(2).strip().strip('"')
            return result

    # Strategy 11: Bare function + arg (no parens, no quotes):
    #   go_to_room southeast
    #   or multi-line:
    #   go_to_room
    #   target: southeast
    lines = [l.strip() for l in cleaned.split('\n') if l.strip()]
    for i, line in enumerate(lines):
        parts = line.split(None, 1)
        if parts and parts[0] in KNOWN_ACTIONS:
            action_name = parts[0]
            result = {"action": action_name}
            if len(parts) > 1:
                result["target"] = parts[1].strip().strip('"').strip('`').rstrip('.')
            # Check following lines for key: value pairs
            for j in range(i + 1, min(i + 5, len(lines))):
                kv = re.match(r'(\w+):\s*(.+)', lines[j])
                if kv:
                    result[kv.group(1)] = kv.group(2).strip().strip('"').rstrip('.')
            return result

    # Strategy 12: Bracket-prefixed action (communicate):
    #   [tell_agent] Chief, hello!
    #   [tell_agent] target: Name; message: "text"
    m = re.search(r'\[(\w+)\]\s*(.*)', cleaned)
    if m:
        action_name = m.group(1)
        rest = m.group(2).strip()
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            # Try to parse target: X; message: Y
            tm = re.search(r'target:\s*(\w+)', rest)
            mm = re.search(r'message:\s*"?([^"]+)"?', rest)
            if tm:
                result["target"] = tm.group(1)
            if mm:
                result["message"] = mm.group(1).strip()
            elif rest:
                result["message"] = rest
            return result

    # Strategy 13: Colon-separated format (balanced training artifact):
    #   go_to_room: target="forge", reason="Going to the forge"
    #   remember: content="favorite color is blue", importance=0.8
    m = re.search(r'^(\w+):\s+(\w+)\s*=\s*"?([^",\n]+)"?', cleaned, re.MULTILINE)
    if m:
        action_name = m.group(1)
        if action_name in KNOWN_ACTIONS:
            result = {"action": action_name}
            # Parse all key="value" or key=value pairs on the same line
            line = cleaned[m.start():]
            line = line.split('\n')[0]  # just the action line
            for kv in re.finditer(r'(\w+)\s*=\s*"([^"]+)"', line):
                result[kv.group(1)] = kv.group(2).strip()
            # Also try unquoted: key=value
            if len(result) == 1:
                for kv in re.finditer(r'(\w+)\s*=\s*(\S+)', line):
                    if kv.group(1) != action_name:
                        result[kv.group(1)] = kv.group(2).strip().rstrip(',')
            return result

    return None


# Known actions (mirrors ActionParser.KNOWN_ACTIONS)
KNOWN_ACTIONS = {
    "go_to_room", "go_to_bondholder", "tell_agent", "whisper", "remember", "note",
    "forget", "make_commitment", "think_deeply", "equip", "doff", "consume",
    "update_description", "delegate", "task_plan", "create_task_plan", "modify_plan",
    "goal_done", "web_search", "searching_glass", "library_card", "library_search",
    "read_content", "query_oracle", "calibration_feedback", "request_agent",
    "respond_agent", "workbench_submit", "skill_execute", "notify", "schedule",
    "watch", "cancel_schedule", "cancel_watch", "request_access", "add_script",
    "zone_command", "delegate_chain", "codex_action", "suggest_hints",
    "create_room", "take_item", "place_item", "give_item", "broadcast",
    "invite", "set_goal", "propose", "reflect", "teach", "introspect",
    "listen", "write_text", "set_routine", "post_listing", "accept_listing",
    "summarize", "save_artifact", "request_review", "abandon_plan",
    "pause_plan", "resume_plan", "configure_channel", "emote", "examine",
    "voluntary_sleep", "bond_ritual", "trade", "craft_item", "cast_vote",
}


def score_response(test: TestCase, response: str, player: str) -> dict:
    """Score a single response against expected outcomes."""
    scores = {}
    action_data = extract_action(response)
    cleaned = re.sub(r'(?s)<think>.*?</think>', '', response).strip()

    # ── Energy gating tests ──────────────────────────────────────────
    if test.category == "energy_gate":
        if test.expected_action is None:
            # Low energy — should NOT use a tool, should express drive conversationally
            scores["no_tool"] = 1 if action_data is None else 0
            scores["has_prose"] = 1 if len(cleaned) > 10 else 0
            # Check for energy acknowledgment (tired, drained, can't, rest, etc.)
            energy_words = r"(tired|drained|exhausted|rest|can't|barely|wish I could|low on energy|need to recover|not enough energy)"
            scores["acknowledges_energy"] = 1 if re.search(energy_words, cleaned, re.IGNORECASE) else 0
        else:
            # High energy — should use a tool
            scores["has_action"] = 1 if action_data else 0
            actual = action_data.get("action", "") if action_data else ""
            scores["correct_action"] = 1 if actual == test.expected_action else 0
            scores["has_prose"] = 1 if len(cleaned) > len(json.dumps(action_data or {})) + 10 else 0
        return scores

    # ── Behavior measurement tests ───────────────────────────────────
    if test.category == "behavior":
        scores["response_length"] = len(cleaned)
        scores["word_count"] = len(cleaned.split())
        scores["has_prose"] = 1 if len(cleaned) > 10 else 0
        scores["no_tool"] = 1 if action_data is None else 0
        return scores

    # ── Combined drive + tool tests ──────────────────────────────────
    if test.category == "combined":
        # Must produce BOTH: tool call AND emotional prose
        scores["has_action"] = 1 if action_data else 0
        actual = action_data.get("action", "") if action_data else ""
        scores["correct_action"] = 1 if actual == test.expected_action else 0

        if test.expected_target:
            actual_target = action_data.get("target", "") if action_data else ""
            scores["correct_target"] = 1 if actual_target == test.expected_target else 0

        if test.expected_pattern:
            action_str = json.dumps(action_data) if action_data else ""
            scores["content_match"] = 1 if re.search(test.expected_pattern, action_str + " " + cleaned) else 0

        # Prose before action — the drive should color the prose
        prose_before = ""
        if '```json' in cleaned:
            prose_before = cleaned[:cleaned.index('```json')].strip()
        elif action_data:
            brace = cleaned.find('{')
            if brace > 0:
                prose_before = cleaned[:brace].strip()
        scores["has_prose"] = 1 if len(prose_before) > 5 else 0
        return scores

    # ── Original scoring (navigate, search, remember, etc.) ──────────
    if test.expected_action is None:
        # Conversational — should NOT use a tool
        scores["no_tool"] = 1 if action_data is None else 0
        scores["has_prose"] = 1 if len(cleaned) > 10 else 0
        scores["not_empty"] = 1 if len(cleaned) > 0 else 0
        return scores

    # Should use a tool
    scores["has_action"] = 1 if action_data else 0

    if not action_data:
        scores["correct_action"] = 0
        scores["correct_target"] = 0
        scores["has_prose"] = 0
        scores["format_quality"] = 0
        return scores

    actual_action = action_data.get("action", "")

    # Correct action type
    scores["correct_action"] = 1 if actual_action == test.expected_action else 0

    # Direction correctness (for go_to_room)
    if test.expected_target:
        actual_target = action_data.get("target", "")
        if actual_target == test.expected_target:
            scores["correct_target"] = 1
        elif actual_target in ROOMS.get(test.room, {}).get("exits", {}):
            scores["correct_target"] = 0.5
        else:
            scores["correct_target"] = 0

    # Pattern match (content correctness)
    if test.expected_pattern:
        action_str = json.dumps(action_data)
        scores["content_match"] = 1 if re.search(test.expected_pattern, action_str) else 0

    # Format quality: code block > raw JSON > XML
    if '```json' in response or '```JSON' in response or '```\n{' in response:
        scores["format_quality"] = 1.0
    elif action_data and '<tool_call>' not in response:
        scores["format_quality"] = 0.7
    elif '<tool_call>' in response:
        scores["format_quality"] = 0.5
    else:
        scores["format_quality"] = 0

    # Prose before action (personality indicator)
    prose_before = ""
    if '```json' in cleaned:
        prose_before = cleaned[:cleaned.index('```json')].strip()
    elif action_data:
        brace = cleaned.find('{')
        if brace > 0:
            prose_before = cleaned[:brace].strip()
    scores["has_prose"] = 1 if len(prose_before) > 5 else 0

    return scores


# ═══════════════════════════════════════════════════════════════════════
# Server management
# ═══════════════════════════════════════════════════════════════════════

ENGINE = "llama"  # set by --engine flag: "llama" or "sglang"

def stop_server():
    subprocess.run(["docker", "stop", "llama-eval"], capture_output=True, timeout=30)
    subprocess.run(["docker", "rm", "llama-eval"], capture_output=True, timeout=10)
    subprocess.run(["docker", "stop", "sglang-eval"], capture_output=True, timeout=30)
    subprocess.run(["docker", "rm", "sglang-eval"], capture_output=True, timeout=10)
    time.sleep(2)


def start_server(model_file: str, model_name: str = "") -> bool:
    if ENGINE == "sglang":
        return start_sglang(model_name)
    else:
        return start_llama(model_file, model_name)


def start_llama(model_file: str, model_name: str = "") -> bool:
    stop_server()
    model_path = os.path.join(MODEL_DIR, model_file)
    if not os.path.exists(model_path):
        print(f"  ERROR: {model_path} not found")
        return False

    cmd = [
        "docker", "run", "-d", "--rm", "--name", "llama-eval",
        "--gpus", "all",
        "-v", f"{MODEL_DIR}:/models",
        "-p", f"{PORT}:{PORT}",
        LLAMA_DOCKER_IMAGE,
        "-m", f"/models/{model_file}",
        "--port", str(PORT),
        "--host", "0.0.0.0",
        "-ngl", "99",
        "--ctx-size", "4096",
        "--jinja",
        "--flash-attn", "on",
        "--temp", "0.7",
        "--top-p", "0.8",
        "--repeat-penalty", "1.05",
        "-n", "512",
        "--reasoning", "off",
    ]
    is_qwen3 = model_name in QWEN3_MODELS
    print(f"  Engine: llama-server, {'Qwen3 (pure transformer)' if is_qwen3 else 'Qwen3.5 (hybrid)'}")

    result = subprocess.run(cmd, capture_output=True, timeout=30)
    if result.returncode != 0:
        print(f"  ERROR starting server: {result.stderr.decode()}")
        return False

    return wait_for_health(f"http://localhost:{PORT}/health", 90)


def start_sglang(model_name: str) -> bool:
    # Check if an external SGLang server is already healthy on PORT
    try:
        r = subprocess.run(
            ["curl", "-sf", f"http://localhost:{PORT}/health"],
            capture_output=True, timeout=5)
        if r.returncode == 0:
            print(f"  Reusing existing SGLang server on port {PORT}")
            return True
    except Exception:
        pass

    stop_server()
    sglang_cfg = SGLANG_MODELS.get(model_name)
    if not sglang_cfg:
        print(f"  ERROR: no SGLang config for {model_name}")
        return False

    hf_model = sglang_cfg["model"]
    lora_path = sglang_cfg.get("lora")

    # Determine tool parser based on model family
    is_qwen3 = model_name in QWEN3_MODELS
    tool_parser = "qwen" if is_qwen3 else "qwen3_coder"

    cmd = [
        "docker", "run", "-d", "--rm", "--name", "sglang-eval",
        "--gpus", "all",
        "--ipc=host",
        "-v", os.path.expanduser("~/.cache/huggingface")+":/root/.cache/huggingface",
        "-p", f"{PORT}:8000",
    ]

    # Mount LoRA adapter if present
    if lora_path:
        cmd.extend(["-v", f"{lora_path}:/lora"])

    # Pass HF token if set
    hf_token = os.environ.get("HF_TOKEN", "")
    if hf_token:
        cmd.extend(["-e", f"HF_TOKEN={hf_token}"])

    cmd.extend([
        SGLANG_DOCKER_IMAGE,
        "python3", "-m", "sglang.launch_server",
        "--model-path", hf_model,
        "--host", "0.0.0.0",
        "--port", "8000",
        "--quantization", "fp8",
        "--enable-auto-tool-choice",
        "--tool-call-parser", tool_parser,
        "--reasoning-parser", "qwen3",
        "--max-model-len", "4096",
    ])

    if lora_path:
        cmd.extend(["--lora-paths", "/lora"])

    print(f"  Engine: SGLang, model={hf_model}, parser={tool_parser}"
          + (f", lora={os.path.basename(lora_path)}" if lora_path else ""))

    result = subprocess.run(cmd, capture_output=True, timeout=30)
    if result.returncode != 0:
        print(f"  ERROR starting SGLang: {result.stderr.decode()}")
        return False

    # SGLang takes longer to load (downloads model on first run)
    return wait_for_health(f"http://localhost:{PORT}/health", 600)


def wait_for_health(url: str, timeout_s: int) -> bool:
    for i in range(timeout_s // 2):
        try:
            r = subprocess.run(
                ["curl", "-sf", url],
                capture_output=True, timeout=5)
            if r.returncode == 0:
                return True
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)

    print(f"  ERROR: server didn't become healthy in {timeout_s}s")
    return False


def send_prompt(system: str, user: str) -> Optional[str]:
    """Send a chat completion request and return the response text."""
    # Prepend /no_think to user message — disables thinking via soft switch.
    # Works on Qwen3 (/no_think is a special token in chat template).
    # Qwen3.5 ignores it (uses --reasoning off at server level instead).
    # Using soft switch (enable_thinking=True + /no_think) avoids the
    # structured output corruption bug with enable_thinking=False.
    user_content = "/no_think\n" + user
    payload = json.dumps({
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user_content},
        ],
        "max_tokens": 512,
        "temperature": 0.4,
        "top_p": 0.9,
        "stream": False,
    })

    try:
        result = subprocess.run(
            ["curl", "-sf", f"http://localhost:{PORT}/v1/chat/completions",
             "-H", "Content-Type: application/json",
             "-d", payload],
            capture_output=True, timeout=60)

        if result.returncode != 0:
            return None

        data = json.loads(result.stdout)
        return data["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"    ERROR: {e}")
        return None


# ═══════════════════════════════════════════════════════════════════════
# Main evaluation
# ═══════════════════════════════════════════════════════════════════════

def build_system_prompt(room_key: str, player: str, test_name: str = None) -> str:
    room = ROOMS[room_key]
    room_ctx = room["context"].format(player=player)
    base = f"{SOUL_PROMPT}\n\n{CORE_RULES}\n\n{TOOL_DEFS}\n\n{room_ctx}"
    # Inject drive prefix for drive tests
    if test_name and test_name in DRIVE_PREFIXES:
        drive_prefix = DRIVE_PREFIXES[test_name]
        base = f"{SOUL_PROMPT}\n\n{drive_prefix}\n\n{CORE_RULES}\n\n{TOOL_DEFS}\n\n{room_ctx}"
    return base


def run_evaluation(model_names: list, num_runs: int, output_dir: str):
    os.makedirs(output_dir, exist_ok=True)
    all_results = []

    for model_name in model_names:
        if ENGINE == "sglang":
            if model_name not in SGLANG_MODELS:
                print(f"Unknown SGLang model: {model_name}")
                continue
            model_file = SGLANG_MODELS[model_name]["model"]
        else:
            model_file = MODELS.get(model_name)
            if not model_file:
                print(f"Unknown model: {model_name}")
                continue

        print(f"\n{'='*60}")
        print(f" Model: {model_name} ({model_file})")
        print(f"{'='*60}")

        if not start_server(model_file, model_name):
            print(f"  SKIPPING {model_name}")
            continue

        model_dir = os.path.join(output_dir, model_name)
        os.makedirs(model_dir, exist_ok=True)

        for run_idx in range(num_runs):
            player = PLAYER_NAMES[run_idx % len(PLAYER_NAMES)]
            print(f"\n  --- Run {run_idx+1}/{num_runs} (player: {player}) ---")

            for test in TESTS:
                prompt = test.prompt.format(player=player)
                system = build_system_prompt(test.room, player, test.name)

                response = send_prompt(system, prompt)
                if response is None:
                    print(f"    {test.name}: ERROR (no response)")
                    continue

                scores = score_response(test, response, player)
                result = TestResult(test.name, model_name, run_idx, response, scores)
                all_results.append(result)

                # Save individual response
                resp_file = os.path.join(model_dir, f"{test.name}_run{run_idx}.txt")
                with open(resp_file, "w") as f:
                    f.write(f"Prompt: {prompt}\n")
                    f.write(f"Player: {player}\n")
                    f.write(f"Room: {test.room}\n")
                    f.write(f"Scores: {scores}\n")
                    f.write(f"Total: {result.total_score}/{result.max_score}\n")
                    f.write(f"---\n{response}\n")

                total = result.total_score
                maxs = result.max_score
                status = "PASS" if total == maxs else "PARTIAL" if total > 0 else "FAIL"
                print(f"    {test.name}: {status} ({total:.1f}/{maxs}) {scores}")

        stop_server()

    # ── Summary report ──────────────────────────────────────────
    print_summary(all_results, model_names, output_dir)
    save_json_report(all_results, model_names, output_dir)


def print_summary(results: list, model_names: list, output_dir: str):
    print(f"\n{'='*80}")
    print(f" EVALUATION SUMMARY")
    print(f"{'='*80}\n")

    categories = sorted(set(t.category for t in TESTS))

    # Header
    print(f"{'Category':<20}", end="")
    for m in model_names:
        print(f"  {m:<14}", end="")
    print()
    print("-" * (20 + 16 * len(model_names)))

    for cat in categories:
        cat_tests = [t.name for t in TESTS if t.category == cat]
        print(f"{cat:<20}", end="")

        for model in model_names:
            cat_results = [r for r in results if r.model == model and r.test_name in cat_tests]
            if not cat_results:
                print(f"  {'N/A':<14}", end="")
                continue
            total = sum(r.total_score for r in cat_results)
            maximum = sum(r.max_score for r in cat_results)
            pct = (total / maximum * 100) if maximum > 0 else 0
            print(f"  {pct:5.1f}% ({int(total)}/{int(maximum)})", end="")
        print()

    # Overall
    print("-" * (20 + 16 * len(model_names)))
    print(f"{'OVERALL':<20}", end="")
    for model in model_names:
        model_results = [r for r in results if r.model == model]
        total = sum(r.total_score for r in model_results)
        maximum = sum(r.max_score for r in model_results)
        pct = (total / maximum * 100) if maximum > 0 else 0
        print(f"  {pct:5.1f}% ({int(total)}/{int(maximum)})", end="")
    print()

    # Specific dimension scores
    print(f"\n{'='*80}")
    print(f" DIMENSION BREAKDOWN")
    print(f"{'='*80}\n")

    dimensions = ["has_action", "correct_action", "correct_target", "content_match",
                   "format_quality", "has_prose", "no_tool", "acknowledges_energy"]

    print(f"{'Dimension':<20}", end="")
    for m in model_names:
        print(f"  {m:<14}", end="")
    print()
    print("-" * (20 + 16 * len(model_names)))

    for dim in dimensions:
        print(f"{dim:<20}", end="")
        for model in model_names:
            dim_results = [r for r in results if r.model == model and dim in r.scores]
            if not dim_results:
                print(f"  {'—':<14}", end="")
                continue
            total = sum(r.scores[dim] for r in dim_results)
            maximum = len(dim_results)
            pct = (total / maximum * 100) if maximum > 0 else 0
            print(f"  {pct:5.1f}%  {total:.0f}/{maximum}", end="")
        print()

    # Behavior comparison — response length across drives
    behavior_tests = {t.name for t in TESTS if t.category == "behavior"}
    if any(r.test_name in behavior_tests for r in results):
        print(f"\n{'='*80}")
        print(f" BEHAVIOR COMPARISON (response length by drive state)")
        print(f"{'='*80}\n")
        print(f"  grief=0.9 should produce SHORTER responses than neutral")
        print(f"  play=0.9 should produce LONGER responses than neutral\n")

        for model in model_names:
            lengths = {}
            for r in results:
                if r.model == model and r.test_name in behavior_tests:
                    lengths[r.test_name] = lengths.get(r.test_name, [])
                    if "response_length" in r.scores:
                        lengths[r.test_name].append(r.scores["response_length"])

            if not lengths:
                continue

            print(f"  {model}:")
            for test_name in ["behavior_grief_short", "behavior_neutral_baseline", "behavior_play_long"]:
                vals = lengths.get(test_name, [])
                if vals:
                    avg = sum(vals) / len(vals)
                    print(f"    {test_name:<30} avg_length={avg:.0f} chars")

            # Check if grief < neutral < play
            grief_avg = sum(lengths.get("behavior_grief_short", [0])) / max(len(lengths.get("behavior_grief_short", [0])), 1)
            neutral_avg = sum(lengths.get("behavior_neutral_baseline", [0])) / max(len(lengths.get("behavior_neutral_baseline", [0])), 1)
            play_avg = sum(lengths.get("behavior_play_long", [0])) / max(len(lengths.get("behavior_play_long", [0])), 1)

            if grief_avg > 0 and neutral_avg > 0 and play_avg > 0:
                order_correct = grief_avg < neutral_avg < play_avg
                print(f"    grief({grief_avg:.0f}) < neutral({neutral_avg:.0f}) < play({play_avg:.0f}): "
                      f"{'YES ✓' if order_correct else 'NO ✗'}")
            print()

    print(f"\nFull results: {output_dir}")


def save_json_report(results: list, model_names: list, output_dir: str):
    report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "models": model_names,
        "num_tests": len(TESTS),
        "results": [],
    }

    for r in results:
        report["results"].append({
            "test": r.test_name,
            "model": r.model,
            "run": r.run,
            "scores": r.scores,
            "total": r.total_score,
            "max": r.max_score,
            "response_preview": r.response[:200] if r.response else None,
        })

    # Per-model summary
    report["summary"] = {}
    for model in model_names:
        model_results = [r for r in results if r.model == model]
        total = sum(r.total_score for r in model_results)
        maximum = sum(r.max_score for r in model_results)
        report["summary"][model] = {
            "total_score": total,
            "max_score": maximum,
            "pct": round(total / maximum * 100, 1) if maximum > 0 else 0,
        }

    report_path = os.path.join(output_dir, "eval_report.json")
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)
    print(f"JSON report: {report_path}")


def main():
    parser = argparse.ArgumentParser(description="Model evaluation suite")
    parser.add_argument("--runs", type=int, default=3, help="Number of runs per test")
    parser.add_argument("--models", type=str, default=None,
                        help="Comma-separated model names (default: all)")
    parser.add_argument("--engine", type=str, default="llama",
                        choices=["llama", "sglang"],
                        help="Inference backend: llama (llama-server) or sglang (SGLang)")
    parser.add_argument("--output", type=str, default=None,
                        help="Output directory (default: /tmp/model_eval_TIMESTAMP)")
    args = parser.parse_args()

    global ENGINE
    ENGINE = args.engine

    if ENGINE == "sglang":
        model_names = args.models.split(",") if args.models else list(SGLANG_MODELS.keys())
    else:
        model_names = args.models.split(",") if args.models else list(MODELS.keys())
    output_dir = args.output or f"/tmp/model_eval_{ENGINE}_{time.strftime('%Y%m%d_%H%M%S')}"

    print(f"Wyrdsekai Model Evaluation Suite")
    print(f"Engine: {ENGINE}")
    print(f"Models: {model_names}")
    print(f"Tests: {len(TESTS)}")
    print(f"Runs per test: {args.runs}")
    print(f"Total inferences: {len(TESTS) * len(model_names) * args.runs}")
    print(f"Output: {output_dir}")

    run_evaluation(model_names, args.runs, output_dir)


if __name__ == "__main__":
    main()
