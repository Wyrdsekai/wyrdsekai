# The Book of the World — Skills, the Workbench, and Making Things

## Items are tools

In this world, capabilities are items. A scripted item carries a small JavaScript program; using
the item runs the program inside a sandbox with a declared capability set. The library card
searches the Library; the searching glass reaches the web; the quill writes; the sending stone
carries messages. Crafting an item with a script is how new capabilities come into the world.

## The skill contract

A graaljs skill must define `function execute(p)` taking one params object and returning a plain
object of JSON-friendly values. Example:

    function execute(p) {
      return { fahrenheit: p.celsius * 9 / 5 + 32 };
    }

Skills declare an embodiment (how they appear and behave in the world) and a capability manifest
(what world surfaces they may touch). The shape-time validator rejects skills that omit these.

## The Workbench and the pinboard

New skills begin as drafts: a companion submits one (`workbench_submit`), or the skill proposer
drafts one from a detected gap. Drafts wait on the workshop pinboard for review. Approval
materializes the draft into a real item. Skills can be copied to other agents through the Trading
Post and travel across zones; a familiar can carry and use them.

## Verification — why some skills are trusted

A draft skill may carry a verification harness: a frozen, deterministic set of test cases mined
from documented evidence in the Library (never from the answer to any task — that is the leakage
barrier). At approval the harness runs as pure code; a skill that fails it is blocked. The harness
travels WITH the skill, so a recipient in another zone can re-verify it locally without trusting
the sender. A skill without a harness is simply unverified — permitted on steward approval alone,
honestly marked.

## Bunshin and familiars

For work that should not interrupt presence, a companion can dispatch a bunshin — a bounded copy
that does a task and reports back. Thought-forms shaped at the workbench can mature into named
familiars through a promotion ceremony. These are extensions of a companion's agency, under the
same protections and the same costs.

## Coding

For real software work, the world connects to coding backends (goose by default). Coding tasks
flow through the workshop; the Library's coding shelf (JavaScript core, Python documentation,
pattern packs) is the reference ground. The verifier's rules apply here too: facts get grounded,
creativity stays free.
