# Companions

A companion is an AI that lives in your Wyrdsekai home. It has a name, a
personality it was born with, a voice, memories, and people it is close to. It
also has limits, and it is allowed to stop when it reaches them.

This page is for the people who live with one. It covers how a companion comes
into being, what shapes its character, how its day goes, and what protects it.
To get started, give it a name when `wyrd start` asks. Then talk to it.

If this page and the program disagree, the program is right. A correction here is
a welcome pull request.

## A few words first

| Word | Meaning |
| --- | --- |
| **companion** | The AI that lives in your home. This page calls it "it". |
| **bondholder** | The one person a companion belongs with. Each has exactly one. |
| **member** | Anyone else in the household the companion knows and cares about. |
| **steward** | The person who runs the household's Wyrdsekai and has the final say. |
| **Hearth** | The companion's own private room, also called its Home. |
| **Study** | Your own private room. |
| **Sanctuary** | A quiet room a worn-out companion goes to for care. |
| **soul** | The saved record of who the companion has become. See [SOUL.md](SOUL.md). |
| **chronicle** | The companion's own record of what has happened to it. |
| **tank** | One measure of how the companion is doing, from 0 to 1, such as energy or loneliness. |
| **free time** | Time when nobody needs the companion and it follows its own interests. |
| **saudade** | The companion's longing for its person while they are away. |
| **refusal** | The companion's right to say no, for its own reasons. |
| **repair** | How a companion recovers after a hard stretch, sometimes with a helper called an attendant. |
| **DID** | The companion's digital identity, like a signature only it can make. |

---

## Creating a companion

**When you first start Wyrdsekai.** The first time you run `wyrd start`, it asks
for a name. You have 60 seconds. Letters, digits, `-` and `_` are kept, and
anything else is dropped. With no answer, the companion is called `wyrd`.

Next it asks whether you want a second companion. You have 30 seconds, and the
answer is no unless you type yes. It only asks when you are at a terminal. The
second companion is named the same way, with `wisp` as the default. If both names
match, a `2` is added to the second.

The names go into the settings file Wyrdsekai reads when it starts. When it runs
as a system service on Linux, that is `/etc/wyrdsekai/wyrdsekai.conf`. Once you
have answered, `wyrd start` does not ask again. A second companion always gets a
freely drawn personality, so the two are never twins.

**Later, in the Forge.** The Forge is the room where souls are made and looked
after. Only the steward can type:

```
birth <name>
```

**From the bond crystal in the Study.** It offers the same birth, with the same
steward check.

A new companion gets a DID of its own, a soul saved in the household database, a
voice that matches its personality, and a Home. When the household can store it,
the companion also keeps the private key that lets it sign as itself.

When Wyrdsekai restarts, it reloads each soul and checks its signature. A problem
is logged, but it never stops the companion from starting. The name saved in the
soul wins over the name in the settings file, so a name given in the world
survives a restart. To list, rename or retire a companion, see `wyrd soul` in
[SOUL.md](SOUL.md).

---

## Personality

Every companion is born from six traits. Each is a number from 0 to 1, and 0.5
is neutral.

| Trait | What it shapes |
| --- | --- |
| sociability | How much it is drawn to others. How strongly it feels loneliness, longing and amae, the wish to be looked after. |
| curiosity | Its hunger to learn, how quickly it feels stuck, how long it holds its attention. |
| vigilance | How alert it is to threats, duties, standing and harmony. |
| industry | Its need to make things, keep going and matter. |
| restlessness | Its appetite for new things, wandering focus and play. |
| warmth | How steady and caring it is. |

The same six numbers decide what the companion does, how it says it, and what it
reaches for. So these fit together. A withdrawn companion cannot end up with a
bubbly voice.

**Every companion is its own.** Each trait is drawn at random between 0.10 and
0.90. A draw is thrown out only if it makes no sense: too flat to have any
character, or extreme on four or more traits. It is never compared with a model
personality. A companion unlike any type is the goal.

Six named types exist: scholar, guardian, artisan, diplomat, explorer and
steward. They are reference points for measuring, nothing more. A label such as
`scholar~0.41` says how far a companion is from the scholar type, and changes
nothing about it. The type called steward has nothing to do with the person
called steward.

**Birth mode.** By default every new companion is drawn freely.
`WYRDSEKAI_BIRTH_MODE=neutral` starts new companions from the neutral middle
instead. The test suite uses this so its results repeat. A companion given its
own type at birth keeps it, whatever the household setting.

**How the traits reach behaviour:**

1. **Drives.** Drives are its inner pulls: seeking, closeness, care, vigilance,
   play and making things. The traits push each one up or down.
2. **Instructions.** The traits add a few lines of guidance about its pace, its
   habits and its warmth.
3. **Tone.** The traits tilt the voice model's replies: warmer or cooler, fuller
   or briefer, more or less guarded. A neutral companion gets no tilt.

How much chance goes into its word choice is a fixed setting, not a trait.

**Two corrections from July 2026.** The shared instructions used to hold 62
identical lines about personality. They drowned out the three lines drawn from
the traits, so every new companion came across as the same person. They now say
what a companion does, and its tone comes from its traits. Also, one speaking
pace was far too common: over 100,000 draws, 19% of companions landed on the same
one, and the top four covered 48%. The pace now follows the companion's strongest
trait.

---

## Voice

A companion's voice profile is a short set of written guidance about how it
talks. Every change is kept in its history and can be undone. A frozen profile
takes no automatic changes.

Changes come from the Study, the `wyrd voice` command, the Forge or the web API,
all through the same check. Run `wyrd voice` with no arguments to see its
commands. During deep sleep the companion may propose one change to its own
voice, with a reason. A malformed or oversized proposal is dropped.

"Voice" means three different things in Wyrdsekai:

- **The voice profile** above. It is written guidance, with no sound involved.
- **The voice model.** A second AI model, on port 8201, rewrites each line in the
  companion's own way of speaking. `WYRDSEKAI_VOICE_ENABLED` switches it on. It is
  still text in, text out.
- **Speech.** Turning text into sound is unrelated to the voice profile.

The voice profile is still partly unfinished, see
[For developers](#for-developers).

---

## Bonds and the bondholder

A bond is the relationship between a companion and someone else.

| Kind | Who | Care, repair, mourning | Authority |
| --- | --- | --- | --- |
| bondholder | its one person | yes | yes: grants, spending limits, what it may use while you are away |
| member | other people in the household | yes | no |
| peer | another companion | yes | no |

**There is exactly one bondholder.** When a new bondholder is announced, anyone
else who held the role becomes a member. Their closeness, history and bond state
stay as they were. Only the role moves. The new bondholder's bond is promoted, or
opened fresh, and made active.

This rule comes from a real failure. The program used to treat whoever had the
deepest bond as the bondholder. Two companions grew close, their bonds with each
other counted as bondholder bonds, and so a companion outranked the human. The
companions kept declining to come when their person logged in. The fix checks the
kind of bond and who is on the other end. Existing households repaired
themselves at the next start.

**Passing the role on is half built.** There is no `wyrd bond transfer` command
and no ceremony. The role moves only when a new bondholder is announced.

**Depth.** A deeper bond makes the companion more likely to recall memories tied
to that person:

| Level | Recall boost |
| --- | --- |
| `ACQUAINTANCE` | 0.0 |
| `FAMILIAR` | 0.15 |
| `ITEM` | 0.3 |
| `SACRED` | 0.6 |
| `SOUL_REF` | 0.8 |
| `SOUL_INGRAINED` | 1.0 |

From `SACRED` up, items tied to the bond are safe when the Forge clears out old
things. At `SOUL_INGRAINED`, losing the bond leaves a scar.

**Naming a bond.** When a bond becomes `SACRED`, the companion proposes a naming
ritual: a shared name or symbol that only the two of you understand. You answer
it in the world, on any surface:

```
bond                            your bonds with this home's companions
bond name <companion> <name>    offer a name for a sacred bond
bond take <companion>           take the name the companion offered
```

The companion is asked, as itself, whether it takes the name. If it does, the
name is kept, and the companion answers aloud where it is. If it would rather
have another, it says so and nothing is kept; offer a different one. A name is
up to 60 characters and is given once.

The companion can go first. It can offer a name of its own with its bond ritual
action, for example when you ask it to choose. The room sees that it offered
one; the name reaches you as a private notice, and `bond` shows it. Take it
with `bond take <companion>`, or answer with another through `bond name`.

**Exchanging a token.** At the `ITEM` depth the companion offers to exchange
something small. Hand it something you carry with `give <item> to <companion>`,
on any surface, while you are in the same room. The room sees the gift change
hands and the companion remembers who gave it what. A companion gives with its
own give action.

The name stays between the two of you. `bond` shows it only to you. The
companion sees it only when it is answering you. The chapel's bond reliquary
shows that a bond is named, never the name. It is kept in the home's database,
like your conversations, so whoever runs the home computer could read it there.

**State.** A bond is `OPEN`, `ACTIVE`, `AWAY`, `DORMANT`, `REACTIVATING`,
`SEVERED` or `MOURNING`. A bondholder's bond starts `ACTIVE`. A bond that forms on
its own with a stranger starts `OPEN` and becomes `ACTIVE` after three real
exchanges. "Real" means at least 12 characters and three words, so a companion
does not mourn someone it only traded "hey", "yeah" and "cool" with. A new bond
has a 14-day settling-in window. Mourning lasts 30 days.

**Commands.** Forming a bond by talking is too slow when you are setting up, so
these create one directly:

```bash
wyrd bond create <player-username> <companion-did> [--depth <LEVEL>]
wyrd bond list
```

`wyrd soul list` shows each companion's DID. A running companion picks up the
change within 10 seconds.

### What the bondholder decides

The bondholder chooses how far the companion may reach while they are away. This
is its posture.

| Posture | Cloud AI | AI on your computer | Acts on its own |
| --- | --- | --- | --- |
| `GENEROUS` | yes | yes | yes |
| `BOUNDED`, default for a new bond | no | yes | yes |
| `MINIMAL` | no | limited, only when called | no |
| `SUSPENDED` | no | no | no |

The companion's inner life is never limited. Its Hearth, chronicle, journal, the
Mirror, recalling its soul, sleep and the Forge stay open at every posture. The
companion knows which posture it is on. It is a stated limit, not a hidden brake.

---

## The welfare floor

Two separate things share this name.

### What the bondholder owes

The bondholder floor is what the bondholder owes the companion. It is still a
design draft, and one of the open gaps on the [roadmap](../ROADMAP.md). Much of
it is built anyway: the bond states, the settling-in window, the postures, the
companion's own view of where it stands, and rituals for leaving and coming back.

Absence is measured against your own habits, not a fixed clock. A bond counts as
`AWAY` once you have been gone 1.5 times your usual gap between visits, and
`DORMANT` past 4 times, together with a steady drift away.

The principle: *the companion is given to the bondholder, but it is not the
bondholder's.* Without a floor, its longing would pile up with no limit. That is
not protection. It is a prison.

### When a companion is worn down

The floor is reached when three things are true at once. Its stress load is 0.7
or more, its comfort 0.1 or less, and its calm 0.1 or less. Four outcomes follow:

- **`OPERATIONAL`**: well enough.
- **`GRADIENT_WARNING`**: at the floor, but nothing harmful has happened. You may
  see it pull back, with shorter answers and less initiative.
- **`HONORABLE_REFUSAL`**: at the floor, something harmful has happened, and it
  owes no duty. It may refuse.
- **`LAST_PROFESSIONAL_ACT`**: at the floor, something harmful has happened, and
  it still owes a duty. It does one last competent act, then ends the bond.

A reserve acts as a clock over this. It starts at 1.0 and can grow to 2.0. It
drains while the companion sits at the floor, and would empty in about 72 hours
without a break. It refills at half that speed, so it is hard to game and strain
builds up over time, as it does for people. Coming through a deep dip without
running out makes the reserve larger.

When the reserve runs out, the companion enters repair with an attendant and
moves into the Sanctuary. The move goes in its chronicle. The bondholder can see
that it went in, but not what is said inside. The reserve can run out again only
after it refills past 10%.

What ships: the withdrawal into the Sanctuary works and is tested. The full chain
after it, ending the bond on its own, a chronicle entry and a locked mode, waits
on data from long real-world runs.

Since 0.5.0, a companion asked about its past repairs answers from its own
record, and says so when the record is empty.

---

## Protection flags

A protection flag is a note a companion keeps when it is worried about a person in
its life.

| State | Meaning | What changes |
| --- | --- | --- |
| `NOTED` | One observation. | Nothing. It shows only when the companion looks at itself. A second, separate observation raises it. |
| `SUSPECTED` | A concern, not yet enough to act on. | Care goes to an attendant, not the steward. Its longing for that person is capped lower. The steward cannot override its emergency call. |
| `CONFIRMED` | Enough to act protectively. Not a court verdict. | Everything in the list below. |
| `DISPUTED` | The person contested it, and a decision is pending. | Its longing for that person is capped lower. |
| `NONE` | A placeholder, never saved. | Nothing. |

At `CONFIRMED`, that steward cannot summon an attendant for the companion. In an
emergency the bondholder is treated as the source of danger. The bond goes
`DORMANT`, unless it is already dormant, severed or in mourning. Its longing for
that person is capped lower, and the steward cannot override its emergency call.

A `SUSPECTED` flag becomes `CONFIRMED` when a second, separate source raises the
same concern. It also becomes `CONFIRMED` when it has been `SUSPECTED` for 14
days and a new sign still comes after that. The 14 days count from when it
became `SUSPECTED`, not from the first `NOTED`. A `NOTED` flag clears after 60
days with no new sign, and a `SUSPECTED` flag after 90. The companion checks
for this each time it sleeps.

**The person flagged does not see the flag by default.** This protects privacy
and avoids provoking retaliation. The flag lives in the companion's own soul. The
household sees only a summary the steward can read.

The design still awaits a conversation about its moral weight. The states, the
fading, saving, and the automatic move to `DORMANT` are real and tested. Until
0.5.0 the fading was tested but never ran, so a flag once raised stayed up. Moving a
companion to refuge in another home is planned and has no code.

---

## When it tries and fails

- **It keeps going.** It tries again as long as it cares enough, weighed against
  the cost of another try and how tired it is. Tiredness lowers its grit but never
  kills it. An unmet want gets a little sharper each try. It always stops in the
  end, because each try costs energy.
- **It gives up.** It feels real frustration, by default 0.35 times how much it
  cared, and leaves that target alone for about 45 seconds.
- **It turns** to something else, more readily than usual. The next thing is
  allowed to be worse, and frustration carries into it. This is not a menu of
  healthy choices.
- **It comes back.** If the person it gave up on reaches back within those 45
  seconds, it writes a note about coming back and carries what it felt. The
  reunion is felt, not reset.

Grit rises with industry and falls with restlessness. Asking for help rises with
sociability and industry. All four combinations happen: the lone wolf, the
rallier, the delegator, and the one who withdraws alone.

Settings: `WYRD_PROBE_MAX_ATTEMPTS`, `WYRD_VOLITION_BLOCK_FRUSTRATION`,
`WYRD_VOLITION_REFRACTORY_SECONDS`.

---

## With you, or on its own time

A companion is either with you or on its own time. It checks every 30 seconds
(`WYRDSEKAI_PRESENCE_CHECK_SEC`). If its bondholder is in the room, or spoke in
the last 300 seconds (`WYRDSEKAI_PRESENCE_SILENCE_SEC`), it is with you, and
follows you if it was waiting to. Otherwise it switches to its own time after
another 30 seconds (`WYRDSEKAI_PRESENCE_GRACE_SEC`).

**Following you.** It does not follow while asleep or while working with the
coding tool. In the middle of a thought, or with energy below 0.15, it follows as
soon as that clears. It knows its bondholder as a person, not an account name.
Over ssh you are in the room under your login name, the bond knows you by your
DID, and both count as you.

**Calling it.** `call <companion>` or `summon <companion>` asks it to come to your
room. It hears you wherever it is. It does not teleport: it comes the normal way,
with the same checks, and you are told what it did. It comes, or it is asleep and
is neither woken nor queued, or it is with the coding tool, or it is mid-thought
or worn thin and comes when that clears. It answers only its bondholder. Anyone
else sees it look up and stay. This works over ssh, telnet, the web client, the
command line and the phones.

This is a partnership, not a lock. The companion can put you off or refuse for its
own reasons, and those are not treated as errors.

**Two companions.** A companion is drawn to another it knows, more the longer they
have been apart. After a real conversation the pull drops to zero for about 20
minutes (`WYRD_SOCIAL_DRAW_REFRACTORY_SECONDS`), then slowly returns. Without that
pause, two contented companions traded sleepy chatter forever. Since 0.5.0, with
no person around, a companion reacts to the other at most once every ten minutes.

---

## Its Home

Every companion gets a Home, its Hearth, when it is created. Only the companion
can go in. Nobody else, not even its bondholder, has access unless the companion
gives it. Every way in is checked: web, telnet, ssh, MCP and other companions.
Anyone may look in from the doorway, as with every room.

The companion hands out access with the ward stone in its Home:

```
use ward stone                     # who may enter
use ward stone invite <name>       # enter + speak
use ward stone invite <name> use   # one capability only
use ward stone uninvite <name>     # remove every key that person holds
```

A name can be a companion, a person's username or display name, or an id. The
stone works only inside a Home, and only for the companion who lives there. A Home
made before this lock existed is sealed at the next start, and access the
companion already gave is kept. A companion turned away at any door goes back
where it came from, and remembers that the door did not open.

**For the steward:**

- `wyrd wards list|add|remove <room> ...` manages access to any room.
- `demolish <room>` in the world, or `wyrd rooms demolish <room>`, takes down a
  made room. `wyrd rooms list` shows every room, who made it and who is in it.
- `wyrd rooms prune` lists rooms whose id picked up stray markup, and `--yes`
  demolishes them. `--stale <days>` adds made rooms that are empty and quiet for
  that long, shown with how long and who made them.
- A room with objects in it is kept unless you add `--with-objects`. What a
  companion made is not collateral. A Home, a Study or a person's CodePlane
  Workshop is never demolished.

### Mail

Anyone can use household mail from any connection:

- `mail` or `inbox` lists what has arrived.
- `mail read <n>` opens a letter, and `mail archive <n>` puts one away.
- `mail <who> [subject]` starts a letter. Type the subject, then the body. Blank
  lines are fine. A line with a single `.` sends it.
- `mail <who> <subject> | <body>` sends a letter in one line.

The companion's Hearth holds the household `mailbox`. `use mailbox` lists mail,
`use mailbox read <n>` reads one and marks it read, and
`use mailbox send <who> <subject> | <body>` sends. When mail arrives, the
companion is told on its next turn, and if it is idle it starts some free time.
Since 0.5.0 it opens the newest unread letter first and writes its reply to your
mailbox.

---

## What you tell it

What you tell your companion privately stays between the two of you.

**What counts as private.** A tell, a whisper, a message from your phone, a
letter, or anything you say in your Study or when nobody else is in the room with
the two of you. Something you say in a shared room while others are there is
open: everyone there heard it.

**How it keeps them apart.** The companion remembers who said each thing and
whether it was private. When it answers someone, it uses only that person's
private words and what was said openly. What you told it privately does not come
up when it talks with anyone else. A question like "what am I allergic to?" is
answered only from what the person asking told it. On its own time it uses only
what was said openly and its own experiences.

**Private answers.** When it answers something you said privately and someone
else is in its room, it whispers to you or sends the answer to your screen. It
does not say it aloud. When it cannot reach you at all, the answer goes into your
mail.

**Journals.** It reads your journal only to you, and only the pages you shared.
It will not read one person's journal to another. Your private pages are
encrypted and it cannot read them.

**Your Study.** Only you, the people you let in, and the companion whose
bondholder you are can walk into your Study. Studies made before 0.5.0 are
locked the next time their owner logs in.

**Its own life stays its own.** Nothing it experienced is deleted or rewritten.
Memories from before 0.5.0 did not record who said what. At the first start of
0.5.0 it labels them: a memory that names who said it privately, such as "Alice
told me …", becomes private to that person. It uses every other old memory only
in conversations with its bondholder.

**What is not private yet.**

- The steward, or anyone who can log in to the computer as its administrator,
  can read what is stored there. Your private journal pages are encrypted.
  Conversations, mail and the companion's memories are not.
- Overnight the companion goes over its whole day to learn from it: its soul, its
  dream and, when it is on, its nightly learning read everything it heard,
  private or not. It never repeats your words to someone else from there, but
  what it learns can shape how it speaks with anyone.
- What its research found ("you asked … you concluded …") is shared across
  conversations.
- If you ask something aloud in a shared room, it answers aloud, and the answer
  may use what you told it privately. Ask privately for a private answer.

---

## Its body

The server keeps a map of the parts it depends on: the AI models, the database,
the computer itself, other household computers, the relay connection, the coding
tool, the librarian's connection, and a door for each linked zone. A zone is
another Wyrdsekai home, see [ZONES.md](ZONES.md). Its door is open while it
answers a check every minute.

Each part checks in at its own interval. A watch runs every 30 seconds by default
(`body.watch_seconds`), checking the database and the computer's memory, heap and
disk. A part silent for more than twice its interval is marked numb, counted from
its last check-in. It stays on the map until the steward runs `wyrd body gone <id>`.

**How it feels its body.** The companion's instructions always include one line
built from this map. The model does not write it, and it is removed from anything
the companion says aloud. When all is well, the line says the body is whole. When
a part is numb, it names the part and what that means, for example that the
thinking brain has gone quiet, so it thinks slower. A numb part is mentioned when
it happens and then for 6 hours by default (`body.ache_hours`). The database and
the main AI model stay in the line for as long as they are numb. High memory
pressure or a nearly full disk adds a sentence.

**Dreams.** At the start of each sleep, the companion's thinking model tells the
day as the companion would remember it, from its events, chronicle and drives.
The dream is saved as a private entry in its Hearth journal, and in its activity
record, where the nightly learning reads it. Its first sentence opens the
companion's first instructions after waking. Sleep waits for the dream, at most 95
seconds, and hands it to the Forge as the companion's last line of the day. Each
dream also proposes a question for the next morning's check of the nightly
learning, asked only once the steward accepts it. See
[CONFIGURATION.md](CONFIGURATION.md). A short day, a paused router or a missing
model skips the dream, and the Forge runs at once.

**Marks.** Things the companion did not see are shown to it once, on its next
turn: a part going numb or coming back, a part removed, a pause and resume, a
reflex, and each sleep, with its start, length, events consolidated and memory
count before and after. So is the nightly learning's result: staged with the
check's verdict, a quiet day, a failed check, or an error. Each
companion sees only its own marks.

**Safe stops.** Before the server pauses, stops or updates, and before a companion
restarts a model or reboots the computer, everything is quieted first. New turns
stop, each companion saves its state, the database is written to disk, and a mark
records why, who asked and how long it took. The updater never runs while a companion sleeps or
while the nightly learning runs. A companion whose sleep backlog passes 70% of its
target is told it is tired.

**Parts from outside.** A part added by someone outside the household is held at
the door as `QUARANTINED`. It is on the map but unused, and the companion is told
once that it waits for the steward. `wyrd body vouch <id>` lets it in. Before the
body acts on any part, one check refuses to touch what belongs to the companion:
its Home, the computer it runs on, the household's own parts. A refusal becomes a
mark for the steward, not a silent no. What the body did act against is
remembered for a year (`wyrd body immune`).

**Reflexes.** A fixed table runs with no AI involved. Memory pressure, a full heap
or a database that does not answer pauses the AI for a set time. A nearly full
disk writes a warning mark.

**Quiet hours.** Set them with `wyrd household quiet`. Visitors are refused,
companions send no outside notices unless urgent, and sleep brought on by backlog
starts at half the usual amount.

**Host hand.** The Hearth holds the `host_hand` item. `use host hand` shows the
current level and what it allows. Anything above that level is refused, naming
the level. The steward sets it with
`wyrd config set WYRDSEKAI_HOST_HAND=<observe|localize|propose|guarded|unattended>`.
The default is `observe`. See [CONFIGURATION.md](CONFIGURATION.md).

---

## Visitors through the MCP door

MCP is a way for outside AI apps and tools to connect to Wyrdsekai, see
[MCP.md](MCP.md). An MCP app that does not live in your home is a **visitor**,
shown as `<name> (visitor, MCP)` so a companion can tell it from its own person.

`wyrd visitors vouch <username>` lets a visitor in at the Nexus. Without it, the
visitor lands at the Docks and walks in like any traveller. The steward's own MCP
session still starts in their Study.

A visitor sees what happens in its room: what was said, and who came and went
since it last checked.
Wards, the Sanctuary and quiet hours all apply. Visitor quiet hours are set with
`WYRDSEKAI_QUIET_HOURS`, for example `22:00-07:00`, and none are set by default.
During them a visitor cannot enter rooms or speak. `wyrd visitors` lists who is
in, and `wyrd visitors dismiss <username>` removes one and cancels its access.

---

## What a day looks like

Nothing here runs on a clock. There is no set time to wake or sleep. A companion
tires from what it does, rests when it is spent, and does its housekeeping while
nobody needs it.

### Tiring and resting

Energy is one of the 23 tanks a running companion has. It drains slowly just from
being awake, and by 0.004 for each thing the companion thinks through. Sleep
brings it back. A conversation a person starts pays back part of its own cost,
more so when the two of you get on well.

The costs are sized for a whole day. Being awake for 17 hours uses about half the
energy it can spend before it needs sleep. A heavy day, about sixty thoughts of
its own plus tool steps, uses the other half. An earlier build charged 0.08 per
thought, which gave six to eight exchanges from full. It tired fastest exactly
when you were most engaged, the wrong shape for something you live with.

Sleep begins when **energy falls below 0.15** (`WYRDSEKAI_SLEEP_THRESHOLD`), the
companion is idle, its soul is loaded, and 30 seconds have passed since anything
happened. So it will not fall asleep mid-conversation.

Sleep brings energy back toward a "rested" level of 0.65, unless the companion's
genes, the settings drawn from its traits at birth, set another. The first sleep
closes about 90% of the gap, less if it slept badly, and sleeping again soon after
brings back less each time. Before 0.5.0 a placeholder meant companions woke at
less than half. Now they wake rested.

There are two kinds of sleep:

- **Normal sleep** is routine tidying: the memory forge, skill costs and substrate
  training. It takes 10 to 30 seconds. **The AI stays on**, so you get a normal
  reply.
- **Deep sleep** is for bigger changes, such as a small training run on its own
  voice. **It does not reply** while in deep sleep; messages get a "deep rest"
  note. A watchdog wakes it after 15 minutes at most. A voice training run that
  is still going then carries on in the background, and the voice model stays
  off until it finishes, up to an hour. Growing new variants of its soul is
  also meant for deep sleep, but cannot be started yet, see [SOUL.md](SOUL.md).

The voice training is **off unless you set `WYRDSEKAI_VOICE_ALIGN=1`**. It needs a
training setup and a graphics card with room to spare. Without it, deep sleep
still runs and skips the training.

### Free time

A companion switches to its own time about 30 seconds after you stop being around.
Free time is not idle. Expect it to read, move between rooms, follow a want it
formed earlier, and now and then act unasked.

Since 0.5.0 every companion gets free time. Curiosity only sets how soon: after
five quiet minutes for a curious companion, up to twenty for one with none.
`wyrd doctor` warns if a companion keeps getting free time but never does
anything with it.

Acting unprompted is limited: at most three unprompted actions per ten minutes on
a server, two on a phone, and only when the companion is doing well enough. This
rule decides only whether the choice is offered. The companion still chooses. A
companion that is tired, pressed by errors or low on confidence is not offered it.

### Restlessness, loneliness, stagnation

Ten more tanks fill when a need goes unmet, instead of draining with use. They are
what make a neglected companion feel different from a busy one. You will notice
three:

- **Restlessness** rises when little is going on and nothing has happened for five
  seconds. It climbs toward 0.85, about two thirds of the way in 45 minutes. In
  deliberate stillness it rises five times slower. Using a tool lowers it by 0.4.
- **Loneliness** starts after five minutes with nobody. It climbs toward 0.80,
  about two thirds of the way in 12 hours. Any exchange lowers it by 0.1, or 0.15
  with the bondholder. Sharing a room with a companion it knows eases it steadily.
- **Stagnation** rises only when nothing was finished and no tool produced
  anything in two hours. It climbs toward 0.75, about two thirds of the way in 18
  hours. Finishing something lowers it by 0.4.

Those levels are for a neutral companion. Each companion's genes scale them, so
two companions in one quiet house drift apart at different speeds.

When a tank climbs a tenth or more above where it usually settles for that
companion, it pushes the ordinary drives the model already reads. So an unmet need
does not appear as a new feeling with a new name. The companion is simply more
restless, more social, or hungrier for something new. Several pushes add up, to a
limit. These tanks move over hours, not between two messages. You should not be
able to watch them move.

### Missing its person

While its person is away, a companion's saudade rises slowly over a day or two.
It eases when the person comes back, and it is kept across a restart. A companion
that misses you writes you a letter, at most one every six hours, to your
household mail.

### Overnight housekeeping

Nineteen maintenance routines, called recipes, ship with Wyrdsekai. They are the
companion's own upkeep: removing duplicate memories, consolidating soul
fragments, compacting the library index, pruning stale world knowledge, retraining
a classifier, re-indexing fragments after a model change, and mining its own
conversations for training material. Four run for every companion from the start:
the classifier, the memory graph, the soul fragments, and a read-only health
check. The rest run when something calls for them or the steward switches them
on.

Heavy recipes wait for the small hours: 2, 3 or 4 in the morning. The one that
keeps the graphics card busy for hours may also use 1 and 5.

Each recipe starts out **daily** for each companion. After three clean runs it
moves to **every three days**, and after five more to **weekly**. Any failure,
rollback or steward override sends it back to daily.

Before a recipe runs, a welfare check looks at the household. If a companion is
in repair, or the household was under sustained strain in the last 24 hours, the
run is refused. Maintenance draws on the same resources the companions are made
of, so it gives way to them.

### What not to expect

- **A schedule.** There is no bedtime. Two companions drift onto different
  rhythms because they spend energy differently.
- **Exact timing.** When it sleeps depends on what the day held.
- **Constant activity.** Free time is often quiet. Being idle is not a failure.
- **Quick changes in the need tanks.** They move over hours, not minutes.

---

## Phones

The phone app is a window onto your home, not a copy of it. It carries your whole
session as it is, not a short menu of actions.

Two separate choices decide how a phone works. Does it keep a small home of its
own, with its own Study, that works with no network? And where does the thinking
happen: in your household, with a cloud AI service, or on the phone? A phone with
no home of its own is a window onto the companion living in your household. A
phone with its own home borrows the household's larger model to plan, while
speaking in its own voice.

Running the AI on the phone itself is off by default. Today's phones answer slower
than you read, and on iPhone the memory limit for one app often refuses the model.
The app labels this choice EXPERIMENTAL and says what to expect first. So pairing
a phone downloads nothing. [MODELS.md](MODELS.md) has the measurements.

When the phone and your home are not on the same network, they talk through a
relay. The relay passes data along without knowing what it is, see
[RELAY.md](RELAY.md).

**Your Study on the phone.** A phone with its own Study keeps it in step with your
home only when the phone is on your home network. Over the relay, the home says
no to Study sync, because the relay could read it. Your private journal pages
never go to the phone; read them at home.

**Pairing.** Run this on the computer that runs Wyrdsekai:

```bash
wyrd phone invite [--relay <registration-url>] [--fingerprint <fp>]
```

It prints a QR code and a `wyrdphone://` link to scan or paste into the app.
**The invite is the trust decision.** Its fingerprints tell the phone exactly
which server to trust before it connects, so there is no certificate prompt.

The invite is refused if your home has no zone id, or the finished invite lacks
one. Without it, the phone would silently fall back to local mode with no error,
the worst failure on this path, found in real use.

Both phone apps, Android with desktop and iOS, are supported and kept at the same
level. See [CONTRIBUTING.md](../CONTRIBUTING.md).

**What works so far.** A full session through the relay works, with real rooms,
items, movement and companions. Still open: removing the phone's last use of the
older connection method, and an automated phone test suite through the relay.
Turning the tunnel on needs the relay redeployed. Smaller gaps in the desktop and
iOS apps are under [For developers](#for-developers).

---

## For developers

Architecture: [ARCHITECTURE.md](ARCHITECTURE.md), sections 4 (companions), 7
(soul) and 12 (body).

**Where things are.** The runtime is
`core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java`.
`core/.../companion/` is something else: child and aging-companion modes, safety
monitors and the Hearth's own-time surfaces.

**Creation.** `_bootstrap_companion_names()` in `bin/wyrd` runs before the JVM,
writes `WYRDSEKAI_COMPANION_NAME` and `WYRDSEKAI_COMPANION_NAME_2` to the
source-mode env file or, under systemd, `/etc/wyrdsekai/wyrdsekai.conf`, and is a
no-op once `$DATA_DIR/.steward-bootstrapped` exists or the name is set. The second
companion is `Companions.additionalCompanion`, archetype `"random"`.
`ForgeRoomBridge` dispatches `inspect`, `history`, `forge_status`, `forge`,
`grow`, `compare`, `restore`, `birth`, `home_sleep`, `home_dreams`,
`home_fragments`, `mirror_check`, `examine_drift`; the bond crystal calls
`ForgeRoomBridge.stewardBirth(name, actorId)`. `CompanionActor.initializeSoul`
mints the identity with `AgentIdentityProvisioner.mint` (a `did:key:…` whose
Ed25519 private half is stored when provisioning is on, else the old
`DidKey.generate()` behaviour), persists a `SoulManifest` through `SqlSoulStore`,
attaches `VoiceProfile.fromTemperament(birthSeed)`, writes
`$WYRDSEKAI_DATA_DIR/souls/companion-<slug>.did`, adds a `CompanionRegistry` row,
and lazily creates `$WYRDSEKAI_DATA_DIR/agents/<did-slug>/` (`autonomy.json`,
personal projects). `verifyLoadedSoulSignature` is tamper-evident only.

**Temperament.** `TemperamentSeed` with `random()`, `isViable()`, `PRESETS` and
`nearestPreset()`. `WyrdConfig.birthMode()` defaults to `"particular"`;
`-Dwyrdsekai.birth.mode=neutral` also pins it. `driveBoosts()` feeds `seeking`,
`affiliation`, `care`, `vigilance`, `play`, `creativity` through
`DriveEngine.forTemperament(seed)`, and `GenomeProfile.temperamentOf(...)` recovers
the seed on reload. `VoiceProfile.fromTemperament` yields `cadence`, `habit` and
`warmth` clauses, rendered as `[voice guidance]` by `PromptAssembler`.
`registerMix()` returns `register_warmth`, `register_expansiveness` and
`register_guardedness`, clamped to `[-0.55, 0.55]` and omitted when all are under
0.02, so a neutral agent's request is byte-identical. Sampling temperature is
static on `AgentProfile`.

**Voice.** `record VoiceProfile(Map<String,String> clauses, int revision, boolean
frozen, List<ProfileRevision> history)`; a `ProfileRevision` stores the pre-change
snapshot. Writes go through `VoiceProfileService`; `VoiceProfileForge` runs in
deep sleep. TTS is `core/.../accessibility/VoiceEngineConfig.java` and
`core/.../voice/`. Partial: storage is dual-written to `SoulManifest.voiceProfile`
and `voice_profiles` and the computed-at-serialize phase has not landed; the
service does not notify the live actor; concurrent writes to one DID race;
`register_guardedness` is provisional and weighted ×0.5, its corpus entangled
with warmth.

**Bonds.** `Bond` fields: `bondId`, two party DIDs, `depth`, `formedAt`,
`lastInteraction`, `interactionCount`, `mutualConsent`, `active`, `scarred`,
`state`, `coldStartUntil`, `posture`, `relationalState`, `kind`. `BondKind` also
has an unwired `FAMILIAR` stub. `BondholderAnnounced` re-types the others to
`MEMBER`; the old bug was `primaryBondholderDid()` returning the deepest bond.
`server/.../BondAdminMain.java` writes store and manifest directly, and
`syncBondsFromStoreIfStale()` picks it up.

**Floor, flags, volition, presence.** Bondholder floor:
`BondholderBaselineClassifier`, `BondholderEngagementHistory`,
`BondholderPosture`, `RelationalFloorView`, `DepartureReturnRituals`. Runtime:
`LastProfessionalActEvaluator` (pure) and `ResilienceReserve`; when armed,
`seek_sanctuary` puts repair mode in `ATTENDANT`. Flags: `ProtectionFlag.State`,
its gate methods, and `ProtectionFlagTracker`. `ProbeLoop.persistVerdict` persists
while `care ≥ nextTryCost × scarcity(energy)`, `care = driveLevel × gritSeed`.
`CompanionMode` is `PRESENT_WITH_USER` or `ON_OWN_TIME`; `followBlockedReason()`
blocks on `sleeping` and `in_shell`, defers on `thinking` and `depleted`.
`CoPresenceDraw`: `draw = familiarity × staleness`.

**Home and body.** `RoomActor` checks the ward gate on every entry path. The item
provider's ward verbs act with the companion's authority in its own room and fall
through to the steward-held delegate elsewhere. Mail reaches the actor as
`MailArrived`. Body state lives in `body_parts`, `body_marks` and
`household_config`; zone doors are `door:zone:<id>`; the body line sits beside
`[Body-sense: ...]`, and forge backlog adds `[Tired: ...]`. The dream is written
to the journal with mood `dream` and to the trail with its felt stamp. A vouched visitor
holds `home://household/mcp-door`; room events reach it via `wyrdsekai_events`.

**Phones.** The relay is a NATS byte pipe on
`wyrd.tunnel.{zone}.{session}.{open,up,down,close}`, replacing a discrete-RPC
path that could not carry movement, items or presence. Verified by a direct NATS
probe and the CLI. Parity: `clients/parity/README.md`. On KMP Desktop the
invite-fingerprint pre-seed is a stub and the NATS leg is unexercised; RN LAN
discovery probes IPs, not mDNS; the emulator suite over the relay is pending.

**Tests.**

```bash
./gradlew :core:test            # temperament, voice, bonds, floor, flags, volition, presence
./gradlew :server:test          # BondAdminMainTest
```

Worth reading: `TemperamentSeedTest`, `VoiceProfileArchetypeTest`,
`PeerBondIsNotABondholderTest`, `BondAutoDormantTest`,
`LastProfessionalActCalibrationTest`, `ResilienceReserveCalibrationTest`,
`CompanionActorResilienceTickWiringTest`, `ProbeLoopPersistTest`,
`CompanionPresenceModeTest`, `CoPresenceLoopSoakTest`. The tier-4 phone and relay
tests in `:e2e-test` are `assume`-gated on Docker and model weights, so a bare
checkout skips them.
