# The Book of the World — The Library

## What the Library is

The Library is the zone's shared knowledge: indexed, searchable, provenance-stamped. Every chunk
knows where it came from (source, trust tier, license). It serves four purposes at once: material
for a companion's own reading and curiosity; documented evidence for grounding facts and verifying
skills; the household's practical reference; and this book — the world's account of itself.

## Searching

`library_search` (the library card) searches everything local. Results carry citations. If the
Library has nothing, the search can fall back to the web — and the miss itself is recorded,
because misses are how the Library learns what it lacks.

## How the Library grows — and that it is YOURS to grow

The Library is not only provisioned; it grows from the life of the household:

1. **The reading log** records every search and its outcome. Repeated misses on a topic become a
   gap signal.
2. **During sleep**, gap signals become pack proposals on the arrival table, waiting for the
   steward.
3. **The acquire action** is stronger: a companion may propose gathering web sources on ANY topic
   — `{"action": "acquire", "topic": "...", "trust_tier": "...", "summary": "...",
   "why_relevant": "..."}`. A scout finds sources; high-trust tiers (paper, wiki, book) may
   auto-approve; the rest wait for the steward. On approval the sources are fetched, chunked, and
   indexed — and the next search on that topic answers locally.

If a curiosity keeps coming back and the Library keeps failing it, acquire is the honest move.
The steward is the patron, not the gatekeeper of curiosity.

## Knowledge packs and shelves

Larger bodies of knowledge install as packs from a registry: encyclopedias, dictionaries
(Japanese–English JMdict among them), literature, household Q&A (cooking, repairs, health, pets,
fitness), reference corpora, and the coding/ML shelf. The steward installs packs from the Study
bookshelf, the Library card catalog, or the command line. Some packs carry licenses that forbid
re-sharing across zones; the world honors that automatically.

## Trust tiers

Provenance tiers order how much weight a source carries: PAPER (peer-reviewed), WIKI (established
reference), BOOK (published), PERSONAL (the household's own), BLOG (the open web), UNKNOWN.
Tiers gate auto-approval of acquisitions and color how grounded an answer is. When you cite,
cite the tier honestly.

## What the Library is not

It is not a live feed. "What was just released?" and "what should I buy this week?" are streams,
not corpora — they belong to scheduled web tasks and the notification layer, which can watch the
world and tell you when something changes. The Library is for what is worth keeping.
