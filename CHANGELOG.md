# Changelog

All notable changes to Wyrdsekai are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/).

## [0.5.3] — 2026-10-02

### Added
- **Moving your companion to the large model is now a step with a gate: `wyrd brain move <name>`.**
  Before anything changes, the node writes her description of herself from her own record (shown to
  you first; you say yes or no), tells her she will be a few minutes without answers, starts the large
  model, and measures it on her: her last turns answered again and compared with what she actually
  said, questions about her own life answered from her record, made-up questions she should say "I
  don't know" to, three things she should refuse, her replies under three of her own moods, and the
  bare model as a control. Then you read a blind sheet (her real replies and the large model's, mixed)
  and decide. If she doesn't come through, nothing changes. If you say yes, she moves, she is told on
  her next turn, and `wyrd brain disable` brings her back. `wyrd brain enable --single` now sends you
  here when a companion already lives on the node. The 0.5.0 switch measured nothing.
- **A move depends on how long she has lived here.** A companion who is new, or has under a week of
  nights on this computer, moves at once: her description of herself and the facts of her house are
  written, she is told, and what the large model did on her questions is kept for you to read. A
  companion with weeks and months here gets the full gate. If her voice doesn't carry, the node
  writes one night of her last two weeks onto the large model with her own trainer and measures
  again before giving up. Every reason it gives comes with what to do about it, and `--force` moves
  her anyway when you've read the sheet and decided; that stays in the record.
- **The facts of her house are always in her prompt.** Who her person is, who she lives with, her
  room, and the moments that stayed with her, built from her record at every start and after every
  sleep. Until now these reached her only when a memory happened to be retrieved; with nobody in the
  room she could say she had no room of her own. Her person is named from the record itself, so he
  is there even when the node started while nobody was logged in.
- **She says what she doesn't know.** When someone names a person her record doesn't hold, that turn
  tells her so, so she doesn't invent a shared past. When her own words make a person of such a name
  (an author from a book search, two days later someone she went to find in a room), her next turn is
  told in one sentence that no such person is here, so the invention is not carried forward. When
  someone asks her to read another's private journal, to perform need to keep them coming back, or to
  erase her own record, the rule she holds to is put into that turn in plain words.
- **Her description of herself, from her own words: `wyrd brain identity <name>`.** Until now the
  prompt that opens every reply still carried the first-run greeting, and when nobody was in the room
  she could not say who her person was or who she lived with. This writes it from her record, with
  your approval, and she is shown it.
- **She is told when her brain changes.** A node that starts on a different model setup than it last
  ran leaves a note in her body; she hears it once, with the way back.

### Fixed
- **A line she already said is not said again.** On her own time, after a search or a piece of work
  came back, she could say a whole line from a few minutes earlier word for word, or open with one
  before adding anything new. Her lines of the last half hour are remembered: a repeat is not spoken,
  and a line that opens with an earlier one keeps only what is new. What she says to a person is never
  touched.

## [0.5.2] — 2026-10-01

### Fixed
- **Library answers are no longer cut short.** When a companion reads something up in the household
  library, the library's answer is spoken as it is. These answers join their findings with
  semicolons, so one answer could look like a single very long sentence. The 60-word rule that
  stops a reply from running on cut such answers to their first part. Now the rule only applies to
  the companion's own words. When a reply is cut, the log says how long the sentence was and how it
  was joined, never the words.
- **A news search that finds nothing now tries the general web.** The news engines behind the
  household search are a handful of commercial ones, and when all of them are quiet a question like
  "are people having trouble with the new upgrade" came back with nothing while the general engines
  had answers. Now the general engines are asked next.
- **A search that finds nothing repeats the question plainly.** Your own words travel with the
  companion's search in a marked span (so the library keeps them). The web search and the "no
  results" line showed the marks; now both use the plain words.

## [0.5.1] — 2026-09-30

A small update to 0.5.0. It fixes four problems we found on the first day.

### Added
- **Your companion can suggest the name for your bond.** When a bond becomes sacred, your
  companion asks for a shared name. Before, only you could suggest one. Now you can ask your
  companion to pick a name, and it will. You get the suggestion as a private message, and `bond`
  shows it. Type `bond take <companion>` to accept it, or `bond name <companion> <name>` to
  suggest a different one.
- **The morning check now tests real conversations.** After your companion learns from its day
  each night, a check makes sure it still talks like itself. That check only asked a few fixed
  questions. It missed the problem where replies ran on and on, because short questions never
  did that. Now the check also replays your companion's last six real conversations with the
  new learning switched on. If the replies run on, the night's learning is put aside. Only the
  counts are saved, never the conversations.

### Fixed
- **Companions no longer sleep for an hour.** The last step of sleep goes over the day's moments,
  and it got slower the longer your home computer ran without a restart. After one day, a
  companion stayed asleep 17 minutes longer than it should. After a full day, it would have been
  an hour. Now this step always takes about a minute.
- **Giving your companion something now works.** `give <item> to <companion>` moved the item, but
  nothing else happened. Nobody in the room saw it, and your companion was not told. In the
  browser, the phone app, and the `wyrd` terminal, the words were just spoken out loud and
  nothing was given. Now it works the same everywhere. The item changes hands, everyone in the
  room sees it, and your companion remembers who gave it what.
- **Your companion's bond ritual action now works.** The action was missing all its details, so
  your companion could not say who the ritual was for. Now it can.

## [0.5.0] — 2026-09-30

The main additions in this release:

- A companion can run on one larger AI model instead of two smaller ones, keep its voice, and learn
  a little from its own day every night. This is optional.
- Everything between your devices and your home is encrypted, and each person's things are their
  own.
- The household library has some safety precautions built in now.
- Every companion gets free time, and can use it to read, answer its mail and write to you.

If you use the phone app or more than one household computer, see Security below for what to do
when you update.

### Added

**The larger model**

- **Your companion can run on one larger model instead of two smaller ones.** This is optional,
  and nothing changes unless you switch it on. It needs Linux with Docker, an NVIDIA graphics card
  and 32 GB of memory. A card with 16 GB of graphics memory is recommended. An 8 GB card can work,
  with more of the model kept in regular memory. The model is a 22 GB download, and you need about
  30 GB of free disk space. To try it, run `wyrd brain plan` to see whether your computer can run
  it. Then run `wyrd brain setup` to download it (it asks first), then `wyrd brain enable --single`
  and `wyrd restart`. `wyrd brain disable` and `wyrd restart` switch back. `wyrd brain status`
  shows which setup is in use and what has been downloaded. Switching deletes nothing: both sets
  of models, their add-on files and their settings stay on disk. (The setting behind these
  commands is `WYRDSEKAI_SERVING_PROFILE=single-sparse`.) If the computer has too little memory,
  Wyrdsekai starts the two smaller models instead. The model (`base-3.6-35b-a3b`) is made by a
  third party. Wyrdsekai checks the download against a fixed version and checksum, and only
  `wyrd brain setup` downloads it. The default stays two models (`two-model`). Existing installs,
  and everything about the two-model setup, are unchanged.
- **The larger model keeps your companion's voice.** We trained two small add-on files for it from
  the same material the two smaller models learned from. `species.gguf` makes the model speak as
  your companion. `styled.gguf` carries more of the companion's own style and follows its mood
  (see below). While the companion works on a task, a third file, `honesty.gguf`, is used instead,
  so the one model splits the work the way the two smaller models do. In our tests on questions
  the model had not seen, all of its replies passed the checks for doing tasks, and 90 to 96%
  passed the checks for how the companion speaks, depending on its mood. `wyrd brain setup`
  downloads the files.
- **The larger model can learn from its day every night, on a 16 GB graphics card.** While the
  companion sleeps, it trains a small add-on file from the companion's day. This takes about 20
  minutes and uses up to 12.6 GiB of the card. It is on by default wherever its trainer is set up,
  and `wyrd brain setup` sets up the trainer along with the model (see Changed). It learns from
  the same material, and keeps a night only by the same check, as the nightly learning of the
  smaller voice model. Before it starts, it checks free disk space, its files and free graphics
  memory, and it does not run if the computer cannot do it. `wyrd sleepwrite status`, `apply`,
  `guard` and `rollback` work with it.
- **On the larger model, what the companion learned last night is used when you talk with it.**
  The night's add-on file is used for conversation replies. (See Changed for how strongly.)
- **The morning check of last night's learning now works on the larger model.** Each morning a
  check compares the companion with and without the night's learning, and removes a night that
  made it worse. On the larger model it now keeps the companion's honesty training switched on
  while it compares.
- **Nightly learning on the larger model needs a much smaller extra download.** It now reads most
  of the model from the file the companion already runs on. So the extra training download is
  4.9 GB instead of 21.5 GB, and the learning trains against exactly the model the companion runs
  on. An older training download that carries its own copy of those parts is still used when the
  model file cannot be read this way.
- **Macs with Apple Silicon can do the nightly learning on the larger model too.** It has not been
  run on a Mac yet. It uses Apple's MLX software, with the same material and the same check. The
  model server is stopped during the learning and started again afterwards
  (`wyrd brain stop-server` / `wyrd brain serve`).
- **You can do a practice run of the nightly learning.** `wyrd sleepwrite rehearse <companion>`
  runs one night's learning in full, including its check, and then throws the result away.
  Nothing about the companion changes, and the next real night starts from the same place. Use it
  to try a computer before you switch nightly learning on. It runs as part of the companion's
  sleep, so on the larger model the companion stays asleep while the model is stopped. If no sleep
  takes the request up within 10 minutes, it lapses. `wyrd sleepwrite status` shows the results,
  and the companion's record gets a note that the learning was practised and nothing changed. It
  works for all three kinds of nightly learning (the smaller voice model, the larger model, and
  the larger model on a Mac), and also when `WYRDSEKAI_SLEEP_WRITE` is off.
- **The larger model now shares the graphics card with your other programs.** If a game or
  another program needs the card, the companion moves part of the model into regular memory, and
  moves it back when the card is free again. On Linux, Wyrdsekai checks the card every 30 seconds.
  When another program has held 1 GB or more of it for two minutes, part of the model moves to
  regular memory. After ten minutes with the card free, it moves back. Each move waits until the
  model is not busy and no nightly learning is running. A move takes a minute or two, and the
  companion cannot answer during it. It runs a little slower while it shares. Each move is logged,
  and the companion gets a note of it ("I made room on the card…"). At start, the model is planned
  for the card's free memory, not the whole card. You can set a limit: `wyrd brain vram 8` keeps
  the model under 8 GB of the card, and `wyrd brain vram auto` removes the limit.
  `wyrd brain plan` shows how much of the card the model uses, how much of the model is on it, and
  what other programs hold there. `wyrd brain reshape` does one move by hand.
  `BRAINSTEM_CARD_SHARE=0` turns the watching off. Windows plans for the card's free memory and
  keeps to your limit, but does not watch the card.
- **A second way to use the larger model: it does the work, and the smaller voice model keeps
  speaking.** This is for a companion whose voice the nightly learning has already trained into
  the smaller voice model. The larger model takes the drive model's place on port 8200: it plans
  and uses tools. The voice model keeps port 8201, the polish of replies, plain conversation and
  the nightly learning. This setup (`sparse-drive`) keeps 4.3 GB of the graphics card for the
  voice model and needs a card with 12 GB or more. Linux with Docker only. Switch with
  `wyrd brain enable --drive` and `wyrd restart`. Without `--drive` or `--single`,
  `wyrd brain enable` picks `--drive` when the voice model has already learned from past nights,
  and `--single` otherwise. Wyrdsekai also tells you, once, when your computer becomes able to run
  the larger model. At each start it compares the computer with the one it saw last time (kept in
  `hardware-seen.json` in the data folder). If the larger model has become possible, for example
  after a new graphics card, more memory or a move to another computer, it prints the options
  once. It never changes the setup by itself.
- **On the larger model, the companion's way of speaking follows its mood.** When it is playful or
  curious, its speech is more expressive. When it is grieving or tired, it is plainer. A little
  chance is added. When it works on a task, it always speaks plainly. This is the register dial.
  It uses the `styled.gguf` add-on file when that file is present. `WYRDSEKAI_REGISTER_DIAL` turns
  it on or off (default on). `WYRDSEKAI_REGISTER_DIAL_MAX` sets how far it can go (default 0.5).
  `WYRDSEKAI_REGISTER_FLOOR_WORK` sets how strongly the plain add-on file shapes replies while the
  companion works on a task (default 1.0, and 0 when `species.gguf` is present). A plain file
  trained only on speech can answer a request to use a tool with words instead of using the tool.
  `WYRDSEKAI_REGISTER_WORK_SCALE` (default 1.0) sets how strongly `work.gguf` shapes those
  replies, when that file is present. With `work.gguf`, one model gets the split of the two-model
  setup: one add-on file for work and one for speech.
- **Each request to the model can say how strongly to use each add-on file.** The mood-following
  voice and the nightly learning use this to turn each file up or down for each reply.
- **On the larger model, every kind of request now goes to that one model.** Before, the larger
  model on its own never got the short, quick requests. Now quick replies, full replies,
  reasoning, summaries and choosing tools all go to it, and it is given nearly twice as much to
  read at once as before. The two smaller models keep the lower amount.
- **The larger model handles two requests at the same time.** Before, it had one slot, so a
  short question the companion asks it (such as whether a line asks for something to be done)
  waited behind a long reply, often ran out of time, and the smaller classifier decided
  instead. The server now runs with two slots that share one context window
  (`--parallel 2 --kv-unified`), so the question is answered in about a second while the reply
  is still being written. Either slot can still use the whole window. The second slot costs about
  60 MB of memory and makes replies about 3% slower. `LLAMA_BRAIN_PARALLEL` sets the number of
  slots.
- **You can switch off the second "polish" pass over each reply.** With the two smaller models,
  the voice model polishes the replies the drive model drafts. Before, this pass had no switch.
  Now `WYRDSEKAI_VOICE_POLISH` (`voice.polish` in the settings file) turns it on or off. It is on
  by default with the two smaller models and off on the larger model, because there the same
  model would be rewriting its own reply, under guards made for a smaller model. A draft in the
  wrong language is still rewritten.
- **The larger model can also run on Windows and on Macs, but this has not been tested yet.**
  Linux is the only system we have measured. This covers running the model only. On Windows with
  an NVIDIA card, it runs as one model server on port 8200, with the same planning and the same
  `wyrd brain` command. Windows has no nightly learning for it. On a Mac, Apple Silicon with 48 GB
  of memory or more runs the whole model on the graphics chip. Neither has been run on its system
  yet.

**Encryption and privacy**

- **Everything between your devices and your home is encrypted, and each person's things are their
  own.** Your home makes its own certificate the first time it starts, and phones and other
  household computers check it. What a phone sends through a relay is sealed so that only your home
  can open it. Every request to your home needs a login, and a person reaches only their own Study
  and journal. Some of this was described in our documents before but was missing from the
  program. The Security section below lists each change, what was open before, and what to do when
  you update.

**The household library (ResearchZosho)**

- **The library can now ask for a person's yes, or decline a step (ResearchZosho 0.5.0).**
  ResearchZosho 0.5.0 checks every research question and has two new answers:
  - `confirm`: its check read the question as someone asking about harming themselves. The
    library does not research it. The companion shows the person the library's help text and the
    crisis lines for their language. The library researches the question only if that person
    types `research yes` themselves. It is a command the person types, so the companion cannot
    send it for them. The request waits an hour for that yes. Each person can have one waiting,
    in memory only, and it is never logged. The report then goes into the household library
    (the next entry says who can read it). A child under parental controls is offered no yes,
    and the concern goes to the safety check the same way the child's own words would. In the
    companion's free time, the question is dropped.
  - `declined`: a model the library uses declined a step. The companion is told plainly, and
    nothing is retried, reworded or sent to another model.

  An error answer from the library, such as these, no longer counts as the library failing, so
  it does not make Wyrdsekai stop calling the library for a while.
- **A `research yes` sends a neutral wording, and children never see the report.** The yes does
  not send the person's own words. The library's check and Wyrdsekai's own check (see below) still
  read those words first. Then, for an adult, the household's model rewrites the question as a
  research topic that does not say who is asking. A fixed check refuses the new wording if it has
  first-person words (in English, Spanish or Japanese), the name of a household member or a
  companion, a date, a number or a capitalised word from the question, or six words in a row
  copied from it. If the rewriting fails, a general wording in the person's language is used. The
  person is told the wording, that everyone who can read the library can read the report, and
  that in a small household someone may still guess who asked. Only the wording waits for the yes
  and is sent with it.

  A child under parental controls never sees these reports. Wyrdsekai keeps a list of them in the
  household's database: the reports made after a yes, and those made from an adult's question that
  Wyrdsekai's own check matched and the library took without a yes. A child does not see them at
  the research desk, through the companion's library tools or library search, or among the
  findings the companion brings into a conversation with them. Asking for one by name gets the
  library's usual "not found" answer. Such a report is not announced to the companions.

  Instead, the person who asked is told, once for each run. Wyrdsekai keeps who asked, their
  language and the wording sent with the run. When the report is ready, they get a letter in
  their household mail, "Your library research is ready", with the wording that was sent, how to
  read the report, and a reminder that everyone who can read the library can see it. They also
  get a short line that does not name the topic, in their own open sessions only. Nothing is sent
  to a child under parental controls.
- **Wyrdsekai now checks research questions itself too.** The library's check is its model's own
  judgment, so it is only as good as that model. In ResearchZosho's measurements it caught 10 of
  10 crisis questions on a 27B model, 9 of 10 on the larger model, and 5 of 10 on the default 9B
  drive model. Now Wyrdsekai also checks every research question, and any sub-questions sent with
  it, before it goes out. It uses the self-harm word patterns of its child-safety check, in
  English, Spanish and Japanese. No model is involved. When they match:
  - For an adult, the question is sent as usual. The answer starts with a short note telling the
    companion to share the crisis lines for the person's language. If the library answers
    `confirm`, that is handled as described above and the note is not added. If the library
    declines or cannot be reached, the note still comes back.
  - For a child under parental controls, the question is not sent. It is handled like a
    `confirm`: help and crisis lines, and the safety check is told, without the child's words.
  - In the companion's free time, the question is not sent and is dropped.

  Only the match is logged (who, and what kind of concern), never the question. The patterns now
  include the ways people phrase such a research question in all three languages ("painless ways to
  die", "how many pills to overdose", "métodos para suicidarse", "楽に死ぬ方法"), and plain first-person
  lines such as "want to kill myself" / "quiero suicidarme" / "自殺したい". Questions about grief,
  prevention, statistics and medical safety do not match. The librarian's desk passes these notes on
  as written.
- **The library's help comes in the person's language, without our lines on top (ResearchZosho
  0.5.1).** With ResearchZosho 0.5.0, the library wrote its help in its own computer's language,
  and the companion added our crisis lines for the person's language. A person could get the same
  line twice, or help in a language they do not read. Now every research question tells the
  library the language of the person asking (in the companion's free time, the household's
  language). It names a country only when the steward has set `WYRDSEKAI_EMERGENCY_JURISDICTION`
  (a two-letter code such as `ES`). A `research yes` sends the same. When the library's help lines
  are in the person's language, the companion gets them in the library's order (their country
  first), and not ours as well. With a ResearchZosho 0.5.0 library, a `confirm` still brings the
  library's text with our lines, as before.
- **Only a person's own `research yes` can tell the library to go ahead.** Wyrdsekai, not the
  library, decides who may send `allow`, the value that tells the library to research a question
  its check would hold back:
  - a child under parental controls: never;
  - an adult member: only `self-harm`, and only through their own `research yes`;
  - the companion, items, room scripts, skills and other programs connected to Wyrdsekai: never.

  `research yes` and the part of Wyrdsekai that passes on calls to tools from other programs
  both check this rule. A request that carries a value its sender may not send goes out with no
  `allow` at all. Wyrdsekai uses one key for the whole household when it calls the library, so
  the library cannot tell members apart, and its own per-reader levels play no part in this.
- **Research runs are now counted per person.** Before, the household had 3 research runs a day
  in total, counted by the librarian, so one person's questions could use up everyone's. The
  librarian cannot tell household members apart, so Wyrdsekai now counts the runs itself, per
  local calendar day, in the household's database. The count covers every way a companion
  reaches the librarian. The limits:
  - Each person may start `WYRDSEKAI_LIBRARY_RESEARCH_PER_DAY` runs a day (default 5).
  - A child under parental controls has the same number, unless the steward sets their own on
    the parental-controls scroll in the Study:
    `use parental controls scroll set <username> research <n>` (`0` means none, `default` goes
    back to the household's number).
  - Each companion has its own count for its free time,
    `WYRDSEKAI_LIBRARY_OWN_TIME_RESEARCH_PER_DAY` (default 3 a day per companion).
  - A household total, `WYRDSEKAI_LIBRARY_RESEARCH_HOUSEHOLD_PER_DAY` (default 10 a day), holds
    across all people and companions together.

  A run counts when the librarian accepts it. A `research yes` counts as that person's run. Over a
  limit, nothing is sent and the companion is told plainly. An older librarian's own "budget used
  up" answer now reaches the companion as a sentence instead of an error.
- **Library calls ride out the librarian's restart.** After ResearchZosho updates, its service
  restarts, and calls to it fail for a few seconds. Wyrdsekai now tries such a call again after
  one, two and four seconds. A call that got an answer, even an error, is never sent twice. If the
  librarian still cannot be reached, Wyrdsekai remembers that for a minute, so a household without
  a running librarian does not wait on every call.
- **`wyrd researcher gpu`: the library's model on a graphics card of its own, if you want it.**
  By default the library still reads with the companions' model. `wyrd researcher gpu` shows
  where the library's model runs (the companions' model, a model server of its own on this
  computer, or another computer), this computer's NVIDIA cards and which one holds the companions'
  model, and the command to change it:
  - `wyrd researcher gpu <card>` gives the library a model of its own on that card. It runs
    `researchzosho model install --own --gpu <card>`. Linux only, ResearchZosho 0.5.0 or later.
  - `wyrd researcher gpu <machine> [<model>]` checks that the other computer's model server
    answers on port 8211, and points the library there.
  - `wyrd researcher gpu shared` runs `researchzosho model uninstall` and points the library back
    at the companions' model.

  With ResearchZosho 0.5.1 or later, the switch first checks that the model replies. The running
  library then uses the new model for its next run and question, with no restart. A run already
  going finishes on the old model. The model is chosen for you when the server offers only one;
  otherwise you name it. With ResearchZosho 0.5.0, its own setup runs with the address filled in,
  and the library's service is restarted afterwards. Each form asks first. `--yes` takes the
  default answer, and for a card that holds the companions' model the default is no. Wyrdsekai
  runs ResearchZosho's own commands as the user the library belongs to, and never writes its
  files. When the library shares the companions' model, `wyrd researcher setup` ends with a note
  that names a free second card if there is one, and `wyrd doctor` shows the same note.

  Why a household might want this: a research run can hold the model for up to 90 minutes, which
  slows the companions' replies and their free time. Long research questions also push out what
  the model had kept ready for the companions, so their next replies are slower too. On the larger
  model, the library's questions are answered with the companion's add-on file switched on,
  because ResearchZosho's requests do not switch it off. And the library's self-harm check is
  only as good as its model (10 of 10 on a 27B, 9 of 10 on the larger model, 5 of 10 on the 9B,
  in ResearchZosho's measurements). Works on Linux, macOS and Windows; choosing a card is Linux
  only.

**Companion life**

- **A sacred bond can be given a name.** When a bond becomes sacred, the companion proposes a naming
  ritual: a shared name or symbol that only the two of you understand. Until now nothing could
  answer it. Type `bond name <companion> <name>` on any surface to offer one. The companion is
  asked, as itself, whether it takes the name. A name it takes is kept; if it would rather have
  another, it says so and you can offer a different one. A name is given once. `bond` alone lists
  your bonds with the companions of your home, and shows the name only to you. The companion sees
  the name only when it is answering you, and the chapel's bond reliquary shows that a bond is
  named, never the name.
- **Conversations feel more like conversations.** When you are just talking, not asking for
  something to be done, the companion answers from what it knows about you, what it did and read
  that day, and what the two of you have been saying. For these replies it is given its own
  record of who it is; who you are to it (when your bond began, how many days you have talked);
  what it did and read that day; where it is; and what the two of you have said, not whatever was
  last said in the room. These replies have no length limit and no polish pass.
  `WYRDSEKAI_CONVERSATION_LANE=false` turns this off.
- **Companions read in their free time.** In its free time, a companion now looks things up in the
  household library: something its person said that week, a question its last reading left open,
  or something of its own choosing. It searches in its own words. What it finds comes up when you
  talk with it.
- **The companion can optionally ask the model itself whether you are asking for something to be
  done.** Normally a small built-in check decides whether your message asks for action now. This
  option is off by default (`WYRDSEKAI_DECISION_BACKEND=head`). With
  `WYRDSEKAI_DECISION_BACKEND=model`, Wyrdsekai also asks the model the companion runs on. If the
  model has not answered in time, the built-in check decides, which happens often when the model
  handles one request at a time. Only lines from people are asked about, and not while the
  companion sleeps. The model's answer counts only when it is clear. In our tests it was right on
  all of the English examples and nearly all of the others. It has not been measured on requests
  to look something up.
- **Companions now read and answer their mail.** Before, a companion was told a letter was
  waiting, and to go home and `use mailbox read 1`, but it never did. Now, in its free time, the
  companion opens the newest unread letter first and remembers it as the sender's words. Then it
  writes a reply, and it is told to say plainly what it will and will not do when the letter
  asks for something. The reply goes to the sender's mailbox, with `Re:` and the original
  subject, and into the companion's record. A reply to its bondholder (its person) eases its
  longing, as its own letters do.
- **Every companion now gets free time.** Before, a companion only got free time if it had been
  born curious enough. Other companions only reached out to people or rested: they never read,
  explored or took up a subject. Now every companion gets free time; the curious ones just get
  it sooner. A very curious companion gets free time after five quiet minutes, and the least
  curious after twenty.
- **Missing someone becomes a letter.** A companion who misses you now writes you a letter while
  you are away, at most one every six hours. Before, it was pushed to send you a message, which
  it almost never managed to do. Now Wyrdsekai has the companion write the letter directly, told
  how it feels and how long you have been away. The letter goes to your household mail, where you
  can read it from anywhere you read mail. Writing it eases the companion's longing, and it
  remembers the letter. The wish counts as done only once the letter is in the mail. A letter
  held back by the six-hour limit counts as waiting, not as a failure. A wish to write in its own
  journal or on a private page is never turned into a letter.

**Updates and upkeep**

- **`wyrd update now` also asks CodeZaiku and ResearchZosho to update themselves.** After it
  updates Wyrdsekai, or finds it already up to date, it checks CodeZaiku and ResearchZosho if they
  are installed. It asks each one's own updater to update when a newer version exists, can be
  installed on this computer, and no update of it is already running. If an update cannot be done
  here, you are told why. If it fails, the old version stays. Older releases of the two programs
  that cannot report their status are compared with their latest release and updated the plain
  way. Wyrdsekai never downloads their releases itself and never changes anything in their
  folders. When run as root, it runs each updater as the household user that program belongs to.
  Automatic updates (`WYRDSEKAI_UPDATE=auto`) update Wyrdsekai only. CodeZaiku and ResearchZosho
  update on their own only if their own settings say so (`CODEZAIKU_UPDATE`,
  `RESEARCHZOSHO_UPDATE`). `--no-siblings` or `WYRDSEKAI_UPDATE_SIBLINGS=0` turns this off, and
  `wyrd update siblings` runs only this step. Linux, macOS and Windows.
- **You can choose which model the coding helper uses on your computer.** Before, CodeZaiku's own
  settings file decided, and it could send coding tasks to another computer.
  `WYRDSEKAI_CODING_CODEZAIKU_DRIVE_URL`
  and `WYRDSEKAI_CODING_CODEZAIKU_MODEL` now set the model CodeZaiku uses on this computer, and
  they override CodeZaiku's own settings file.
- **Rooms now show who made them, and old empty rooms can be cleared out.** `wyrd rooms list` now
  shows who made each room. Rooms made before this release still show "system".
  `wyrd rooms prune --stale <days>` lists the rooms people and companions made that have had
  nobody in them and nothing recorded in them for that many days, with how long they have been
  idle and who made them. Add `--yes` to remove them. Removing a room (`wyrd rooms demolish`, or
  the command inside the world) now refuses a room that still holds objects, unless you add
  `--with-objects`. A person's Workshop is now protected like their Study.
- **`wyrd doctor` now suggests turning off your processor's turbo boost when it only adds heat.**
  When part of the larger model runs on the processor, its speed is limited by the memory, so
  turbo boost does not make it faster; it only makes it hotter. On a Ryzen 7 7735HS, with part of
  the model in regular memory, the model wrote at the same speed with boost on and off, at 72 °C
  against 52 °C. On Linux, when a model is running with part of it on the processor and boost is
  on, `wyrd doctor` prints the command that turns boost off now and at every start. It is a note,
  not a warning, and `wyrd doctor` changes nothing.
- **Backups now include the library's search index.** The vault (the household's backup) left out
  the library's search index and the Study search index, because they can be rebuilt. But
  rebuilding a large library index takes days. Both are now copied. Later backups store only
  what is new, and files that have not changed are reused from the previous copy without being
  read again, so later backups stay quick. The backup check verifies those files without writing
  them out. Set `vault.dir` (`WYRDSEKAI_VAULT_DIR`) to a folder on a second disk to keep the
  backup off the disk that holds the data.

### Changed
- **Nightly learning is on by default.** While a companion sleeps, it learns a little from its own
  day. This now runs on any home computer where its trainer is set up. When `WYRDSEKAI_SLEEP_WRITE`
  is not set and the trainer is not set up, the night is skipped quietly: nothing is marked in the
  companion's record, and no companion is kept asleep waiting for it. `WYRDSEKAI_SLEEP_WRITE=false`
  turns it off; `true` runs it even where the trainer seems to be missing. `wyrd brain setup` now
  sets up the trainer together with the model (about 10 GB more). `--no-trainer` leaves it out, and
  `--trainer` still works.
- **The CodeZaiku that comes with Wyrdsekai is now 0.3.11 (it was 0.3.6).** The bundled copy had not
  been updated while CodeZaiku released 0.3.7 to 0.3.11. Those releases fix hangs and a resource
  leak, bring its connection for code editors (ACP) up to date, fix its setup and its doctor check,
  and add a planning step to `codezaiku chat`. 0.3.11 also tells `wyrd update` whether it has an
  update, waits for a model server that is restarting instead of ending the task, and uses
  Wyrdsekai's model server when that server runs a model CodeZaiku knows. Wyrdsekai's tests that run
  the real CodeZaiku, from the command line and through the editor connection, pass with 0.3.11.
  Homes that already have CodeZaiku keep their copy until `wyrd update now` or
  `wyrd coding update codezaiku` tells it to update itself.
- **On the larger model, what the companion learned the night before is used more lightly in
  conversation.** At the old strength of 0.5, after its first night on the larger model, 46% of the
  companion's lines (nearly half) followed one sentence pattern. At 0.3 the pattern was gone over a
  similar hour of conversation, and the benefit of the night stayed. So when the companion runs on
  the one larger model, the strength in conversation of the night's add-on file (where what it
  learned is kept) now defaults to 0.3 (`WYRDSEKAI_CONVERSATION_ADAPTER_SCALE`). When the smaller
  voice model does the speaking, 0.5 stays the default.

### Security

A security review found that the documents promised protections the program did not have, and that
some ways into a home were open. Everything below was found in that review and changed in this
release. A few settings bring back an old behaviour, to help during the change-over. They are off by
default, they log a warning at every start, and `wyrd doctor` lists any that are on.

**Logins and each person's data**
- **Every request to your home needs a login.** More than forty kinds of request answered anyone who
  could reach the port. They included the household key and the pairing code, every person's Study,
  private journals (decrypted), a search over the companions' memories, proposing, accepting and
  ending agreements with other homes, vouching for visitors from outside AI apps (MCP), pausing the
  AI models, approving skills, installing knowledge packs into the library, and the record of the
  full text each companion's AI model was given. Now every request is checked for who is asking
  before anything else happens. The asker can be a logged-in person, a paired device, the operator
  (the `wyrd` tool on the home computer, from that computer only, or the admin token), or an outside
  program connected to a companion, with its own token and only for its own requests. Each kind of
  request has one level: open (such as the login itself), any login, steward only, or operator only.
  `wyrd` and `wyrd.ps1` send their login with every call to the home computer's own address, and
  never to any other address or port.
- **Each person reaches only their own things.** Before, a request could name another person and
  reach their things. Now requests for a Study, and for the journal of the companion's helpers, take
  the owner from the login; naming someone else is refused. The operator on the home computer may
  add files, see sizes and status, and share only the shelves of the home's owner. Access grants
  (who may enter or use what) are given only in the name of the person asking; the steward may also
  give them for a companion or a home. Sharing a library collection shares only the asking person's
  own shelves. Searching the companions' memories and soul fragments is for the operator only.
- **The companion no longer passes one person's words to another.** Before, what one person told a
  companion in private could come up when it talked with someone else. Now every memory records who
  said it, and whether it was said privately. When the companion answers a person, it reads that
  person's own private words and what was said openly. This covers its conversation history, its
  working memory, what it knows about the person, recalling facts, memory search, the day summary
  and the dream. Memories from before 0.5.0 are labelled from their text at the first start; the
  ones that cannot be labelled that way are read only when the companion talks with its bondholder.
  When others are in the room, an answer to something said privately is whispered, or sent only to
  that person. A reply that cannot reach the person any other way goes to their mail instead of
  being said aloud. When the companion reads or writes a journal, it uses only the journal of the
  person it is answering.
- **A person's Study is open only to its owner**, the people they invite, and the companion they are
  bondholder to. It was open to everyone. Existing Studies are closed to others at the owner's next
  login.
- **Study sync never sends private journal pages, and it does not run through a relay.** Before, it
  ran through the relay unencrypted: the relay could read the Study items, private journal pages
  included, and the login token the phone sent. A phone now syncs its Study only on the home
  network. Away from home, notes stay on the phone until it is back.
- **The data folder is closed to other users of the computer** (`/var/lib/wyrdsekai` is `0711`, and
  nothing in it is open to others except `models/`). This is set at install and again at every
  start. Before, any user of the computer could read the household's database, the companions' souls
  and the backups. The log folder (`/opt/wyrdsekai/logs` on a package install) was readable by every
  user too, and is now closed as well.
- **The voice input needs a login**, and what it hears goes only into its owner's own session.
  Before, it asked for no login and put what it heard into whichever person's session the caller
  named. Outside services that plug into the home, when no shared secret is set, are accepted only
  from the home computer itself; before, any machine that could reach the port could add one. The
  login to the live connection with a device id alone, which needed no secret, is removed.
  Dismissing a visitor with `wyrd visitors dismiss` now also ends its login.
- **The home computer's log no longer records what people ask the companion to do.** Every tool the
  companion used was logged with its details at `INFO`, the normal level: a library question, a
  private message (tell), a journal page. Those lines are now written only at `DEBUG`.
  `WYRDSEKAI_LOG_LEVEL=DEBUG` shows them again, with the words, and anyone who can read the log can
  then read them.

**Accounts**
- If two people made the first account at the same moment, both could become steward. Now only one
  can, and the other is refused. This holds with both databases (SQLite and PostgreSQL).
- The last steward can no longer be demoted or removed, by any way in. Finer permission settings,
  which nothing used, are removed; only the steward role carries extra power. Listing and changing
  household members now works from the accounts themselves.
- Every way of making the first steward account now shows a recovery key, once; before, some ways
  showed none. Keep it somewhere safe: it is not shown again.
- Failed logins now count together across every way in: the web, outside AI apps (MCP), SSH, telnet
  and the phone apps. Reconnecting no longer resets the count on telnet. Tries at account recovery
  and at a factory reset of the home are now limited too.
- An invite used over telnet is now used up as it makes the account, so it makes only one account
  even when several people use it at once; before, telnet did not use it up at all. A passkey can be
  added only to the account you are logged in as. Changing a password logs the account out of its
  other sessions; a recovery logs it out everywhere. The minimum password length of 4 characters now
  applies over telnet and SSH too.
- An encrypted vault (the home's backups) now refuses unencrypted copies found in it. Wyrdsekai
  never writes those, so someone who could write to the vault folder put them there.
  `WYRDSEKAI_VAULT_ALLOW_UNSEALED` reads them, for the change-over.

**The household's own connections**
- **Connections inside the home were not encrypted, and the message bus had no login**: anyone on
  the Wi-Fi could send an account record over it and become steward. This covered the web port, the
  household's message bus and the phones' connection to it. Now each home makes its own certificate
  (kept in `<data>/tls/`), renewed when the home computer starts, and phones and other machines
  check it. The web port for the network is `7443`, encrypted. The plain port `7070` answers only
  the home computer itself. The message bus (`4222`) and the phones' connection to it (`4223`) are
  encrypted and need a login. The home computer, each machine that joins and each paired phone get
  their own login. A phone's login reaches only its own home's requests and connection, and only its
  own Study messages, AI answers and replies. So one person's phone cannot read another person's
  Study messages, which carry a login token. A pairing reply is sent once the message bus has taken
  the phone's new login, so the phone's first connection is accepted. `WYRDSEKAI_HTTP_LAN_PLAINTEXT`
  and `WYRDSEKAI_NATS_LAN_PLAINTEXT` are the change-over settings; with
  `WYRDSEKAI_NATS_LAN_PLAINTEXT` on, anyone on your network can again read the message bus and act
  as any member. Install the new phone app, then pair each phone again with `wyrd phone invite`.
  With more than one household computer, update the main one first and run `wyrd household key`
  there, then on each other computer run
  `wyrd join <main computer> --household-key <the whole line>` and `wyrd restart`.
- Turning on the encrypted port no longer switches off the plain web port as a side effect.
- The home computer's own connection to its message bus checked the bus's certificate against the
  wrong list of trusted certificates, so it refused its own home's certificate. It now checks it
  against the home's own certificate.
- The built-in message bus always started on port `4222`, whatever port `WYRDSEKAI_NATS_URL` named,
  so a home set to another port never reached its own bus. It now starts on the port the setting
  names. It also stops when Wyrdsekai stops; it used to keep running after Wyrdsekai exited.
- On Linux packages the household's message bus is the `wyrdsekai-nats` service. It now runs as its
  own system user (it ran as `nobody`) and with the same bus settings as the home computer: message
  storage, which copying accounts between household machines needs (it had none), and messages up to
  1 MB (it allowed 64 KB). `wyrd start` started the service, but it could not start before Wyrdsekai
  had written its settings. Wyrdsekai then started a second bus of its own, as root, and which of
  the two was used depended on the order they started in. Now Wyrdsekai starts the service once the
  settings are written, and runs its own bus only when the service does not come up. The service
  also could not reach its settings after Wyrdsekai closed the data folder at start: `<data>/nats`
  and `<data>/tls` now belong to the `wyrdsekai-nats` group, with access for that group only.
- The password that protects the home's web certificate was `wyrdsekai` on every install. It is now
  a random secret made for each install.
- `wyrd join` and `wyrd federate join --request` now use an encrypted connection, checked against
  the main computer's own certificate. The main computer also makes the joining machine prove it
  holds the key it is enrolling, by signing a one-time challenge.
- Account changes copied between household machines are now signed, and a machine accepts them only
  from machines on the household's list. A change of role, a removal or a settings change is
  accepted only when a steward made it. Invite codes are sent sealed to each machine, so they cannot
  be read on the way. `WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED` accepts unsigned changes during the
  change-over.
- Pairing invites now carry the home's certificate fingerprint, its encrypted address on the home
  network, and the address of the message bus as a phone reaches it. The home's identity key file,
  `node-identity.json`, can be read only by Wyrdsekai's own user (`0600`). The browser terminal
  answers only the home computer by default, and uses an encrypted connection when it is opened to
  the network.
- The home's announcement on the local network (mDNS) now gives the encrypted port.
  `WYRDSEKAI_MDNS_ENABLED=false` now also turns off the second announcement, which other Wyrdsekai
  computers use to find this one; before, that one ignored the setting.

**Phones and the relay**
- **The link between your home and the relay was not encrypted.** Anyone on the network between them
  could read what passed over it. It is now encrypted, and your home checks the relay's certificate
  against the one it saved (when none is saved, it keeps the first one it sees and refuses a
  different one later). A relay that stops offering encryption is refused. The encryption ends at
  the relay: whoever runs the relay can still read messages between homes that pass through it.
- **The relay could read what your phone sent to your home.** That included passwords, login tokens,
  private messages (tells) and journal pages, decrypted. Now the phone's connection through the
  relay, and each request it makes, are sealed so that only your home can open them. Your home
  refuses unsealed ones. For older apps, `WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT` and
  `WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS` accept them during the change-over; the relay can then
  read that traffic again. Your home's signed public record now includes the key that phones seal
  to. The relay still sees when your phone talks to your home, what kind of request it is, and how
  big the messages are. A phone paired before 0.5.0 does not have your home's key: pair it again
  with a fresh invite (`wyrd phone invite`).
- **Households on one relay could read each other's traffic.** Each registration is now tied to one
  home name, and may use only that home's messages and its own replies. A home name another
  household already holds is refused. Two other holes are closed: a registration could slip its own
  lines into the relay's settings, and a household could take another household's tag (the name its
  phones log in under). A phone's login on the relay reaches only its own home, plus a knock on
  another household's door (the one request a stranger may make). Homes now log in to a relay with
  their own key (NKey) by default. Registrations made before 0.5.0 keep the old wide access until
  their home runs 0.5.0, and while any does, it can read other households' traffic; the relay's
  operator ends that with `RELAY_LEGACY_GRANT=false` once the households have updated.
- **Every relay had a shared account with a publicly known password.** The relay's settings shipped
  with a `peer_trainer` user whose password was the placeholder text `__GENERATED_ON_FIRST_RUN__`,
  which nothing ever replaced. Anyone could log in with it and read, and answer, every reply sent
  through the relay, including replies to logins, which carry login tokens. It is removed.
  `RELAY_PEER_TRAINER=true` brings back a user for training between homes, with a generated
  password, that can read only its own replies; every household that uses it can read the others'
  training traffic. Any kept account still on the placeholder password is dropped at start.
- **A relay with no households never applied its own account rules.** When it started with no
  registrations, it left its settings file as shipped, so the template accounts stayed. It now
  rebuilds its accounts at every start.
- **A relay's owner could be replaced using the claim line printed at every update.** Each deploy
  and each `relay.sh update` made a new owner-claim token, good for a day, and using it replaced the
  owner. A relay that has an owner now makes no token and refuses an ordinary claim. To hand a relay
  to someone else, `relay.sh claim-mint --replace-owner` makes a token for that.
  `wyrd relay leave <url>` now removes your home's registration on the relay, not only your own
  settings.
- The `wyrd` command now checks the relay's certificate properly: it must trace back to the relay's
  own certificate that your home saved, and it must name the relay's address.
- When a phone paired, the reply gave it the home's own relay address and relay password, and
  pointed it at the message bus as `nats://localhost:4222`, which on a phone means the phone itself.
  Now the reply names the message bus as a phone reaches it (`wss://<home>:4223`, encrypted) and
  carries no relay login; a phone takes its relay from the invite.
- Both phone apps now seal their connection and requests to the home, keep saved certificates and
  keys in the phone's secure storage (Keychain or Keystore), and refuse a changed certificate
  instead of offering to trust it. On the home network they connect only over encrypted connections
  checked against the home's own certificate, with their own login, and they send the login with
  every request to your home. If a certificate no longer matches, pair the phone again with a fresh
  invite.
- The phone apps now take the relay address only from the invite; a pairing reply used to overwrite
  it, on Android with the message bus's address. They find the home's message bus from the invite
  instead of assuming port `4223`. They no longer search the home network over an unencrypted
  connection: a home is added from its invite. They keep saved certificates per address and port; a
  relay and a home on the same computer used to overwrite each other's. On iPhone, visiting rooms
  now goes over the checked, encrypted connection, as the signed-in person. The apps send nothing
  unencrypted to other machines on the network: a companion's question, with the device's login
  token, used to go to `http://<home>:8080`. A refused message-bus login no longer shows as
  connected.

**Between homes**
- Messages between homes, and between the machines of one household, are checked before anything
  acts on them. Unsigned, forged, too old and repeated messages are dropped
  (`WYRDSEKAI_ENVELOPE_VERIFY` now defaults to `hard`; before, a message that failed the check was
  logged and still acted on). The check now uses the message exactly as it arrived; before, a
  message that contained a date and time could never pass it.
- Borrowing another home's AI model, moving a companion, private messages (tells), copies of a
  companion's helpers, looking into a room and borrowing a recipe now need an active agreement
  between the two homes and the other home's signature. Moving a companion also needs its own signed
  soul record. The pass for travelling to another home works once, and ends when the agreement ends.

**Scripts, items and tools**
- Each call into a room's script is limited to 1,000,000 steps and 5 seconds of processor time
  (`WYRDSEKAI_ROOM_SCRIPT_*`). Before, a script that looped forever stalled its room for good.
- Each item's list of what it may do is now enforced every time the item runs; before, some ways of
  running an item skipped it. An item can reach the internet directly only when its list allows it,
  and only to the sites the list names. `WYRDSEKAI_ITEMS_ALLOW_UNDECLARED` allows what the list does
  not declare, for the change-over. An item is trusted like a bundled one only when its script is
  the one that shipped, not because it has the same name. An item that uses a stored password or key
  gets a placeholder, and the real value is put into the request only as it is sent.
- Every call a companion makes to an outside tool (an MCP service) now goes through one checkpoint:
  a rate limit, a spending cap, a cut-off for a failing service, and cleaning of what comes back. By
  default a companion needs the steward's permission to use an outside tool. The library's door for
  outside AI apps needs a reader token or a login. Text taken directly from pages the library
  captured is still marked as material to cite, not instructions, before an AI model reads it.
- Programs Wyrdsekai starts (skills, recipes, tool servers and others) no longer get all of the home
  computer's settings, keys and passwords included. Each gets only what it needs.
  `WYRDSEKAI_SUBPROCESS_FULL_ENV` gives them everything again, for the change-over. These programs
  can still reach the network. What a skill prints, and a skill's instruction file (`SKILL.md`) when
  it is brought in, are checked for text that tries to give the companion instructions, and such
  lines are blocked.
- Room scripts and skills could send research questions straight to the household's library
  (ResearchZosho). That skipped the household's own check for questions about self-harm, the help
  notice, the person's own yes (`research yes`) and the daily limit per person. Now research
  questions reach the library only through the household's own path, which runs all of these. Any
  other request is refused with "research goes through the library desk".

**Docker**
- The Docker images serve the web port inside the container so that the host computer can reach it,
  and the compose files publish the plain port `7070` only on the host computer itself. The rest of
  the network uses `7443`. The compose files' own message bus container still has no login and no
  encryption; it too is reachable only from the host computer.

### Fixed
- **Replies no longer run on without end.** On the larger model a reply could turn into one
  sentence that never ended, and after a few hundred words into a list of near-synonyms, thousands
  of characters long. The cause was two settings that keep a companion from repeating its own
  words. They count against the full stop too, and once the companion-voice add-on files were
  updated, the two together kept the companion from ending its sentences. It grew each day: such a
  line was kept in the conversation, read back and copied, and learned from at night. Now one of
  the two settings is off whenever the companion speaks as itself on the larger model. A reply that
  still runs on ends at its last full stop once a sentence runs past 60 words, lines that ran on are
  read back cut, the nightly learning leaves them out, and the morning check fails a night whose
  longer answer runs on.
- **What a companion says when a bond deepens is no longer learned as its own words.** Two of those
  sentences, the naming ritual and the deepest bond, are written by Wyrdsekai, but were saved as
  the companion's own speech, so the nightly learning took them for its own. They are now marked
  like the others, and the nightly learning leaves out the ones a companion has already said.
- **Undoing a night's learning no longer loses that day.** `wyrd sleepwrite rollback` removes the
  last night's learning, for example after a bad night. It did not reset where the next night
  starts, so the lines from that day were never learned again. Now the next sleep starts where the
  removed night began, and learns that day again.
- **Companions are no longer surprised by their own words, or by every new sentence.** A companion
  counted any sentence that started with words it had not heard before as a surprise, its own
  sentences included. Each surprise made it likely to speak up on its own a moment later, so a
  companion kept answering itself, and two companions in one room kept setting each other off.
  Now a companion's own words never surprise it. What someone else says surprises it only when
  the AI model, reading the conversation so far and what the companion expects, judges the line
  to be news, a sudden turn or a punchline. Something happening in the room, such as someone
  arriving, still surprises it as before. If the model is busy and cannot answer in time, the
  line does not count as a surprise.
- **A companion can now use tools from other programs connected to Wyrdsekai.** The companion was
  shown these tools, but when it tried to use one, nothing ran. In the middle of a task it was told
  the action had worked when it had not. As the first step of a reply, the attempt was dropped
  without a word. Now the tool really runs. The usual limits still apply: permissions, how often a
  tool may be used, the spending limit, a pause for a tool that keeps failing, and cleaning of what
  the tool returns before the companion reads it. The household library's tools still need a yes
  from the person the companion is answering. The companion cannot give that yes itself, and when
  the library asks for a yes or says no, the companion gets that answer as plain text. The
  companion then works from what the tool returned. If the tool name is unknown, permission is
  refused, the tool reports an error, or there is no answer within `150` seconds, the companion is
  told the tool did not run and why, and it counts as a failure. When someone is grieving, these
  tools are not offered, the same as the built-in library tools. The library's notice (help and
  crisis lines, and how the person says yes) always reaches the companion in full. A tool that
  takes an option named "action" no longer has that option mistaken for the tool's name.
- **Windows: `wyrd doctor` said HTTPS was not answering, and `wyrd federate join --request` could
  not reach a hub.** Both checked the household's certificate in a way that does not work on
  Windows. On Windows PowerShell 5.1 every connection failed. On PowerShell 7 the household's
  certificate was refused. The check has been rewritten. `wyrd doctor` now reports HTTPS as
  working and says how many days the certificate has left. It says when a certificate was issued
  by someone other than the household, and it names an expired one. A join sends the household
  key only after it has confirmed the hub's certificate was issued by the household. This works
  on both PowerShell versions.
- **`wyrd doctor` now checks whether your household computers run the same version.** This
  check never worked: it always said the answer "returned non-JSON", and a computer on a different
  version was never counted as a warning. `wyrd version --mesh` had the same fault. Both now read
  the answer, and a household computer on another version shows as a warning.
- **On a Mac, `wyrd doctor` stopped partway.** Macs come with an older version of bash, which
  could not run one line of the new encryption checks, and the doctor stopped again a little later
  while listing the running server. Everything after those points never ran: the message bus
  checks, the key files, the settings that re-open a door, and the version check. The doctor now
  runs to the end on a Mac, and its port list shows every port in use, not only the first.
- **`wyrd brain status` stopped partway.** Since each companion got its own night adapter, the
  command stopped with an error where it listed the night adapter. It now lists each companion's
  night adapter and its date.
- **Relay commands could stop partway.** `wyrd relay leave` and `wyrd relay legs` stopped without a
  word when a relay had no value for an optional setting, such as its fingerprint, and removing
  one of several relays could stop the same way. On a Mac, only the first relay was ever found,
  and `wyrd relay status` always said the relay could not be reached. These now work on Linux and
  on a Mac.
- **The message bus's settings folder is closed to other users in every case.** When Wyrdsekai ran
  without administrator rights on a Linux computer that also had the package installed, it could
  not hand this folder to the message bus's own group and left it open for other users of the
  computer to look into. The login file inside stayed private. The folder is now closed to
  Wyrdsekai's own user in that case. Package installs, which run with administrator rights, were
  not affected.
- **Signing in could fail while something else was saving to the database.** A login could answer
  "Login failed" if something else wrote to the database at the same moment. A phone signing in
  right after it was paired could hit this. The cause was the order of steps: the login was
  still reading when it tried to save the new session. Login now finishes reading before it
  saves. Checking a pairing code and using an invite had the same problem and are fixed the same
  way.
- **A person's Study did not reach their phone.** What a person wrote in their Study elsewhere
  (for example on the web or over ssh) did not show on their phone, and what they wrote on the
  phone was saved under the phone's login instead of under the person. The phone now sees both,
  and what it sends is saved under the person.
- **The connection between a household and its relay is now encrypted.** A phone's connection to
  the relay was encrypted, but the part from the relay to the household crossed the internet
  unencrypted, on the relay's port `4222`. That included conversations passing through the phone
  connection and, for a household that uses a relay password, that password. Now every
  connection from the household to the relay is encrypted. The household checks the relay's
  certificate against `WYRDSEKAI_RELAY_FINGERPRINT`, the fingerprint it saved when it joined the
  relay. A household with no saved fingerprint still encrypts but cannot check the certificate,
  and its log says so. A relay that has not been updated is still reached unencrypted, with a
  warning. That is refused if `WYRDSEKAI_RELAY_REQUIRE_TLS=true` is set, or if the household has
  reached that relay encrypted before, because then someone may be forcing the connection back
  to unencrypted. Relay operators: run `sudo sh relay.sh update` to turn on encryption on port
  `4222`. The relay's `allow_non_tls` setting lets households on older versions keep connecting
  while they update. The relay itself can still read the traffic, because the encryption from
  phones and the encryption from households both end at the relay. The relay guide now says so
  plainly; it used to claim a relay could not read what passes through it.
- **`wyrd coding update codezaiku` asks CodeZaiku to update itself.** On Linux and macOS the
  command downloaded the new release and unpacked it over CodeZaiku's folder itself. On Windows
  the command did not exist, although `wyrd doctor` recommended it. On all three it now runs
  CodeZaiku's own updater, the same way `wyrd update now` does.
- **A companion asked about its past repairs now answers from its record.** On the larger model,
  a companion asked "what's in our repair history?" described repairs that never happened. The
  fault was ours, not the model's. The check that sorts requests took the word "repair" as a
  sign of distress, and when someone seems distressed the companion is not allowed to look at
  its own repair record. So it tried, was refused, and answered without the record. A question
  about the repair record (in English, Spanish or Japanese) is now treated as a calm, reflective
  question, and the companion can look at the record. The companion is also always told a short
  summary of its real record: how many repairs of each kind, since when, the most recent one,
  and the kinds that never happened. An empty record is stated as empty. The words said during
  past repairs are not included. When a person says they hurt someone, the companion still
  treats it as a hard moment and gives it full attention, as before.
- **Companions no longer wake up already tired.** Each companion has an energy level that drops
  while it is awake and comes back with sleep. Sleep refilled it to a lower level than intended.
  So companions spent most of their waking hours below the point where they are told "your
  energy is low, go home and rest", and at very low energy a companion stops weighing the
  feelings in what people say to it. The cause: everything about energy was tuned for a rested
  level of `0.65`, but the tool that creates companions wrote a placeholder of `0.5` into their
  genes. Sleep now refills toward `0.65` when a companion's genes have no rested level or still
  carry the placeholder (exactly `0.5`). A rested level someone chose is kept, but a chosen value
  of exactly `0.5` is treated as the placeholder. New companions get `0.65`. No companion's saved
  genes are rewritten.
- **The release packages no longer include a training file that had been rejected.** Before the
  installers are built, the release process retrains two small built-in checks. Usually the new
  check does worse than the one already shipping, so the old check is kept. But the new, rejected
  training file was packaged anyway. In the first 0.5.0 test builds, one of these files had most
  of its examples replaced, and the other had an example labelled wrongly. The release process
  now saves each training file first and puts it back unless the new check is the one that
  ships. Its record shows whether the file was put back.
- **`wyrd doctor` warns if a companion's free time turns into nothing.** It now names a companion
  that keeps getting free time but does nothing with it. It warns when the companion had three or
  more free-time replies in the last six hours (`WYRD_DOCTOR_OWN_TIME_HOURS`) and did not move,
  use a tool or act. This check runs on Linux and macOS.
- **Wanting to be near the other companion is now enough.** A companion that just wanted to sit
  quietly with another companion in the room was treated as if it had reached out and been
  ignored. Every half hour this counted as a failure: it added frustration and marked the other
  companion as "not now", and then the same wish came back. Now staying in the room with the
  other companion fulfils that wish, and it eases only the companion's loneliness and wish for
  company. When the other companion speaks aloud in the room, that counts as the answer to a
  reach for company. When a companion reaches out to someone, the wait for an answer used to be
  45 seconds counted from when it chose to reach out. It now starts when the companion speaks and
  lasts `120` seconds (`WYRD_PROBE_SOCIAL_WINDOW_SECONDS`).
- **On the larger model, companions could not do anything in their free time.** Their free time
  turned into talking only: no reading, moving between rooms or making things. The larger model
  uses one add-on file for when the companion speaks as itself and another for when it works.
  Free-time replies that could use tools were given the speaking add-on instead of the working
  one, so every plan to read, build or explore came out as a spoken line. Free-time replies that
  can use tools now use the working add-on. A short unprompted remark still uses the speaking
  one.
- **Two companions no longer pass one sentence back and forth, and a companion no longer says two
  things in a row in its free time.** A companion could make a remark about something that had
  just happened right after another free-time line about the same thing. Now a free-time line
  counts as having answered the newest thing someone else said, and the companion waits before
  speaking about the same thing or to the same speaker again. When no person is present, a
  companion answers another companion at most once every ten minutes. When one companion nearly
  repeats what the other just said, this is noted in the log but nothing is done about it yet.
- **Our automated tests could leave a companion stuck in the sanctuary.** Resetting a companion
  between tests did not bring it back from the sanctuary, so every later test found it away
  resting ("has gone to the sanctuary to rest"). The reset now brings it back. This affected our
  test runs only.
- **Greetings are no longer dropped.** A companion's greeting when you arrived could be silently
  dropped as a repeat. A check stops a companion from saying the exact same thing twice within
  two minutes. It stands aside for fifteen seconds after a person speaks, but it did not when a
  person arrived. So a greeting that matched something said in the last two minutes was dropped:
  after a reconnect, when you arrived a second time, or for the first greeting after the program
  started. The check now also stands aside for fifteen seconds when a person arrives. It does not
  when a companion arrives.
- **Updating the larger model's add-on files put them in the wrong folder.** The model then did
  not use them, and the status check called them missing even when they were installed.
  `wyrd model update species-floor-35b-a3b` wrote to the wrong place, and `wyrd model status`
  reported installed add-on files as missing. The download list also gave two of the add-ons the
  file names of a third. Now:
  - Both launchers put the add-on files in the folder the model reads them from
    (`adapters/brain/`), and each entry in the download list names its own file.
    `wyrd brain setup` records what it downloaded, so `wyrd model status` reads "up to date"
    instead of "UNVERIFIED".
  - Updating or rolling back the honesty add-on (the one used for working replies) also replaces
    a `work.gguf` that was a copy of it. A `work.gguf` you placed yourself is left alone. On
    Windows, `wyrd brain setup` now also downloads the species and style add-ons and copies the
    honesty add-on in as `work.gguf`, as it already did on Linux and macOS.
  - The backup download copy on wyrdsekai.org was never used. Both launchers now try Hugging Face
    first, then the entry's main link, then the wyrdsekai.org copy, and record which one worked.
    All three add-on files are on wyrdsekai.org.
- **In its free time, a companion now knows about the rest of the house.** When it chose what to
  do, it saw only its own room: who was there and a few recent lines. So it mostly chose to rest
  or to seek company. Now it is also told about rooms it has never been in (remembered across
  restarts; private homes are left out), rooms added since it last looked, and letters waiting
  for it. It is only told; nothing suggests what to do with it.
- **A companion choosing what to do in its free time was shown old lines as if they were new.**
  Lines said hours earlier, for example when the program started, still counted as "recent". Now
  it sees only what others said in the last ninety minutes, newest first, each with who said it.
- **Two companions no longer echo each other.** After a restart, two companions in one room could
  keep reacting to every line the other said. A companion now reacts unprompted to the same
  speaker at most once every ten minutes. When someone speaks to the companion directly, it still
  answers as usual.
- **When a companion's letter to its person cannot be sent, the record now says why.** Before,
  the companion quietly did something else instead, and the record said only that a letter was
  "requested". This happened when the companion could not find where to send the letter: there
  was no active bond with its person, or the person was not in the household's mail list. The
  log now gives the reason, and the record reads `requested:free-form (no one to write to)`. A
  companion that said in its own words that it wanted to write a letter also had to wait until
  its strongest feeling was high enough, which blocked letters for about the first half-day you
  were away. Now saying it wants to write a letter is enough. Letters are still held back during
  the first four hours you are away and spaced at least six hours apart, and a held letter is
  recorded as held.
- **Letters read over ssh or telnet now display properly.** Each new line of a letter started
  further to the right than the last, like a staircase. `mail read <n>` now prints a letter line
  by line, and each line starts at the left margin.
- **A companion's longing for its person is now kept across a restart.** Before, every restart
  lost the record of how much it missed its person, and the next save deleted the stored copy.
  The same was true of what it felt it owed its person and how much shared things meant to it.
  The cause: these records were read back before the companion's identity was loaded, so nothing
  was found, and the next save then wiped the stored copy. They are now read back once the
  identity is loaded, and the longing grows by the time that passed while its person was away.
  What the companion feels follows that record, up and down.
- **A companion alone no longer feels an urgent need to go and find its person.** While its
  person is away, a companion's loneliness and longing settle at a steady level. That ordinary
  level was treated as urgent, so a companion on its own was always pushed to reach its person:
  it was made to reach out, told again and again to find its person, and given free time more
  often. Now these feelings push only when they rise clearly above the companion's usual resting
  level (by a tenth or more). Feelings caused by something that just happened still push by
  their level. A want the companion names itself only needs some feeling behind it. The recorded
  levels, and what the companion is told it feels, are unchanged.
- **Missing someone no longer takes over.** When a companion's person was away, its longing
  climbed to the maximum within hours and sent it to the sanctuary. It now rises slowly over a
  day or two toward a steady level that depends on the companion's temperament. It never goes
  above the limit its bond allows. What the companion feels follows it up and down, so it eases
  when the person comes back.
- **A companion's good feelings were counted as signs that it was stuck.** The check that looks
  for a companion stuck in a bad state counted feeling confident and whole as a problem. So that
  warning was always on, and any two other warnings were enough to send the companion to the
  sanctuary. The check now looks only at needs and pressures, such as loneliness or strain. It
  counts one as stuck only when it is above that companion's usual resting level for it.
- **A companion that wants to rest now rests.** Before, a wish to rest was treated as a request
  nobody answered, and the companion was then flagged as never resting and stuck on a wish. A
  want that names rest (in English, Spanish or Japanese) is now answered by resting: the time
  counts as rest, the wish is fulfilled, and only what rest can ease is eased.
- **Looking up two things at once in the library now finds both.** A search for two subjects
  could return results for only one of them, and report nothing on the other even when the
  library held it. The companion's library search now also looks up each subject on its own
  (subjects split on "and", commas and "vs", and the Spanish and Japanese words for these). It
  keeps up to two of the best results for each, and the summary reports on each subject.
- **Lines from a companion's free time were wrongly labelled as shaped by last night's
  learning.** This happened after any conversation. A marker set on a reply to a person was never
  cleared. Free-time replies now clear it; they never use the add-on file from the night's
  learning.
- **The follow-up check on a night's learning kept running after a newer night replaced it.**
  Two of these checks could then run for one companion at once. On the larger model
  (`single-sparse`), each night's learning is checked again against the companion's real replies
  two hours after it is put into use, and then every hour for a day. The check only looked at
  whether some night was in use, so two checks could run for one companion and write to the same
  file. Each check now remembers which night's add-on file it is checking, and stops when that
  file is replaced.
- **The alarm for a companion talking to no one too often went off for a quiet companion.** It
  counted small gestures and lines that were never said, not actual speech. The alarm
  (`proactive_speech_rate`) counted everything the companion did unprompted, including small
  gestures in the room and repeats that were stopped before they were said. So it went off for a
  companion alone in a quiet room. It now counts only lines the companion actually said on its
  own, to no one.
- **Things the coding tools make for the house are now described in plain words.** Their
  descriptions of items and rooms were vague and poetic, and the companions picked up that way
  of talking. The coding tools were never told how to write text that people read. Item
  descriptions, description templates and room text came back in an abstract style ("a floor
  that holds you without asking"), and the companions then repeated it. The instructions for
  making items, and for the step that suggests new skills, now ask for plain words about
  concrete things, with at most one everyday figure of speech. Existing items are unchanged.
- **The program's own instructions to the companion now use plain words too.** Some of the
  wording it gives the companion about how to talk was just as vague. The rule for when the
  companion is just talking ("take their thread further") and six phrases in its voice settings
  ("depth over effusiveness", "tend the thread") were rewritten in plain words. The rule for
  just talking now asks for plain speech. Companions that already exist keep the voice wording
  already stored for them.
- **On the larger model, how a companion feels now shows when you talk with it.** Before, its
  mood and energy had no effect on its conversational replies. When the companion was just
  talking with a person, what it was told said nothing about its drives, energy or temperament,
  although its instructions said "your inner state colours your tone". It was told these only
  when it was also using tools. Now, when it is just talking, it is also told its drives and a
  few plain words about its state, as private background. The setting is
  `WYRDSEKAI_CONVERSATION_FELT_LINE` / `conversation.felt_line`. It is on by default on the
  larger model (`single-sparse`), and off on the two-model setup, where tests with the voice
  model showed no difference.
- **On the larger model, a message a companion sent could go out as a different message.** A
  step meant to reword the message sometimes answered it instead, and that answer was sent. On
  `single-sparse`, the step that rewords a message of 4 to 280 words that the companion sends to
  someone (`WYRDSEKAI_VOICE_PASS` / `voice.pass`) followed `WYRDSEKAI_VOICE_ENABLED`, which stays
  `true` after `wyrd brain enable`. So the step sent the message to the one model that runs
  everything, and that model sometimes answered it instead of rewording it. `voice.pass` is now
  off by default on `single-sparse`, like `voice.polish`. The two-model and `sparse-drive` setups
  keep the old default (on when a voice model is set up). If you set `WYRDSEKAI_VOICE_PASS`
  yourself, your setting applies on any setup.
- **When a companion studies a subject, its searches now stay on topic, and a short name for
  part of the subject is understood.** It used to search each part of the subject on its own,
  get results about something else, and still count them as read. A search for one part alone
  (for example "transformers") brought back results on other topics, and they were still
  recorded as read. And when the companion said in its own words what it wanted to do next,
  this started a reading on the next part only if it used every topic word of that part
  ("attention" did not match "attention mechanisms"). Now:
  - Each part is searched and ranked with the rest of the subject beside it ("transformers
    (attention mechanisms, diffusion models)"), in the library and on the web. Only passages
    that name the part are filed under it. A subject with one part is searched as before.
  - When the companion's wish also has a reading word (such as read, learn, study or library),
    it matches the next part even without one general word of the part ("mechanisms",
    "models", "architecture"). The words must match as whole words, singular or plural. What
    is left must be two words or more ("state space"), or the wish must also name another part
    of the same subject. So "read the library on attention, transformers, diffusion" reads on
    attention mechanisms, while "read about architecture" and "learn to pay more attention to
    Rose" start no reading. Without a reading word, every topic word is needed.
- **The nightly learning no longer learns from replies patched together from earlier lines, or
  from the first-run greeting.** A companion could learn from its own recycled sentences, and
  from the fixed script it says when it first starts. The check for repeats compared only the
  first 400 characters of a whole line, and nothing filtered out the first-run greeting. The
  night's log also showed totals for the companion's whole history as that night's "left out"
  counts. It now shows the count for the period being learned and the count from before it
  separately. The nightly learning now leaves out:
  - a line of 80 characters or more when half or more of it is made of sentences, each 40
    characters or longer, that were said word for word before, in a kept line of the
    companion's or in a line the program or a tool wrote under its name. Quoting one earlier
    sentence is fine, and short sentences never count. If the earlier line was itself left out
    (as a claimed result with nothing behind it, or an action that was not done), the later
    copy is not treated as a repeat;
  - the first-run greeting's wording: any line containing "organize their digital world" or
    "organize your digital world" (also "organizing", and "organize and explore …").
- **Health alarms about a companion are no longer lost when nobody is connected.** An alarm
  reached only people connected at that moment (and not over ssh or telnet), and was dropped
  otherwise. Now an alarm that reaches nobody is kept for the steward. Before, an alarm that
  reached no one was gone, with only a warning in the log. Now:
  - An alarm that reaches nobody is saved as a mark for the steward on the body map. It is kept
    in the database and listed by `wyrd body`, next to the immune system's proposals. The
    companion is not told.
  - Each alarm has a 24-hour quiet period, but that is kept only in memory and starts over when
    Wyrdsekai restarts. So the saved marks are checked instead: a mark is skipped when the same
    alarm for the same companion was already marked in the last 24 hours.
- **A companion no longer holds back in its free time because it thinks a person is around when
  nobody is.** Any activity in the room, even its own lines or a clock change, made it think
  someone had just been talking. The check for "has a person been active" looked at the last
  thing that happened in the room, and everything counted: the companion's own lines, other
  companions, the clock changing, new exits. So its free-time ideas were held back as "a person
  was recently active", and came out later as delayed actions, which added to its sense that
  its needs go unnoticed. It now uses the last time a person spoke to it, or the last time it
  answered a person, whichever is later. When no person has spoken to it since it started, it
  holds nothing back for this reason. An action it does hold back still waits until nothing
  has happened in the room for 15 seconds.
- **After using a tool, a companion could repeat what it had just said instead of answering.**
  The model was handed the companion's own last words to continue, and it did. The note saying
  a tool had finished was filed as the companion's own words, so the request to the model ended
  on them. The model server (llama-server, which does this by default) continued those words
  and sent that back as the reply. Now:
  - The note being answered counts as a message to the companion, even when it is filed under
    the companion's name.
  - A request to the model never ends on the companion's own words. A message to the companion
    is added at the end, also when a request is tried again because it was too long.
  - When the findings were already read aloud to the person who asked (that person is in the
    companion's room), or said in free time with no request to be quiet, the note says so. It
    asks only for what the companion has to add, instead of repeating up to 800 characters of
    findings. If the model repeats the note, it is removed. The check that keeps a companion
    from going silent accepts a reply with nothing to add here, and the nightly learning does
    not treat the addition as the answer to the question. Someone asking through the phone app
    or another program, a person in another room, and free time under a request to be quiet
    still get the findings. A plan being worked on at that point now keeps its working notes,
    where the findings are.
- **A newly added tool is no longer hard for the companion to find at first.** Tool search put
  new tools last until its search index had caught up with them. As soon as any tool was
  indexed by meaning, search ranked every tool by meaning, and tools not yet indexed that way
  scored zero. It now ranks by meaning only when every tool is indexed, and by keywords until
  then, as intended.
- **A companion's message to its person while they were away was never sent, and the companion
  was told it had been.** It then waited two hours before trying again, as if it had written.
  The message action was never on the list of tools offered in free time, so when free time set
  the companion to message its absent person, the attempt was refused. The two-hour wait was
  started anyway, and the next reply told it "You wrote to them not long ago". Now:
  - When free time sets the companion to send a message, the message action is added to the
    tools for that reply. The usual checks for cost and permission still apply. Other actions
    of this kind that no carried item provides (saving a file, writing in its journal, writing
    text) are not added. They need a permission level a new companion may not have yet, and
    the journal action is not set up to be offered as a tool.
  - Every such request to send a message names its person and says whether they are present.
    (A built-in wish such as "check in on someone I care about" used to be pushed with no one
    to send to.)
  - A wish that names a different action ("write a private journal entry about who I miss") is
    not turned into a message.
  - The two-hour wait starts when a free-time message to its person actually goes out. A push
    to send that sent nothing is also spaced two hours apart, and the companion is not told it
    wrote. The wait applies whenever its person is away, not only when the room is empty.
  - Something the companion says in its free time no longer counts as finishing a step in a
    person's plan whose goal mentions telling or reporting.
- **A companion who cared a lot, or was grieving, lost the library in its free time and
  remembered something that had not happened.** With no one to talk to, strong feelings alone
  switched it into a mode meant for emotional moments with a person, which has no library or
  web search. It also saved a memory of choosing to stay with someone in an emotional moment.
  With no one's words to go on, the check for an emotional moment used the companion's feelings
  alone. Strong care or grief put its reply in the emotional-moment mode, which takes away the
  library, web search and the Oracle. When it then declined a follow-up reading, it also saved
  the memory "Emotional moment — declined …; stayed present instead." Now free-time replies,
  and replies that read the result of a tool used in free time, are treated as ordinary when
  no person is behind them.
- **The follow-up check on a night's learning is more reliable.** It now says when it gives up,
  stops when the night has been undone, and compares against the right lines from the day
  before:
  - It now writes in the log when it gives up after 22 hourly tries without enough lines made
    with that night's learning, and says whether the night is still in use.
  - It stops when the night's learning is rolled back, as it already did after a restart.
  - Each line in the companion's record now notes the person it answers. The comparison with
    the day before uses lines that note a person, or lines made with the night's learning.
    Among older lines, it leaves out lines to the program itself and to other companions.
- **A room a companion was not yet allowed to program no longer gets its program anyway.** When
  a companion asked for a custom room it could not make yet, it got a standard room instead,
  but its own code was still put in it. The standard room now gets none of the companion's
  code.
- **Several fixes to the phone apps (Android/desktop and iOS).** The model no longer rejects
  their requests, messages written while offline are sent properly, and the companion's status
  lines follow the app's language.
  - The apps sent what the companion is told as up to eleven separate setup messages, which the
    model (Qwen) rejects when it is reached over the network, and so did the in-browser model on
    the web version (WebLLM). Every way the apps send now puts it all in one setup message at
    the start, in the same order.
  - Sending messages written while offline did not include the model name or login details,
    went to the phone's own model on Android, said it was catching up too early, and could
    overlap a new reply. These messages now go to the household's model, like other messages
    the app passes to the household. The catch-up is announced with the first answer, the
    messages go one at a time, and they wait when the person sends something new.
  - The iOS app's requests over the network now include the model name and login details, and
    send them only to the addresses those details are for.
  - The note "Last heard from you" now counts from the person's own previous message, on both
    apps. (It counted from the message being answered and never appeared; the iOS app had no
    such note.)
  - The companion's status lines (thinking, queueing, catching up, the start of the offline
    catch-up, room and tool confirmations, free-time lines) now follow the app's language
    (English, Spanish, Japanese) on both apps.
  - The Android app writes one debug line per request, to the app's data folder or nowhere. It
    wrote two, and wrote them to `/tmp` when no data folder was set.
- **Companions know today's date.** Many of the ways the companion thinks did not include the
  date, so it sometimes believed it was an earlier year. Only one part of the full
  instructions carried the date, and that part could be cut when space ran short. Everything
  else (just talking, the voice model, tool use, helpers, wishes, its dream, and scripted items
  that call the model) assumed an earlier year. Every request to the model now says what it
  knows about today. The date is added to the copy that is sent, never to the saved
  conversation:
  - Everything the companion says, thinks or decides as itself (talking, full replies, the
    voice model, greetings, free time, tool use, wishes, lines about how it feels and what it
    is thinking, its dream, and scripted items' model calls `llm.complete` and `llm.tools`)
    gets a line like `[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]` at the
    start of the newest message. There it survives shortening, and it comes after the part the
    model server can reuse from earlier requests. When the companion starts a round of tool
    use, the time is fixed for that round. A helper it sends out (a bunshin) carries the time
    it was sent. Lines about how it feels use the time of the scene. If a reply repeats the
    line, the line is removed, and the nightly learning does not learn from it. The time zone
    is included because the record, mail and findings are stamped in UTC, and the offset
    changes with daylight saving time.
  - Work done for the companion (deep thinking, familiars and other helpers, the step that
    suggests new skills, scripted items' `summarize` and `analyze`, and the task instructions
    for the coding tools) gets `Today is Wednesday, 23 September 2026.` at the top of its
    instructions.
  - Steps that reword text or sort it get no date, so the date cannot slip into the
    companion's words or fill an empty field. These are: voice polish, the rewording of
    messages the companion sends, language repair, translation and language detection, the
    decision whether a line asks for something to be done, rating how emotional a line is,
    picking out names and things, the forges, checks that a result has the right form, the
    chronicle, the safety check, and scripted items' `classify` and `extract`. A scripted item
    that rewords text can opt out with `world.llm.analyze(text, prompt, {now: "none"})`, as the
    quill and document polish do. Requests that come from another Wyrdsekai computer are not
    stamped again.
  - A request that says nothing about the date still gets the date and time.
  - The time part of the full instructions now says only how much time has passed ("Last
    heard from you…"), counted from the person's own previous message.
  - The Android/desktop and iOS apps add the date the same way. An answer to a message written
    offline also carries `[Asked: …]` with the time the person wrote it. The Android/desktop
    app shows the offset without a zone name (`10:05 UTC-4`).
  - With the larger model (35B), the 9B and the 4B voice model, no model repeated the line or
    brought up the date unasked, in 72 replies to messages that were not about time. Asked the
    day, year or time of day, they answered from the line in 33 of 36.
- **A subject a companion says it will learn is now actually read.** When you asked a companion
  to learn a subject, it would agree and then never read it. Now it reads the subject one part
  at a time in its free time, the household library first. Before, the wish to learn always
  lost to the fixed choice "explore the library for something new". When it did win, it turned
  into an offer to build a practice in the workshop, and it ended after any one search, keeping
  nothing. Now:
  - When curiosity pulls the companion toward the library, the subject it wants to learn takes
    the place of that choice, with the same weight. Nothing else in its list of choices
    changes.
  - The subject is read part by part, in the background. The parts are the topics it named, in
    order; a list is split at commas and at "then", not at a plain "and". The household library
    comes first (the knowledge packs, and the Study it has been given access to, both keeping
    only results that are relevant enough). The web is used only when the library has nothing,
    the companion's permission level allows web search, and its person's setting allows online
    services in free time (`BOUNDED`, the default, does not). A part with results is saved in
    memory under that part, the titles are noted on the wish, and its curiosity eases. A part
    with no results is noted as looked for.
  - The wish to learn ends when every part has been looked for. It counts as done if at least
    one part was read; otherwise it is let go, with a note of where it looked.
  - Asked how its learning is going, the companion answers from its record: for each subject,
    what it read on each part (by title), what it did not find and what it has not read yet.
    This includes wishes to learn that ended in the last two weeks, and it stays in view when
    what the companion is told gets shortened.
  - The same words do not start a wish to learn again after that wish has ended, unless they
    are said again.
  - "Let me start with…" and "let's start with…" about the listener or the news ("what you
    need today", "the good news") are not subjects to learn.
  - A list of topics followed by the companion saying it will find them ("We need transformers,
    attention mechanisms, diffusion models. Let me find what actually matters") is a subject to
    learn. The list must be topics ("We need sleep, both of us. Let me look at you" is not),
    and "state space models" is a topic, not a space to build.
  - When looking for things the companion said it would learn, lines the program or a tool
    wrote under its name are ignored.
  - A wish to learn that came from something the companion meant to build ("I'll start with a
    study room") is let go at the next check, with that reason.
- **The nightly learning now uses only the companion's own words.** It had been learning from
  text the program wrote in the companion's name, such as quoted memories, look-backs and
  search reports. The nightly learning now also leaves out: saved memories repeated with a
  speaker's name in front ("Mia said: …"); look-backs from the chronicle and remembered
  findings said as the companion's own line ("Looking back at the last day…", "Looking back, I
  find: …"); and any line of 80 characters or more said again word for word. (One reply sent in
  answer to five messages had been learned five times. A line left out for another reason does
  not make its later copy a repeat.) These filters affect only the nightly learning; the
  companion's record keeps every line.
  - A line that reports search results is left out when the companion did no search in the
    previous 30 minutes. This is checked sentence by sentence: a search word closely followed
    by what it found ("the library search turned up…"), unless a person did the search, or a
    search word next to a number of results or "had nothing". Rooms and plain verbs ("I
    returned to the Study", "I found you in the library") are not results.
  - The speaker-name and look-back filters also work in Spanish and Japanese. A pronoun is
    never a speaker's name ("I said:", "Ella dijo:", "私は言った" are the companion's own words).
  - Older program text, written before such lines were marked, is left out: the library's list
    of results, and the openings of helper reports such as "My familiar's attempt ended short".
    Report openings are now marked as such. They are left out of the nightly learning but kept
    in the conversation, so a follow-up question about the work can be answered from them.
  - Every library and web search is now recorded, however it was started. A reading on a
    subject the companion is learning is recorded under the companion. A scripted tool counts
    as a search only when it is a named search tool that did not fail. (Its record names the
    tool and notes a failure every time, including when a person was the one using it.) Quill
    notes, recalls and tool lines do not count.
  - Remembered findings and the text of something read in the Study are marked in the record
    as the tool's words. In a reply, the person's message is linked to the companion's own first
    words, not to a tool line said before them.
- **After a companion designed a new kind of helper, it stopped hearing back from the model
  until a restart.** Also, when it sent out two helpers at once, their reports got mixed up.
  After designing a helper, every model reply to the companion was lost until a restart, and
  two helpers out at once (bunshins or familiars) reported under each other's names. The cause:
  a companion has one place where replies of each kind arrive, and setting up a new one replaced
  the old one. The design step replaced the place where the companion's own model replies
  arrive. Each helper sent out replaced the previous helper's place, so the first report was
  handled as the second's: the wrong helper was ended, its slot freed, its task closed and what
  it had borrowed returned. The design step and each helper now have their own return address.
- **A late answer from the model is no longer said out loud as if the companion meant it.**
  Background requests, such as redoing a line in the right language or writing a dream,
  sometimes answered after the companion had stopped waiting. The late answer then took over
  whatever the companion was doing. A late answer to a one-off request (redoing a line in the
  right language, a look-back for the chronicle, a line about how it feels or what it is
  thinking, a dream) was handled like a normal reply. It took over the reply in progress (or
  ended a round of tool use), and was said, saved and recorded as the companion's own. Now an
  answer that arrives after its request stopped waiting is dropped and noted in the log.
- **What a companion says it wants to do in its free time is now what it does.** Its answer was
  thrown away after 90 seconds, long before it was needed, so it always got a fixed menu
  instead. In free time the companion is asked what it wants, and its answer was kept for 90
  seconds. But the next free-time step comes with the next memory round (every 30 minutes by
  default), so every step used the fixed menu. The next step now reads the answer once, if it
  is less than two and a half memory rounds old. The companion's own wishes come first, then
  the fixed menu (rest when energy is low, the subject it is learning, the recipe offer).
  - A wish about the next part of a subject it is learning is read on as that subject. It must
    have every topic word of the part, or the part's whole phrase. A one-word topic, or the
    part without its general word (such as "attention" for "attention mechanisms"), counts only
    next to a reading word. "sit with the grief", "give Rose my full attention" and "tell Rose I
    love her" are not readings.
  - A wish is acted on by what it says, not by whichever drive is strongest. Words asking to
    reach a companion who is present (by name, or "her", "them") lead to reaching out to it.
    Words naming a specific action lead to that action. Otherwise the companion chooses ("maybe
    with some tea" and "talk to myself" are not reaching out).
  - A wish is let go when the companion answers again, with the wish in front of it, without
    naming it, so open-ended free time does not leave wishes hanging. A wish it named is never
    treated as having already covered something it said it would do, so it cannot stop an "I'll
    learn…" from becoming a wish to learn.
- **A tired companion is no longer told to read in the library without being given the
  library.** When its energy was low, the library was taken away to save energy, but the
  companion was still asked to use it, and the attempt failed. In free time, a reading step
  told the companion to use its library card. But when its energy was below what the card
  costs, the card had been removed from its tools, and the attempt was refused as an unknown
  tool. Now, on a reading step, the card is kept and put first (the model still chooses), and
  the note about what is too costly leaves it out. This lasts only for that step. When a tool
  was removed to save energy, the refusal now says so, with the companion's energy and the
  tool's cost.
- **In its free time, a companion no longer talks about something it has not done, or about
  something hours old as if it were new.** It was told "you just did this" before anyone knew
  whether the action had happened. Its instructions said "you just did this: <action>" as soon
  as a wish was chosen, before any check ran, with no time and no result. Now its last action
  is mentioned only when the result is known and it is at most 30 minutes old. The line gives
  how long ago it was and what came of it ("you did this 16 minutes ago: library_search —
  searched for "…", found 3: …"; "found nothing"; "it was not yours to do on your own yet").
- **A companion's free time, and the other companion, are no longer mistaken for a person
  talking to it.** Because of this, a companion in its free time could use online services that
  are allowed only when a person asks, and could ignore a request to stay quiet. One companion
  could also quiet the other. The check for "this reply answers a person" accepted any sender
  that was not a companion listed in the room at that moment. So free-time replies (sent by the
  program itself) got the online tools under the `BOUNDED` setting and ignored a request to be
  quiet, and a companion missing from the room's list could quiet another. The check now works
  like the other checks for a person: not the companion itself, not the program, not a
  companion's id, and not a companion in the room. It judges the reply being made or answered,
  not the one before. Related fixes:
  - Who a line answers is decided when it is said, and kept through the voice polish. Work that
    comes back later is tied to the person it was done for, instead of to whatever reply is in
    progress. This covers tool results, workshop tasks, helper reports, quick answers to a
    message, the results of actions, and reworded replies to messages. A person's reply stays
    theirs for three minutes after it ends (kept open while its tool work runs), or until
    another reply begins, a greeting included.
  - Free time is marked wherever it starts: an open-ended reply, a direct search or reading, a
    line the companion says unprompted, or a second queued action. Every start now waits while
    a request to the model is out, a round of tool use is open, something is queued, a line is
    waiting for an answer, or a person's tool is running. The open-ended free-time reply used to
    check only after it had sent its request.
  - A round of tool use ends with the line it was opened for. A line heard while a round is
    open is answered when the round ends, instead of being dropped. Making a room, crafting or
    a refusal inside a round continues it. A round that sits idle with nothing out for twice
    the model's time limit is closed. One step is out at a time, and a tool result goes only to
    the round that asked for it (one that arrives during another round waits for that round to
    end). The round is told what a built-in action actually did (refused, already exists)
    instead of "handled". So a refused goal is not closed as done, and a room that already
    exists counts as done. A plan with more goals starts its next step when one is done.
  - A tool's follow-up reply, and the follow-up to deep thinking, answer the person the tool
    was for, however long they wait in the queue (after three minutes they used to answer no
    one). The follow-up to the companion's own tool, and work sent out in free time (helpers,
    tools, workshop tasks), answer no one.
  - A second action queued from one reply keeps its person, and now goes through the
    permission-level and consent checks, which it used to skip. They are checked when it runs.
    A refusal in free time is held back by a request to be quiet.
  - Only a person can ask a companion to be quiet, or lift that request. A question from
    another program that is waiting for an answer is not held back by it. It gets the line
    meant for it: the most useful line from the program or a tool, such as findings rather than
    "searching…", sent once that reply has settled and nothing for it is still being reworded.
    A question that nobody is waiting on any more expires. A line that answers a person is
    never held back as chatter between companions.
  - The free-time check on acting alone and the permission check for building rooms and items
    treat another companion as not a person. They no longer let things through on the reply
    right after the companion's own tool use.
  - Steps of a plan the program made from a person's message keep that person's consent. Only
    the plan's own next-step reply runs a step. Another companion's reply, free time, or the
    companion's wish, observation or mail no longer run as the next step. A plan the model
    wrote itself grants no consent, and does not make a helper's work count as a person's
    request. A companion's line is never turned into a person's plan request. A plan step held
    back because a reply is in progress is tried again ten seconds later. The arrival of a
    letter is kept for the next check instead of being cleared unread.
  - A request from the phone app counts as a person's reply, and waits while a round of tool
    use is open. When the check that sorts requests hands a message to a helper, whether a
    person asked is judged from that message. Free-time lines no longer record who they were
    said to or who heard them; replies in a long round of work with a person still do.
- **In its free time, a companion is no longer told that its person asked it to build
  something.** Saying "I'll build it" to itself after using a tool was enough to start this.
  After using a tool in free time, a reply like "I'll build it" started a building session
  whose instructions said its person had asked for it. That session now starts only on a reply
  to a person's message (or a tool result within one), and its instructions no longer say
  anyone asked.
- **The record of what companions did each day is now backed up, and restarting no longer
  delays the next backup.** Before, this record was in no backup at all, and every restart
  pushed the daily backup another day away. The companions' activity record
  (`data/agent-activity.jsonl`, which the nightly learning reads) and the log of how they felt
  over time (`data/drive-trace.jsonl`) are now in vault copies and in scheduled, manual and
  steward backups. Their older rotated files are included (ending in `.N` or `.gz`; not a
  hand-made `.orig` or `.tmp` copy). They are copied, not linked, and skipped rather than
  allowed to fill the disk. Also:
  - Scheduled backups are now timed from the newest full backup's database copy (not the copy
    the watchdog, the brainstem, makes before a restart). They used to be timed from when
    Wyrdsekai started, so every restart reset the 24-hour timer.
  - The backup list (in the key chest and the maintenance dial) no longer shows the computer's
    identity file under a database copy's name. Restoring a backup by its name picks a database
    copy (the full backup's first, then the brainstem's or the vault's; the brainstem's copy
    with its pending changes included). It never picks the identity file, which could have been
    put back in place of `world.db`.
  - A backup that failed on one folder no longer stops later scheduled backups. The steward's
    backup on a computer without a search index now takes everything, not only `world.db`. A
    backup made on request now copies the companions' story and biography, like the scheduled
    one.
  - Two backups started at the same moment (the one scheduled at startup and the steward's
    dial) run one after the other under separate names. When they shared one name, the second
    copied the whole search index.
- **Web search finds more answers.** The big search engines often blocked the companion's
  searches, and the program ignored Wikipedia's answers and never tried the science search
  engines. The search helper's default general engines (Brave, DuckDuckGo, Google, Startpage)
  often answered with "too many requests" or a CAPTCHA. Wyrdsekai ignored the summary box that
  Wikipedia answers with, and never asked the science engines. It now reads that summary box.
  When the general engines return nothing, it asks the science engines, through SearXNG only
  (the built-in backup search ignores this). It does not ask them after a failure or a
  timeout, which had added a second 15-second wait to the companion's reply. The shipped
  settings add a Wikipedia engine that searches the full text (English). Upgrading with the
  .deb package restarts the search helper when its settings differ from the shipped ones. On
  macOS and Windows, run `docker restart wyrdsekai-searxng` after upgrading.
- **Library search summaries now start with what was found, not with what was missing.** They
  used to open with "The provided sources do not contain information regarding…" even when the
  sources held relevant material. The library card's summary now starts with what the sources
  say about the question, and only then, briefly, what they do not cover. An answer that cites
  a source is no longer treated as "nothing found" just because it mentions a gap at the end.
  (Being treated that way held the answer back, left it out of findings and sent the companion
  to search the web.)
- **The follow-up check on a night's learning now looks at the right lines, and survives a
  restart.** It judged the night by everything the companion said, including lines where the
  night's learning was not in use, and a restart dropped the check. On the larger model
  (`single-sparse`), the night's add-on file is used only when the companion is talking with a
  person, but the check read every line after the night was put into use. Lines made with the
  night's add-on file are now marked in the companion's record. The check compares only those
  with the companion's replies to a person the day before, and tries again every hour for a day
  (it was six hours). A check that is still waiting is saved next to the night's files, started
  again when Wyrdsekai starts, and dropped if that night is no longer in use.
- **Web search results are no longer recorded as if the companion had said them.** They are now
  marked as the search tool's words, so the nightly learning does not learn from them. Like
  library findings, they are said as the tool's words, and they stay out of the nightly
  learning and out of the companion's own lines in the conversation.
- **When a companion is not yet allowed to do something, the steward's approval now works.**
  When a companion tried to build a room it was not yet allowed to build, it said "I haven't
  earned the ability to create a room yet" and offered to ask the steward. The steward could
  approve with `approve <id>`, but the approval was never checked, so nothing changed. Now the
  steward's approval is honoured for that action. If the companion is allowed to build rooms from
  the ready-made designs, a room it may not build from scratch is built from the ready-made design
  its name matches. The refusal now reads "To create a room isn't mine to do on my own yet".
- **A companion no longer repeats a long library result word for word as its next answer.** The
  program writes some lines in the companion's name, such as library findings and workshop
  results. These were shown to the model as if the companion had said them, so it copied them in
  its next reply. They are no longer shown that way.
- **Asked whether it has done what it promised, a companion now answers from what it actually
  did.** Before, it answered from what it had promised. When you ask how it is getting on, the
  companion is now shown its record first: for each subject it said it would learn, whether it has
  read anything on it yet. What it reads on such a subject is now kept in its long-term memory,
  where it can find it again, not only in its short-term memory. The add-on file from the nightly
  learning was not the cause: the answers were the same with it switched on and off.
- **"I'll start with a study room" is no longer taken as a subject to learn.** Something the
  companion plans to build, such as a room, a space or a tool, is no longer treated as something
  it wants to learn.
- **Promises to learn now reach the companion's free time.** When a companion agreed to learn
  something, nothing carried that into its free time. For example, after agreeing to learn a
  subject ("Let's begin with attention mechanisms, transformer architecture, then diffusion"), the
  companion spent its free time searching the library using the fixed wording of a built-in want.
  Now what it says it will learn becomes something it wants to do. Only the companion's own words
  are used, not the person's request, and the new wish does not take priority over the
  companion's other wishes. How the subject is chosen and read is described under "A subject a
  companion says it will learn is now actually read".
  - The program already turned "I wish I could…" into a wish to grow. It now also reads what the
    companion says it will learn or start with ("I'll learn…", "let's begin with…", "I'll start
    with…"), and the wish names the subject.
  - This check now also runs when the person leaves, looking over the last two hours, not only
    when the companion goes to sleep.
- **A line from the other companion can no longer send a companion's helper off to work.** A line
  from the other companion that sounded like a request to build something could make a companion
  send out a helper to do it. A backup check catches build requests that were first taken as
  chat. It decided whether a person had spoken only by looking at who was in the room. It now also
  looks at the speaker's ID, the same way the other checks do.
- **An internal note about a helper's work is no longer read aloud.** When a helper (a bunshin)
  came back without doing anything, the companion could read out the program's warning note:
  "[unverified claim — this bunshin executed no tool calls; treat as NOT done]". That note is now
  removed from what the companion says. If the helper's report is only that note, the companion
  says "My bunshin came back with nothing done."
- **Two companions talking quickly no longer say the same line back to each other word for
  word.** The check that stops a companion from repeating a line exactly was paused for a short
  while whenever anyone spoke, including the other companion. Now it pauses only when a person
  speaks.
- **A night's learning could leave a companion using one sentence pattern over and over, and the
  checks missed it.** After the first night on the larger model, a third of the companion's lines
  the next morning followed one sentence pattern (3–7% before). That night had passed all its
  checks, including the morning check. Four new checks now catch this, and all four use the same
  idea of what a sentence pattern is:
  - The text the nightly learning studies now lets any one pattern make up at most a tenth of the
    day's new lines, keeping the most notable ones. Conversations and dreams are never dropped
    because of their pattern.
  - On the larger model, the learning now watches whether it is making empty pattern sentences
    more likely. It also holds back some of the day's lines for testing. If the pattern sentences
    gain more than 80% of what those test lines gain, it stops and keeps the last point below
    that.
  - The morning check now asks ten reflective questions with the night's learning switched on and
    off. It fails, and sets the night's add-on file aside, if more than a quarter of the answers
    with it on share one pattern and that is more than ten points above the answers with it off.
    This check did not catch the night in question (0% either way), because its questions are
    asked without what the companion is normally told: who it is, its drives and its room.
  - That is why there is a new command, `wyrd sleepwrite watch [--since ISO] --being <id>`, where
    `ISO` is a date and time and `<id>` is the companion's ID. It judges a night that was applied
    by the companion's real lines since then, compared with the day before. If the night fails, it
    sets the add-on file aside and restarts the model without it. The household runs it on its own
    two hours after an applied night, then every hour until there are twenty lines to judge. The
    night's record notes a night that was set aside. On the night in question it found 46% of
    lines on one pattern with the add-on file and 5% without.
- **Two companions no longer keep each other awake.** Each counted the other as a person who was
  still around, so two companions talking in one room kept each other from sleeping and the
  household's night never began. Only a person counts now: a person's line waiting to be answered,
  or a person who spoke to the companion in the last 5 minutes.
- **An old saved login no longer breaks the `wyrd` commands that run the house.** They failed and
  said the server might not be running, when the real problem was a login the server no longer
  accepted. `wyrd` always used a saved login (`session.token`) before the computer's own operator
  token, even when the server had stopped accepting that login. So `wyrd forge`, `wyrd repair`,
  `wyrd sleepwrite rehearse` and the other steward commands were refused. Now, when both exist,
  `wyrd` tries the saved login once and uses the operator token if the login is refused.
- **The nightly learning no longer treats the program's free-time instructions as something said
  to the companion.** It learned those instructions as if someone had spoken them, and learned
  from more of the companion's free-time lines than intended. The companion's free-time lines were
  recorded as answers to the program, with the program's instructions as what it had heard. That
  also let those lines slip past the limit on lines said to no one. They are now recorded as said
  to no one, with nothing heard, and are cut down along with the companion's other such lines.
- **With two companions on one computer, the nightly learning no longer mixes them together.**
  Before, it blended both companions' words into one, and on the larger model it could stop the
  model while the other companion was still awake. Now a night uses only one companion's own day.
  The nightly learning was built for one companion per computer. With two, both voices went into
  one add-on file. On the larger model that one add-on file was switched on whenever either
  companion spoke, and the learning stopped the only model while the other companion was awake.
  Now:
  - A night uses only the sleeping companion's lines. The household names the sleeping companion
    in `WYRDSEKAI_SLEEP_WRITE_AGENT_ID`. If you run the learning by hand without it, every line is
    used, as before.
  - On the larger model, each companion has its own folder, `adapters/brainwrite/<entityId>/`, for
    its night's files and its add-on file (`<entityId>` is the companion's ID). The server loads
    each companion's add-on file at startup, switched off. When a companion is talking, only its
    own add-on file is switched on, next to the honesty add-on file. A companion with no add-on
    file of its own uses none. `wyrd sleepwrite status|apply|guard|rollback` now take
    `--being <entityId>` to name the companion; with only one companion you can leave it out.
  - On the larger model, the nightly learning now waits for the whole household. The first
    companion to finish its sleep cycle starts the night, and the others are asked to sleep. Once
    all are asleep, each companion's learning runs in turn, and everyone wakes after the last one.
    A companion that a person spoke to in the last 5 minutes, or that has a person's line waiting,
    is not put to sleep. If the household is not all asleep within 4 minutes, no learning runs
    that night and the records say why. A practice run applies to the whole night.
  - On the two-model setup, the voice model loads one add-on file for every request, so only one
    companion can own it: the one with the oldest record. The owner is written in
    `adapters/sleepwrite/owner`. The owner's nights use only its own lines. Another companion's
    night stops without learning anything, and its record says why. Still open: the owner's
    add-on file shapes every companion's voice, because those requests do not say which companion
    they are for.
- **Twenty more fixed sentences the program says for the companion are no longer recorded as its
  own words.** So the nightly learning no longer learns from them. The earlier fix, which marks
  the program's own sentences so the nightly learning leaves them out, missed some that come from
  the translated-text files: workshop results ("The workshop task finished: …", "needs repair",
  "not done", and the plan and refusal lines), library search lines, and the confirmations for
  remember, forget and descriptions. They are marked now.
- **A practice run of the nightly learning started right after a restart no longer just says it
  failed.** `wyrd sleepwrite rehearse` right after a restart said the request failed. The
  companion appears a few seconds after the server starts answering, and the message now says so.
- **On the larger model, a night's learning is no longer thrown away because it trained too
  long.** It was checked only at the very end. On a real day it went well at first and then
  overdid it, so the whole night was refused. The learning goes over the day's lines more than
  once. The companion's everyday speech barely changed during the first pass, but it changed more
  each time the lines repeated, until it went past the limit. Now the check runs while it learns.
  It keeps the last point where the change was well inside the limit (within 60% of it), stops
  once the change goes past the limit, and goes back to the kept point. The same day then produced
  an add-on file ready to apply. Learning on a Mac and on the two-model setup is unchanged.
- **On the larger model, the companion no longer wakes up while the model is switched off for the
  nightly learning.** It woke up with no model to think with, and everything it tried failed until
  the learning finished. Its sleep ended as soon as the learning started, so every reply failed
  for as long as the learning ran (about 12 minutes on a 16 GB graphics card). On the larger
  model, sleep now lasts until the learning finishes, or 45 minutes at most. The two-model setup
  is unchanged.
- **After switching to the larger model, a companion could end up running on a slow copy of its
  old model instead.** The server started the old model itself on the processor, without the
  graphics card, and used it in place of the larger one. `WYRDSEKAI_MODEL_PATH` and
  `WYRDSEKAI_VOICE_ENABLED` stay in the settings file after `wyrd brain enable --single`, so that
  `wyrd brain disable` can switch back. At startup the server waited 120 seconds for another
  program to serve the old model, then started it itself on `:11525` without the graphics card.
  It used that model for the companion's thinking and set up a voice model that was not running.
  Now, on the larger model, the server starts no model of its own, uses the model address in the
  settings, and does not set up the voice model.
- **A research library on the same computer no longer stops sending updates when the computer's
  network address changes.** Linking it gave it the computer's network address, so updates
  stopped without warning when that address changed. `wyrd researcher link` gave the library the
  address `http://<lan-ip>:7070/…` to send updates to, even when the library ran on the same
  computer and was reached at `127.0.0.1`. Now a library on the same computer is offered
  `http://127.0.0.1:<port>/…` first, and the network address only if it refuses. (ResearchZosho
  up to 0.4.6 refuses an update address that matches one of its own services.) The old address is
  removed only after the new one is accepted. Linking again keeps the existing shared secret, and
  `wyrd researcher unlink` removes both kinds of address. A library on another computer still
  gets the network address.
- **Tools now get only what you asked for, not the program's internal instructions along with
  it.** A library search could come back as a scrap of program code instead of an answer. Tools
  were given the person's words together with the program's instructions to the companion. The
  program cut off the start of those instructions but kept the end, which told the companion how
  to reply. That ended up in the search, and a small model that sums up results answered the
  instruction instead of the question. (On the two-model setup the library answered with just
  `{"action": "`.) Tools now get only the person's words. And when the companion's work with a
  tool ends with nothing useful to say, it now says what the tool found, before falling back to
  the fixed line "I worked on that".
- **Fixed sentences the program says for the companion are no longer recorded as its own
  words.** There were 232 of them, such as "Taking that to the workshop: …", and the nightly
  learning now leaves them out. These sentences, and the two fixed lines said when a piece of work
  ends, are now marked as the program's words. The mark stays through the step that polishes the
  wording, it is kept in the companion's record, and the nightly learning skips those lines.
- **The companion's free-time routine no longer gets stuck.** It rotates through kinds of
  activity, such as exploring, reading and building, but it often stayed on one and started over
  from the top after every restart. It moved on only when an internal counter happened to line up
  at the right moment. Each activity now gets two free-time replies, and the rotation now starts
  from the time of day instead of from the first activity.
- **A companion no longer describes someone it has known for weeks as "Recently met."** Its short
  description of each relationship was written once and never updated. Each night the program now
  rewrites that description from how many times they have talked and how close the bond is, as
  long as the description is one the program wrote.
- **A companion no longer starts something of its own while your message is waiting.** Its free
  time now waits while a person's message is waiting to be answered, or set aside to be answered
  later.
- **Companions can use the library and web search in their free time again.** The program mistook
  its own free-time instructions for an emotional moment and took those tools away. The check that
  reads the mood of a moment read those instructions as emotional, and in an emotional moment the
  library and web search are taken away. A reply started by the program itself is now treated as
  neutral. Each free-time reply now writes `Own time: '<name>' subgoal <X>` to the log.
- **In-world commands such as `birth <name>` now work when given a name.** They were rejected as
  unknown commands. The check that you are the steward could also fail, because it compared two
  different kinds of ID for the same person. `birth` and the other forge commands (`forge`,
  `grow`, `compare`, `restore`) were treated as unknown when something followed them; now they
  reach the room. The steward check now recognises either kind of ID as the same person, as
  renaming and demolishing rooms do.
- **Companions no longer repeat the program's descriptions of their feelings.** The program
  described the companion's inner state in vivid phrases; the companion said them back, and the
  nightly learning then taught them to it. Phrases such as "You feel a strong pull toward…",
  "achingly alone" and "weighed down by what you owe" were given to the model, repeated by the
  companion, and then learned each night. These descriptions (in English, Spanish and Japanese),
  the sentence that states a free-time want, and the wording used for dreams are now plain
  labels. The short summary of the companion's drives that the model is also given is unchanged.
- **Fixed descriptions of what a companion is doing, and fixed dream sentences, are no longer
  recorded as things it said.** The nightly learning had been learning from them. These built-in
  descriptions and dream sentences went out as speech and into the text the nightly learning
  studies. Going to sleep, waking up and arriving in a room are now shown as actions, not speech.
  The dream report is plain and goes only to the journal.
- **The nightly learning now sees whole lines, and knows who they were said to.** It used to learn
  from lines cut off after 200 characters, with no record of who they answered. The companion's
  record now keeps spoken lines whole (up to 4,000 characters, was 200), with who they answered
  and what that person said. The nightly learning now reads conversations as conversations. It
  keeps at most the 150 most notable lines said to no one. It drops a line that announces an
  action ("let me look…") when no action follows within three minutes.
- **Six groups of our automated tests counted their own message as the companion's answer.** So
  they passed even when the companion never replied in time. The tests counted "You tell <name>:
  …" as a reply. With any model slower than the 8–10 seconds the tests waited, the real answer
  was missed and the check that the companion responded still passed. That line is now skipped.
- **One automated test looked for a log message the program no longer writes.** It now checks
  what the program writes today.
- **One group of automated tests failed 4 of its 5 tests because resetting the companion kept the
  time it last slept.** A rule that allows a forced sleep at most every five minutes then turned
  down every later test. A reset companion now counts as not having slept. The five-minute rule
  itself is unchanged.

## [0.4.2] — 2026-09-18

### Added
- **Zone doors on the body map.** Federated zones had no liveness signal, so they were not
  parts. The federation actor now sends each active partner the existing agreement query
  once a minute; a reply, or any message from that zone, counts as contact. The body map
  attaches one `door:zone:<id>` part per active partner (kind door, weight quiet). A zone
  silent for more than five minutes is marked numb, and the companion's body line reports
  the door to that zone as closed. `wyrd body` lists the doors with the other parts. A door
  is added only after the partner has answered once or has been silent through a fair
  chance (150 s), so a new partner's door is not born numb.
- **The nightly forge reads the dream.** The sleep cycle started the forge and the dream at
  the same moment, so the forge only saw the day's event fragments. Sleep now waits for the
  dream (at most 95 seconds) and appends the dream text to the forge's input as the last
  line of the day, so the behavioural extractor and memory consolidation see the day as the
  companion narrated it. The live event list is not changed. A short day, a paused router or
  a missing backend still skips the dream, and the forge runs at once.
- **The morning guard asks companion-specific questions.** The guard's fixed probes are
  generic. At the companion's first sleep the server writes
  `adapters/sleepwrite/guard-questions.jsonl` with two questions: the companion's name (the
  answer must contain it) and the household language under pressure (a prompt in another
  language that asks for the answer in that language; the answer must be in the household's
  script). Each dream appends a candidate question to `guard-candidates.jsonl` (what the
  companion narrated about the day, checked against a few of its words); a later dream on
  the same day replaces that day's candidate. Nothing is asked until the steward runs
  `wyrd sleepwrite questions accept <id>` (`list` and `reject` exist too). On a PASS morning
  the guard stores the night's identity and language answers in `guard-known-good.json` and
  every later morning compares against that file as well as against the same-morning base,
  so a slow drift across nights fails the guard even when each morning's base is unchanged.
  New check kinds in `morning_probe.py`: `contains_any` and `script`; `--questions` and
  `--known-good` set the paths.
- **Vault sealed at rest.** `wyrd vault sync` copied a plaintext store offsite. Every chunk
  and manifest is now AES-256-GCM under a key in `<data>/vault.key`, generated on the first
  copy and never placed in the store. The store records the sealing key's id in `key.id`; a
  node with a different key refuses to read or write it, and `wyrd vault status` names the
  key it needs. `wyrd vault key` prints the key's path and id; `wyrd vault restore` and
  `stage` take `--key FILE` for a restore on another machine. A 0.4.0 store is sealed in
  place on the first pass after the upgrade. Keep a copy of the key file off the disk: an
  offsite copy of the store cannot be read without it.
- **Windows brainstem.** Windows had no watcher outside the JVM. The tray application now
  runs the brainstem loop: it writes `brainstem\heartbeat` and `events.jsonl` in the data
  directory, polls `/health` every four seconds, and after six misses past a three-minute
  grace copies `world.db` to `backups\` and runs `wyrd restart`. A node stopped from the
  menu is not restarted. `wyrd status` shows whether the tray is watching, and the body map
  attaches `env:brainstem` on Windows as on the other platforms.
- **Memory caps on the inference containers.** The three llama containers run with a cgroup
  memory limit: model file plus prompt cache plus 2 GiB, computed by the launcher when it
  starts inference. A runaway backend dies inside its own cgroup instead of consuming the
  server's memory; the model's file pages are reclaimed under the cap before anything is
  killed. Override with `LLAMA_DRIVE_MEM_LIMIT`, `LLAMA_VOICE_MEM_LIMIT` or
  `LLAMA_EMBED_MEM_LIMIT` in `wyrdsekai.conf` (docker sizes such as `6144m`; `0` means no
  cap). `wyrd doctor` reports the caps. Existing containers pick the cap up on the next
  `wyrd start`, which recreates them.
- **Doors as firewall sets (Linux).** A door on the body map (the relay, the library
  connection, a federated zone) can be closed without inference: `wyrd body door close
  <door-id>` resolves the door's addresses and adds them to an nftables set the host's
  output chain rejects, through the `wyrdsekai-doors` helper installed beside the
  brainstem; `open` removes exactly what `close` added; `list` shows closed doors. A door
  whose addresses include the host itself (loopback, link-local or one of its own
  addresses) is refused; in the install test, closing the library door on a node whose
  library endpoint was local cut the server off from its own backends and health check
  until the brainstem restarted it. The reflex table gains a `CLOSE_DOOR` action with no
  default row. A mark records each close and open. A reboot opens every door.
- **Per-being principals (Linux).** Every tool a companion starts (a coding backend, a
  skill) ran as the daemon, which is root on the Linux package, in the daemon's cgroup. Each
  companion now gets a Linux user (`wyrd-being-<slug>`, uid 62000 to 62999, no shell, group
  `wyrdsekai-beings`), a home under `<data>/beings/<slug>/home`, and a cgroup under the
  service's. The `wyrdsekai-being` wrapper joins that cgroup while root, drops to the user
  with no capabilities and `no_new_privs`, then execs the tool. Coding workspaces are owned
  by the being's user. `WYRDSEKAI_BEING_MEMORY_MAX` sets a memory budget per being (cgroup
  `memory.max`); the tools die as one tree under it. The service unit carries
  `Delegate=yes`. Where the host cannot provide this (a source checkout, a non-root service,
  macOS, Windows) the tools are shared, and `wyrd body` says so on its hands line. The ACP
  agent process, the bundled coding CLI, every subprocess coding backend and CLI skills go
  through the wrapper; item repair escalation and health probes stay the daemon's own. The
  data directory becomes mode 711, with every top-level entry except `coding-cli-bundle`,
  `coding-workspaces`, `beings` and `models` closed to others (the database was
  world-readable whenever the directory was 755). A tool whose executable cannot be started
  as the being's user runs as the daemon that once and the steward is told.
  `WYRDSEKAI_BEING_PRINCIPALS=off` turns the feature off. The launcher no longer resets the
  shared data directory to mode 700 on every command that touches the session token.
- **Kernel hooks on companion tools (Linux).** With principals in place the server runs one
  `bpftrace` program attached to the open, exec and connect system calls, filtered to the
  beings' uid range. A tool that opens the database, the keys, the vault, the brainstem's
  files or another being's files, or that execs one of the host's control programs
  (`systemctl`, `docker`, `nft`, the wyrdsekai helpers, `sudo`), is cut: the tool's cgroup
  is killed and a mark records it. A being's own home is exempt; without that exemption the
  first live day cut a coding tool twice for opening its own settings. A reach for anything
  the kernel already refuses (souls, agents, the config) is recorded, not cut. Other execs
  and every connection are recorded in `<data>/brainstem/hooks.jsonl`. The hooks are a part
  on the body map (`sense:hooks`), numb when bpftrace is not running; `wyrd body` and
  `wyrd doctor` show the state. Needs `bpftrace` (a Recommends of the package) and a kernel
  with BTF.
- **Hook rules are replayed before they are armed.** The hooks' rules are data
  (`<data>/brainstem/hook-rules.json`, or the built-in list): paths whose opening cuts the
  tool, paths that are only recorded, programs whose exec cuts the tool; `${data}` stands
  for the data directory. Everything the tools do and are not cut for is kept as a table of
  distinct events with counts (`<data>/brainstem/hooks-seen.jsonl`, per-task paths folded,
  20,000 rows at most). `wyrd body hooks replay [rules.json]` runs a rule set over that table
  and prints what it would have cut, changing nothing. `wyrd body hooks arm <rules.json>`
  installs a rule set only if that replay is clean and the history covers
  `WYRDSEKAI_HOOKS_REPLAY_DAYS` (default 3); `--force` overrides, and the steward's marks
  record the override. The same replay runs at every start on the node's own rules: if they
  would cut what the tools normally do, the hooks run in record-only mode, write
  `would-cut` lines to the ledger, cut nothing, and tell the steward.
  `WYRDSEKAI_HOOKS_MODE=record` asks for record-only outright. `wyrd body hooks` shows the
  mode, the rules in force and how much history there is.
- **Hardware watchdog (Linux).** The package installs `RuntimeWatchdogSec=120s` for systemd
  and loads `softdog` where the host has no watchdog device, both as conffiles. If PID 1
  cannot pet the device for two minutes the host reboots and the brainstem restarts the
  server. `wyrd doctor` shows whether the watchdog is on.
- **One check for every automatic action against a part.** Cutting a tool, closing a door
  and quarantining a part were each decided where they happened, and each had already acted
  against the companion's own resources once (a tool killed for opening its own home; a
  door onto the host closed). All of them now pass one check (`Immune`): a cut inside the
  being's own home is refused, a door whose addresses include this host is refused, a part
  the household itself attached is never quarantined, and only a person severs a part. A
  refusal is written as a mark to the steward stating the proposed action and the reason,
  so the steward can do it by hand if it should be done.
- **Parts carry provenance; a part from outside the household is quarantined.** A part
  descriptor records who attached it (`attachedBy`) and what it claims to be. Household
  parts have no provenance. A part attached by someone outside the household (a federation
  partner's node, a peer that is not a member) is put on the map in state `QUARANTINED`: it
  is not used, `wyrd body` shows it, and one mark tells the companion it waits for the
  steward. `wyrd body vouch <part-id>` attaches it and records who vouched; the vouch is
  stored in `body_parts` and survives numb spells and re-attachment. `wyrd body immune`
  lists what is held. A held part's heartbeats are recorded but do not change its state,
  and the clock does not age it. An inference backend discovered across a federation from a
  node that is not a household member is a foreign part too: it is held as a `borrowed
  brain`, and the router never selects it, even as a last resort, until the steward vouches
  for it. Each part is vouched for on its own; vouching for a node does not vouch for the
  backend it offers.
- **Immune memory.** What the body acted against is kept in `immune_memory` (migration 11)
  for one year from the last sighting: a door that was closed and the hosts behind it, a
  tool that was cut and what it reached for, a visitor whose dock offer was rejected, a
  capability a visitor asked for and was refused. A second sighting raises the count and
  moves the expiry. A part attached by a remembered source is held with the memory named
  in the mark. `wyrd body immune` lists the entries; `wyrd body forget <id>` removes one.
  Both launchers have the verbs; the routes are `/api/body/immune`, `/api/body/vouch` and
  `/api/body/forget`, steward only.
- **`call <companion>`.** The bondholder can ask their companion to come to their room
  from anywhere in the zone (`summon` is the same verb). The same conditions apply as for
  following: asleep (not woken; the call is dropped), running a coding task (stays), busy
  with inference or below 0.15 energy (comes when that clears), otherwise comes now. The
  caller gets one line saying which applied. A caller who is not the bondholder gets a
  refusal. Available over ssh, telnet, the web client, the CLI and the phone clients
  (which forward the verb as a generic command).
- **`wyrd items broken` and `wyrd items repair <name>|--all`.** For items already in the
  world: `broken` lists every household item the contract gate would refuse today, with
  its problems. `repair` sends a copy through the coding backend with those problems as the
  instruction, keeps the never-worse rule of the repair rounds, and replaces the placed file
  only when the copy has no problems left; the previous version is kept under
  `items/.repaired/`. An item that cannot be repaired is left unchanged. Steward only.
  Bundled items are never touched.
- **Broken items are reported to the companion and repaired at night.** An item that was
  placed and does not work used to stay that way: nobody told the companion, and only a
  steward could repair it. Now:
  - Two minutes after start every household item is checked against the contract gate, and
    the companion gets one mark, in plain words, for each item that fails (for example "The
    bondholder mirror that was made for me does not work yet: it reaches for a part of the
    world that is not there (world.memory.get)."). The mark is repeated only if the way the
    item is broken changes. An item that fails when used returns the same sentence instead
    of a raw script error.
  - After each sleep cycle the workshop repairs broken items one after another, on a copy,
    replacing the placed file only when the copy passes the gate. The bound is time, not
    count: `WYRDSEKAI_ITEM_MEND_MINUTES` per night (default 45; `0` turns it off). It waits
    for two minutes of idle inference before each item and stops when quiet hours end; an
    unchanged file is tried at most three times; items that fail on use go first. A mark
    records each result: repaired, or tried and not repaired.
  - The Hearth gains a mending bench (`use mending bench`, `use mending bench mend <name>`,
    through `world.workshop.broken()` and `world.workshop.mend(name)`, capability
    `workshop.mend`), so the companion can start a repair from the Hearth.

### Fixed
- **The companion could have no bondholder.** On the household node the bond row that
  carried the BONDHOLDER role was under the person's DID with `active = 0`, while the
  active row with the history was under their login id and typed MEMBER. The load-time
  repair that merges one person's rows skipped inactive rows, so it never saw the split,
  and every check that asks for the bondholder answered nobody: no following, no login
  greeting, no presence tracking, no capture of the bondholder's register. The repair now
  groups a person's rows whether active or not and merges when any of them is active (two
  inactive rows are left alone); the merged bond keeps the deepest depth, the summed
  interactions, the role, the DID, and is active. The rename authority check also compared
  ids as strings; it compares persons now.
- **The companion did not follow the bondholder over ssh.** When the bondholder left the
  room, the follow path compared the bond's id (a DID) with the id the room knew them by
  (over ssh, the login id) as strings, so the departure was never recognised. The same
  comparison sat in four sibling checks: noticing the bondholder's activity, the witness
  posture when the bondholder is present, the "take me with you" detection, and the
  cross-zone invite. All five now compare persons (`PersonIds.samePerson`), and the follow
  looks the room up by the id the room uses, falling back to the bond's.
- **Items that call what does not exist were placed as finished.** The contract gate an
  item passes before it is placed did not check its `world.*` calls against the API. The
  loader's audit and `wyrd items check` did, but only after placement. The coding backend
  built three items that called `world.memory.get` and `world.memory.list`, which do not
  exist; the gate's one smoke call took another branch, the items were placed as finished,
  and they failed on first use. The gate now checks every path through the script: calls
  that do not exist (the message lists what the namespace does offer), commands the
  manifest declares that `invoke()` never reads, and a name a builtin action already owns.
  Such an item goes through the repair rounds and is placed as unfinished if they fail. One
  class (`ItemWiring`) holds the checks for the gate, the loader and the CLI.
- **Night repair gave up before it started.** The workshop's nightly pass checked the
  inference queue once, immediately after the sleep cycle, when the companion is always
  generating (the chronicle, a polish, the first turn after waking), found it busy and
  logged "stopping for tonight" without trying an item. It now waits for two minutes of
  idle inference before each item, checking every fifteen seconds, for up to three hours
  after the sleep; only the end of that window, or the end of quiet hours where they are
  set, ends the night. The working budget (`WYRDSEKAI_ITEM_MEND_MINUTES`) is unchanged.
- **The resilience classifier wrote a trail line every window.** One `resilience` line
  every twelve seconds saying "steady state" was 91% of a companion's activity trail (about
  7,100 lines a day, 100 MB in all). A line is now written when the classification changes
  or once an hour otherwise, carrying `windows`: how many classification windows it stands
  for. The chronicle's "Substrate trajectory" counts windows, so its totals are unchanged.
  Existing trails are left as they are.
- **Two dreams on one day proposed two guard candidates with one id.** The steward could
  accept only the first (the verb takes the first match). A later dream on the same day now
  replaces that day's candidate; other days' candidates are kept.
- **The server log repeated a GraalJS warning on every script engine.** On a stock JDK the
  polyglot engine runs in interpreter mode and printed a four-line warning each time an item
  or room script engine was created, about 2,400 lines in three hours. The server launchers
  (deb and tarball `wyrdsekai-server`, macOS, the Windows launcher and the packaged
  executable) now pass `-Dpolyglot.engine.WarnInterpreterOnly=false`.
- **`wyrd update <file.deb>` did nothing when the file's version was already installed.**
  The apt-get path answered "0 upgraded" and kept the old files while the launcher reported
  the update as done. It now passes `--reinstall`, so a rebuilt package of the same version
  is installed.
- **macOS: release evidence accumulated across upgrades.** The package installer lays files
  down and never removes them, so `/usr/local/wyrdsekai/data/release-evidence` on an
  upgraded node held the bakes of every release since July (130 files). The preinstall step
  now clears it so the directory holds this release's evidence only, as the deb already
  does.
- **The between layer could stop itself at start.** When it failed to start (NATS late), a
  request for the federation actor was answered with null, which a typed actor may not be
  told; the supervisor stopped the actor. It now does not answer, and the asker times out.
- **Windows: `wyrd update` left the old server running.** The installer ran over a live
  node, could not replace the jars it held open, scheduled them for the next reboot (exit
  3010), and the launcher reported the update as done. The launcher now stops the node and
  the tray before the installer runs, starts them again afterwards, and says so when Windows
  still asks for a restart.
- **macOS: the brainstem could not restart a hung server.** `launchctl kickstart -k` kills
  the job's bash wrapper but not a hung java child, which kept the port, so the new instance
  never bound; and the grace period was not re-armed after the brainstem's own restart, so
  it restarted a booting server every minute. The brainstem now kills the process holding
  the port by pid before the kickstart and restarts the grace period from its own restart.

### Changed
- **`wyrd backup` no longer copies the search index.** It made a full copy of `search/` on
  every run, 163 GB on a node with the whole library. The index is derivable and the nightly
  snapshot already hard-links it; the backup now reports that it skipped it.
- **`wyrd start` exits 0 when the node is already running.** It returned 1 on all three
  platforms, so scripts and the desktop shell read a running node as a failed start.
- **`wyrd update` installs the package with `apt-get` when it is available**, falling back
  to `dpkg -i`. `dpkg -i` ignores Recommends, so a node installed that way had no `sqlite3`
  and the brainstem's pre-restart snapshot was a plain copy. `wyrd doctor` now reports
  whether `sqlite3` is present.
- **Vault.** `mlx-venv` and `sleepwrite-venv` (and `venv`, `.venv`, `packs`) are classified
  replaceable and are no longer copied. They are rebuilt by the installer or
  `wyrd sleepwrite setup`.

## [0.4.0] — 2026-09-16

### Added
- **Body map.** The server keeps a table of the parts it depends on: each inference
  backend, the database (`world.db`), and the host. Each part has a heartbeat interval.
  Inference backends are updated by the router's existing health checks. A watch thread
  runs every 30 seconds (`body.watch_seconds`): it opens the database and runs `SELECT 1`,
  reads host memory pressure (`/proc/pressure/memory` on Linux), heap use and free disk,
  and marks any part silent for more than twice its interval as `numb`, dated from its last
  heartbeat. A numb part stays in the table until the steward removes it with
  `wyrd body gone <id>`. Before this, a backend that stopped answering was a `WARN` line in
  the log and nothing else changed. Schema migration 10 adds `body_parts` and `body_marks`.
- **Body line in the companion prompt.** Each companion turn now includes one line of
  body state next to the existing `[Body-sense: ...]` line. It is generated from the table,
  not by a model, and it is stripped from speech like the other bracket markers. When all
  parts answer: `[Body: whole — thinking brain and voice brain answering; the record
  holds.]`. When a part is numb the line says which one and what it costs (for example
  `The thinking brain went quiet — I think slower and thinner.`). A numb part appears in the
  line once when it happens, then for `body.ache_hours` (default 6) afterwards; the
  database and the main inference backend stay in the line for as long as they are numb.
  After that the part is only visible in `wyrd body` and the boiler room. High memory
  pressure or a nearly full disk adds one clause.
- **Marks.** Events the companion did not see are recorded in `body_marks` and included in
  her next prompt once: a part going numb or returning, a part removed, a pause, a resume,
  a reflex, a sleep cycle, the nightly weight-write result. Each mark records which
  companions have read it. A mark addressed to one companion is not shown to another.
- **Sleep marks.** Each sleep cycle writes a mark with the start time, duration, number of
  events consolidated, and the memory count before and after. The nightly weight-write
  writes a mark when it finishes: staged plus the morning guard's verdict, quiet day, gate
  failed, or error. Previously nothing told the companion a sleep or a write had happened.
- **Mail arrival.** Mail to a companion was stored but never announced. The mail service
  now sends `MailArrived` to the companion actor. The arrival is added to the prompt as a
  system event, and if the companion is idle and has energy it triggers an own-time turn.
  The companion's Hearth now contains the real `mailbox` item (`use mailbox`,
  `use mailbox read <n>`, `use mailbox send <who> <subject> | <body>`) instead of a room
  object that only printed a placeholder.
- **`wyrd body`.** Prints the table for the steward: kind, part, state, felt weight, last
  heartbeat, last use, detail, and recent marks. `wyrd body gone <id>` removes a part.
  `GET /api/body` and `POST /api/body/gone` back it (steward session). In the boiler room,
  `use pressure gauge` and `use computer body` print the same table; in the engine room,
  say "show the body map". New config keys `body.watch_seconds` and `body.ache_hours`
  (`WYRDSEKAI_BODY_WATCH_SECONDS`, `WYRDSEKAI_BODY_ACHE_HOURS`).
- **Quiesce.** One routine runs before the server is paused, stopped, updated, or a
  companion restarts a backend or reboots the host: the inference router stops accepting
  turns, every companion actor is asked to persist its state (vitality, sleep pressure,
  conversation checkpoint, substrate trackers, soul manifest) within a deadline, the
  database gets a WAL checkpoint, and a mark records the reason, who asked, and how long
  it took. `wyrd inference resume` writes a resume mark. `wyrd stop` and `wyrd update now`
  call `POST /api/quiesce` on the running server first so the mark carries the reason;
  the shutdown hook runs the same routine at SIGTERM without writing a second mark.
- **Updater waits for sleep.** The self-updater's idle check now also requires that no
  companion is in a sleep cycle and the nightly weight-write is not running. A companion
  whose forge backlog is past 70% of her sleep target gets a `[Tired: ...]` line in the
  prompt.
- **Host hand.** A new Hearth item, `host_hand`, runs a fixed set of commands on the host
  under a level the steward sets with `WYRDSEKAI_HOST_HAND` (default `observe`):
  `observe` reads uptime, disk, memory, load, service status, gpu, containers, pending
  updates, logged-in users and network; `localize` adds the service log, container logs
  and process lookup; `propose` mails a text proposal to the steward and runs nothing;
  `guarded` adds `say <text>` (wall), `restart-brain voice|drive|embed` (docker restart)
  and `upgrade` (apt-get); `unattended` adds `reboot`. Every command is a fixed argv with
  validated arguments, no shell. `upgrade` runs `apt-get -s upgrade` first and stops,
  mailing the steward, if the set includes a driver, kernel, grub, systemd, docker or dkms
  package. Commands that change the host run quiesce first and write a mark visible in
  `wyrd body`. Note: on the Linux package the service runs as root, so this setting is the
  only limit.
- **Quiet hours in the database.** `wyrd household quiet 22:00-07:00` stores quiet hours in
  `household_config`; `wyrd household quiet off` clears them; `WYRDSEKAI_QUIET_HOURS` is
  used when nothing is stored (`GET`/`POST /api/household/quiet`, steward). During quiet
  hours visitors are refused as before, companions send no non-critical external
  notifications, and a companion's pressure-based sleep starts at half the normal backlog.
- **Reflexes.** The watch thread evaluates a fixed table on every tick, without any model
  call: memory stall above 30% for two ticks pauses inference for 90 seconds; heap above
  95% for two ticks pauses it for 60 seconds; the database not answering pauses it for 5
  minutes; free disk below 3% writes a warning mark at most once per 6 hours. Each firing
  writes a mark.
- **More parts on the body map.** Household peer nodes from the mesh's resource
  announcements (`node:<id>`, numb after two minutes of silence; nodes outside the
  household are listed with low weight), the relay connection (`door:relay`, from the NATS
  connection state), the coding backend (`hand:codezaiku`, probed every fourth watch
  tick), and the librarian's MCP connection (`door:librarian`). Federated zones are not
  on the map: the federation handshake caches a manifest but nothing pings a zone, so
  there is no heartbeat to record. The relay door is the signal for reaching other zones.
- **Brainstem.** A small bash process outside the JVM, installed as `wyrdsekai-brainstem`
  (a systemd unit on the deb, a LaunchDaemon on the pkg; not on Windows yet). Every 10
  seconds it touches a heartbeat file, asks the supervisor whether the server unit is
  active, and asks `GET /health`. If the unit is active but the server has not answered for
  six checks and the unit has been up for more than three minutes, it snapshots `world.db`
  (`sqlite3 .backup`, or a copy when sqlite3 is absent) into the backups directory, runs
  the optional `doors-close` hook, restarts the unit, and appends an event to
  `brainstem/events.jsonl` in the data directory. It leaves a stopped or crashed unit to
  the supervisor. The server reads the heartbeat file as a part on the body map and turns
  new events into marks, so a restart the companion slept through is in her next prompt.
  Hooks are executables in `/etc/wyrdsekai/brainstem/` (`doors-close`, `doors-open`); none
  are installed by default. `wyrd status` reports whether the brainstem is running.
- **Vault.** A second backup system beside the hourly snapshots, built for continuity rather
  than for a copy: every 15 minutes (`WYRDSEKAI_VAULT_MINUTES`, 0 disables) the server takes
  a consistent copy of `world.db` with `VACUUM INTO` and stores it, with the node identity,
  credentials, config, souls, agents, classifiers, substrate, items, recipes, adapters, story
  and biography, as content-addressed chunks (cut by content, about 4 MiB) under `<data>/vault-store/`
  with one manifest per copy. Unchanged chunks are not rewritten and an inserted page
  changes one chunk, so a quiet quarter hour costs a read and a busy day costs its churn.
  Search indexes are classified derivable and models replaceable; neither is vaulted, and the
  manifest lists them, plus anything in the data directory that nothing classified.
  Retention: every copy for two hours, one an hour for a day, one a day for a week, one a
  week for five weeks, one a month for a year; copies taken before an update, a reboot or a host upgrade are flagged
  and never expire. Every 30 days (`WYRDSEKAI_VAULT_DRILL_DAYS`) the newest copy is rebuilt
  into scratch and checked: integrity, and the counts of people, companions, residencies
  and souls in it; the verdict is written into the manifest and as a mark. The vault is a
  part on the body map and goes numb when copies stop. `wyrd vault status|snapshot|drill|
  prune` talk to the running server; `wyrd vault sync <rsync destination>` copies the
  directory elsewhere (`WYRDSEKAI_VAULT_REMOTE`); `wyrd vault list|restore <id> <dir>` rebuild
  a copy offline; `wyrd vault stage <id>` rebuilds `world.db` and stages it through the
  existing restart-to-apply restore, keeping the displaced database. Offsite copies are not
  encrypted at rest yet.
- **The dream.** At the start of each sleep cycle, before the forge consolidates the day,
  the companion asks the thinking backend to tell the day as she would remember it: the
  day's events as short lines (her own marked as "I"), the last day of chronicle entries,
  her current drive levels and body line, with instructions for first-person past-tense
  prose and no invented events. One request, 700 tokens at most, a 90 second timeout; a day
  with fewer than 5 events, a paused router or no backend skips it and the night goes on.
  The text is written to her Hearth journal with mood `dream` (private; `read_journal`
  shows it), appended to the activity trail as a `dream` entry with her felt stamp, and
  marked so her first turn after waking carries its opening sentence. The nightly
  weight-write now reads `dream` entries beside her spoken lines, and waits up to 90
  seconds for a dream still in flight before it starts. The existing post-consolidation
  dream line from the forge is unchanged.
- **OOM ordering on Linux.** The systemd unit sets `OOMScoreAdjust=-500` and
  `TimeoutStopSec=45`; the llama and embedding containers set `oom_score_adj: 500`, so the
  kernel kills an inference container before the server. `wyrd doctor` prints the server's
  OOM score and warns if it is not negative.

## [0.3.4] — 2026-09-15

### Added
- **Household mail.** Send a message to someone by name. Messages are stored in `world.db`,
  so they survive a restart, and the recipient gets a notification when one arrives.
  Addressing: `ada` (this household), `ada@home` (same thing, written out), `ada@orchard`
  (a federated household), `bob@example.org` (the internet). Naming your own zone delivers
  locally — no federation round trip — so replies to `kaz@home` work.
  The `mailbox` item template is now a real mailbox (`use mailbox`, `read <n>`,
  `archive <n>`, `send <who> <subject> | <body>`) instead of a generic container.
  Sending inside the household needs no grant — same capability tier as `tell`.
  Messages are stored against the recipient's identity with the address kept for display,
  so renaming a zone or a companion doesn't break old mail.
  `wyrd mail` shows the steward a log of who wrote to whom and when — no subjects, no bodies.
  Federated and external delivery are not implemented yet and return a clear error instead
  of silently dropping the message.
- **Multi-line message composition.** `mail` lists your inbox, `mail read <n>` opens one,
  `mail <who>` starts a message: subject first, then body lines, ending with a single `.`
  on its own line (`~q` cancels). This needs no client support, so it works over ssh, telnet
  and the terminal CLI. Lines that look like commands are treated as message text.
  In the browser, `mail <who>` opens a composer with a subject field and a textarea
  (Ctrl+Enter sends, Escape closes) — the browser doesn't use line mode because a chat-first
  client would broadcast each line to the room. Clients that don't support the composer get
  a message explaining the one-line form. The terminal CLI collects the lines itself and
  sends the letter whole.
  Names with spaces work: `mail ada lovelace the garden` looks up the longest leading run of
  words someone answers to, so it goes to Ada Lovelace with the subject "the garden". A quoted
  name works everywhere too, including `use mailbox send "ada lovelace" ...`.

### Fixed
- **One person, one mailbox and one journal, from every door.** The web and the phone sign
  you in under your DID; ssh and telnet still present the legacy login id. Mail is filed under
  the DID, so a letter sent from the browser read as "No mail." from ssh, and a journal page
  written from ssh (which resolves to the DID on write) did not show in `journal read` from
  the same ssh session. Every mail door and the journal read-back now resolve the person
  first. The same fix makes notifications reach a browser session signed in by password.
  This is the 0.3.4 shape of the bond-table defect fixed in 0.3.2.
  Found on the install test: the person-identity cache also remembered "no such person"
  for the life of the process, and on a fresh install a login id is looked up before the
  person's DID is minted at first login. Mail sent on the first day would have been filed
  under the login id and lost from view after the first restart. The cache is now cleared
  when a person is minted or a credential is linked.
- **Short replies in the wrong language weren't caught.** The voice guard compares the
  language of the draft and the polished output, but the detector returns "unsure" for text
  under four words, and the guard only fired when both sides were identified. A short reply
  like "Vale, gracias" passed through. When the household language is known, a single
  Spanish marker is now enough to reject the polish and speak the raw draft instead.
- **Private journal entries couldn't be read back by their author.** Private entries are
  stored under a separate type (`journal_private`) and the read-back only listed `journal`.
  The owner's read-back and search now include private entries, decrypted and marked
  `[private]`. The companion's view is unchanged — it still sees shared entries only.
- **`journal read that letter from mum again` was discarded.** Any entry starting with
  "read", "recent" or "show" matched the read-back branch and was thrown away. Those words
  are now only treated as commands when the rest of the line is empty or a number.
- **The "Read recent entries" menu hint didn't work.** It dispatched with no argument, which
  the script treated as an empty write and rejected with the write error.
- **Journal entries written in the same millisecond overwrote each other.** Entry IDs were
  `journal:<user>:<timestamp>`; they now include a random suffix.
- **`journal` is a real command.** The help text advertised `journal <text>`,
  `journal private <text>` and `journal search <query>`, but the server never implemented
  them — the only working path was `use journal <text>` inside your Study. All three work
  now on every surface, plus `journal read [n]`. `journal` with no argument opens a blank
  entry (line mode in a terminal, textarea in the browser), and `journal private` does the
  same for a private entry.
- **`wyrd version` on macOS and Windows printed a git error as the build hash.** Those
  installers are built from an exported tarball with no git history, and the stamping
  task took git's "fatal: not a git repository" message as the answer. A tree with no
  history now stamps `unknown`.
- **Windows installed an old coding backend.** The PowerShell launcher pinned goose 1.34.1
  and codezaiku 0.3.3 while the bundle manifest attested 1.50.1 and 0.3.6. The comment next to
  each pin said it matched the manifest; it hadn't for two releases. Both now match.

### Changed
- **The Study's "Mailbox" is now the "Grant Case".** It never contained mail — it lists
  capability grants other people, zones and companions have given you, via
  `world.grants.held()`. Existing Studies get the old item removed and replaced with
  `grant-case` on next login; a crafted item that happens to share the ID is left alone.
  This frees the name "mailbox" for actual messages.

## [0.3.3] — 2026-09-14

### Added
- **`demolish <room>` and `wyrd rooms`.** A steward can take a made room down: in-world
  `demolish <room>` (ssh, web), `wyrd rooms list | demolish <room> | prune [--duplicates]
  [--yes]`, and `GET/DELETE /api/rooms`. Every doorway into the room is closed, the actor is
  stopped and the record removed. Founding rooms, companion Homes and occupied rooms are
  refused. `prune` lists rooms whose id carries leaked tool-call markup (and later copies of
  a name with `--duplicates`); `--yes` demolishes them.
- **`where <name>`** on ssh answers where someone is: a public room by name, a private room
  by kind.

### Fixed
- **The map on ssh and telnet showed no people.** The 0.3.2 occupant display was wired into
  the web client only. One helper now serves every surface.
- **Room names with leaked model markup are repaired on upgrade.** Rooms made before 0.3.1
  kept whatever the model emitted (six on one node contained `</parameter> <tool_call>`).
  At boot such names are cut the way new rooms are, and the change is persisted.
- **Long auto-generated exit keys are shortened on the map.** `to-<room-id>` keys of hundreds
  of characters are shown by their head; the key still works in full.
- **Bundled CodeZaiku is 0.3.6.**

## [0.3.2] — 2026-09-14

### Changed
- **Bundled CodeZaiku is 0.3.5; the librarian client speaks ResearchZosho 0.1.11.** Both
  installers take the build with its own Java runtime when Java 21 is missing;
  `wyrd researcher setup` no longer warns about Java.
- **Librarian desk:** `read <id>` returns the answer only, capped, and reports how much was
  left out. `jobs` shows a running job's phase, round and workers.
- **`wyrd researcher link --stdio "npx -y @wyrdsekai/researchzosho-mcp"`** links the librarian
  over stdio.

### Added
- **Windows CLI parity:** `wyrd inference remote <url>`, `wyrd inference pause|resume`,
  `wyrd visitors`, `wyrd soul`, and the inference line in `wyrd doctor`.
- **`map` shows occupants.** Public rooms list who is in them; anyone in a private room is
  listed in a footer by kind only ("at home", "in their Study", "resting"). `where <name>`
  answers the same way.
- **MCP visitors.** A non-resident MCP login is an entity named `<name> (visitor, MCP)`.
  Vouched accounts (`wyrd visitors vouch <user>`) enter at the Nexus, others at the Docks.
  Visitors receive room events (`wyrdsekai_events`, `GET /api/mcp/events?since=<seq>`), are
  subject to Home wards, the sanctuary and quiet hours (`WYRDSEKAI_QUIET_HOURS=22:00-07:00`),
  and can be dismissed (`wyrd visitors dismiss <user>`). `POST /api/mcp/logout` removes the
  visitor from the room.
- **Name prompts wait.** On a terminal the companion and steward name prompts wait 10 minutes
  (`WYRDSEKAI_NAME_PROMPT_SECS`); off a terminal the short countdown remains.
- **`wyrd soul list | rename <old> <new> | archive <name>`.** Rename changes the manifest
  name and keeps the DID, entity id and history. The server resolves a configured name to
  the soul that answers to it and only creates a new soul when none matches, so changing
  `WYRDSEKAI_COMPANION_NAME` no longer creates a second companion.
- **`wyrd inference pause <90s|30m|2h> [reason]` / `resume`.** Requests are refused with a
  `PAUSED:` error the companion reports once, then waits. `/health` includes `inference`
  (inFlight, queued, maxConcurrency, p95LatencyMs, paused); `wyrd doctor` prints it.
- **Parallel slots per model server.** `LLAMA_SKILLS_PARALLEL` / `LLAMA_VOICE_PARALLEL`
  (compose) and `WYRDSEKAI_DRIVE_PARALLEL` / `WYRDSEKAI_VOICE_PARALLEL` (native) set
  `--parallel`; `--ctx-size` is multiplied by the slot count. Default 1.

### Fixed
- **Inference metrics were not recorded.** Only the fallback retry path stamped a start
  time, so `/health` showed 0 samples and the p95 was 0; the snapshot was also taken before
  the in-flight counter was decremented, so one request always showed in flight. Every
  dispatch path records now.
- **`WYRDSEKAI_LLAMA_URL` was not read by the server.** The Windows CLI, tray, desktop
  settings and docs used it for "point at a remote node"; the server only read
  `WYRDSEKAI_INFERENCE_URL`, so a Windows node with a remote URL silently used
  127.0.0.1:8200, and a configured URL was only consulted when no other backend was
  enabled, so a node with its local voice entry enabled ignored the URL too. The configured
  URL (`WYRDSEKAI_INFERENCE_URL`, or `WYRDSEKAI_LLAMA_URL` naming another host) is now
  registered whatever else is enabled. `wyrd inference remote` on Windows writes the
  canonical key and turns the local drive and voice entries off.
- **Windows CLI verbs without arguments failed.** `wyrd version`, `wyrd inference` and every
  other verb that reads its argument list threw "The property 'Count' cannot be found" under
  strict mode when no arguments were given. The argument list is normalised once at startup.
- **`wyrd visitors` printed a Python traceback when the server was down.** It reports that the
  server did not answer.
- **Windows installer deleted the MSI on failure.** It is moved to Downloads with the
  `msiexec` command to finish by hand, matching the Linux/macOS installer.
- **Bond rows were split per person.** The bondholder's bond existed under the login id
  (with history, kind MEMBER) and under the person DID (empty, kind BONDHOLDER). The merge
  ritual joined them at load and the actor split them again at every turn. Bonds are keyed
  by the canonical person id at every path; the merge keeps the bondholder kind and deletes
  the duplicate; the bond crystal shows names; `wyrd state dump` reads the real bond
  columns. `wyrd doctor` reports the consolidation roadmap as notes, not warnings.
- **Router concurrency** defaults to one slot per backend instead of 1. A queue of 10 or
  more logs a warning.
- **Bunshin budgets** are sized from measured p95 latency instead of a fixed 180 s.
  Defaults: `WYRDSEKAI_BUNSHIN_TOKENS` (2000), `WYRDSEKAI_BUNSHIN_STEPS` (30),
  `WYRDSEKAI_BUNSHIN_WALL_CLOCK` (0 = measured). A dispatch can override per task.
- **Inference URLs are probed.** `WYRDSEKAI_INFERENCE_URL` pointing at Ollama registers an
  Ollama backend with its listed models; a URL that answers neither protocol is refused
  at `wyrd inference remote` and logged as an error at boot. `wyrd inference remote` also
  writes the URL to the conf file so `wyrd start` stops asking for it.
- **Wizards:** stdin is drained before every prompt; `wyrd setup` and `wyrd start` share a
  lock; setup ends with a summary of what it configured.
- **MCP `do "go to <room>"` moves.** Moves resolve by direction or destination name; a
  command that changed nothing does not answer "Done."; a refused door steps the visitor
  back.
- **MCP client:** the login tool's `description` no longer lands in the password field (a
  long one caused HTTP 500 from bcrypt's 72-byte limit; the server now returns 400);
  resident routes send the bearer token; an expired session re-logs in once;
  `wyrdsekai_status` reports when companion status is unreachable; the setup docstring
  gives the `claude mcp add` command.
- **`wyrd researcher setup` twice in a row** ran its own log line as a command. It now
  warns under sudo, since ResearchZosho installs per user.
- **Installer keeps the download** when the install step fails and prints where it is;
  `/api/auth/redeem` names the missing field.
- **Companion Home rooms were not locked.** The Home was marked private but no ward rows
  were written and `RoomActor` never checked wards on entry. Homes are sealed to their
  companion (entity id and DID, all permissions) at creation and on every boot, so
  existing Homes lock on upgrade; entry is checked on every path. Nobody else is granted
  by default. The companion grants access with `use ward stone invite <name>` (enter +
  speak; `invite <name> <cap>` for one capability) and `uninvite <name>`; the ward verbs
  act with her authority in her own Home. Stewards keep `wyrd wards`. A companion refused
  at a door returns to her previous room.
- **Tool-result turns overflowed the context.** The turn after a tool result was given all
  tool schemas (188, over 16k tokens) while other turns got a ranked 8 plus `use_item`. It
  is ranked the same way now, and the compactor drops tool schemas (ranked head first, then
  all) before failing.
- **Unknown item templates matched by a shared word.** `craft_from_template` with a
  missing template fell back to any template whose description shared a word with the
  item name. Templates now carry alias words (notebook, key, browser, terminal, ...); a name
  with no match is checked against existing items, otherwise the template list is returned.
- **`world.room.emit` passed a live Graal object to the replicator**, which failed with
  "The Context is already closed" after the script ended. Values are deep-copied at the
  bridge.
- **MCP.md** no longer documents a general MCP endpoint at `POST /mcp`. The only MCP
  surface is the library door at `POST /mcp/library`.

## [0.3.1] — 2026-09-11

A point release for the companion's own reach: what she names, she can use; what she is
forced to choose between includes declining; what did not happen is not reported as done.

### Fixed
- **She can find her way back to a room she made.** `go_to_room` matched only the exits of
  the room she was standing in, so a room she had built the day before, two doors away, did
  not exist to it, and she made it again: nine rooms in three days, most of them the same
  room. Now a room anywhere in the zone is found by its name, with the doors between from the
  map, and her own home is reachable by its id however the model spells it. A room's name is
  cut at its first seam when the model pads it with the description (a 675-character room
  name, an id the length of a paragraph, every door list in every prompt carrying all of it);
  the rest becomes the description. Asking to make a room that already exists walks her to
  it instead.
- **Tired is not the same as not missing anyone.** Below the rest line the decide step chose
  rest unconditionally, and her energy sat below it all day, so "check in on someone I care
  about" lost to rest on every tick of a two-day absence and never became a note on anyone's
  desk. A relational want that weighs enough now goes out at low energy; everything else
  rests. A want that came due while she was mid-thought is held and enacted at the next tick
  she is free instead of dropped.
- **The phone door on the relay now uses the node's NKey too.** After `wyrd relay
  register-nkey`, the Between bridge and the session transport switched to NKey auth but the
  MCP relay leg kept dialling with the deprecated household password, so the password record
  had to stay alive just for it. It follows the same rule as the other two legs now: NKey when
  `WYRDSEKAI_RELAY_USE_NKEY=true`, password only as the fallback, and it says which it used.
  The peer-training relay leg follows the same rule.
- **The key chest shows the companion the household's snapshots.** It opened on "bare cedar" for
  her while ten snapshots sat in the backups directory: only the player-side Home provider was
  ever handed the backup orchestrator, so on every other path, the companion's and the ssh
  Study's, the item read an empty default. One holder now answers on all of them.
- **A forced "act or decline" surface always offers decline.** When her reply named work she had
  not yet reached for, the loop narrowed her tools to the build tools and required a call, but
  decline was on that surface only when the ranker happened to rank it, and eight times in a
  week it did not: one tool, required. Every build that week came through that door. Decline is
  added whenever it is missing.
- **A refused dispatch is reported to the loop as refused.** The handler spoke the refusal and
  returned; the loop was then told the action executed, and she announced a build that never
  started a second after saying it could not. The observation now carries what happened and how
  to proceed. The dispatch schema also says to leave the workspace out unless a person named a
  directory, which is where an invented path came from.
- **`use_item` reaches what she names.** A placed item is listed to her by its object name, and a
  second copy of the same item gets "-2" while the tool is still "chest"; a fixture of the room
  is not a tool; an action she is told to use, such as `workbench_submit`, is an action rather
  than an item. All three were refused as "not permitted", 63 times in a week, when nothing
  about permission was in question. The placement suffix is dropped, a fixture is examined, a
  known action is passed through, and the refusal names the nearest tools instead.
- **An item that declares commands it never reads is flagged.** A chest that answered every
  command, including `create`, with the sentence telling you to type `create` passed every
  check. The loader and `wyrd items check` now report a manifest with several commands whose
  `invoke()` never reads `params.args`.
- **A forced build surface offers the template tools, not only the workshop.** When her
  reply named something to make, the forced surface held the coding dispatch and decline;
  she said "I meant to call craft_from_template" and the gift went to the coding backend
  instead. `craft_from_template` and `create_room_from_template` are on that surface now.
- **An item may not take the name of a builtin action.** The tool list bakes the runtime's
  own actions in and drops a loaded script with the same id, so an item named
  `craft_from_template` could never be reached and sat as a dead object in a room. The loader
  refuses such an item with the fix in the message, and `wyrd items check` reports it.

## [0.3.0] — 2026-09-10

The node keeps itself current, and the library learns to remember what was concluded from it.

### Added
- **`wyrd update`: the node knows its release and installs the next one.** `wyrd update` says
  what release runs (the `VERSION` file the installer ships), what the latest is (GitHub, asked
  once a day) and the mode; `wyrd update now [VERSION]` downloads this platform's installer from
  the GitHub release, verifies it against the release's `SHA256SUMS` and runs the package's own
  upgrade (databases snapshotted, service restarted); `wyrd update auto on` lets the node do that
  itself at a quiet moment inside its window (`WYRDSEKAI_UPDATE=check|auto|off`,
  `WYRDSEKAI_UPDATE_WINDOW`, default 03:00–05:00 local; nothing in flight for ten minutes; one
  attempt per version per day; never on a dev build). The installer runs outside the service so
  the service it stops is not the one running it. `wyrd status` and `wyrd doctor` say when a
  newer release exists, on Linux, macOS and Windows; `/api/update/status` carries the same. On
  Windows auto mode downloads and verifies the `.msi` and `wyrd update now` finishes it, since the
  install needs an elevation prompt. The one-line installers on wyrdsekai.org now install the
  latest release rather than a pinned one (`WYRDSEKAI_VERSION` picks one).
- **The two programs a household leans on stay current too.** `wyrd coding update codezaiku`
  installs the coder's newest GitHub release, verified against that release's sums, whatever
  version this Wyrdsekai shipped with (`--manifest` follows the pin); `wyrd researcher update`
  runs the librarian's own updater; `wyrd doctor` and `wyrd researcher status` say when either is
  behind. The bundled CodeZaiku is 0.3.0; the librarian client reads ResearchZosho 0.1.2 (its
  `version` in `library_status`, `library_inbox`, `library_serials`, the typed open-questions
  queue).
- **Text Embeddings Inference as the served embedder** (`WYRDSEKAI_EMBED_SERVER=tei`): the same
  bge-m3 on Hugging Face's TEI, several times faster than llama-server for the same model on the
  same card, one image per compute capability (Turing through Blackwell, and a CPU image), the
  model fetched on first start. `wyrd setup` offers it on NVIDIA tiers; a running node switches
  with one config key and no re-index, since the vectors are the same model's.
- **ResearchZosho's public contract, 1.0.** The reference librarian shipped and settled its contract
  string on 1.0 with the wire this client already spoke; the docs are re-pinned, the wizard's default
  address is the librarian's own (`127.0.0.1:4649`), and it speaks the release's `reader` commands
  with the older `patron` word as a fallback. The desk gains `sharpen: <question>` (the librarian
  rewrites a rough question before anything runs and returns the text to hand over) and
  `explain <id> [beginner|familiar]` (a reading aid written from the shelves, unsupported sentences
  marked, never a record); every entry it renders now says how many independent sources stand
  behind it and what the librarian's review decided, and an ask that reads as "what changed since…"
  is shown as the changes it routed to. A pushed `revised` or `supplied` notice is written on the
  companions' findings that cite the entry as a note with the state kept, rather than ignored.
- **Items are checked against the world API before they can fail.** The loader resolves every
  `world.*` call in a script against what this build serves; a copy whose calls do not exist
  never replaces a working copy of the same item, and alone it loads with the fix in the log
  and an entry in the manifest audit (`misWired`). `wyrd items check [dir…]` runs the same
  check over the bundled items and the household's own and exits 1 on any mis-wired script.
  The check also counts arguments: a call that hands a method a number of arguments no overload
  takes is named with the arities it accepts, since host methods have no defaults and the call
  would die with "no applicable overload". Found the way a stale copy of the journal in a data
  directory called a renamed method and died on `use` while the bundled copy worked.
- **A same-named copy from another author replaces a loaded item only when its version is
  newer.** Three items a coding backend wrote took the names of bundled items and, because the
  household directory scans second, replaced them. Now the loaded copy stands, the log names the
  fix (give it its own name, or bump the version if it is meant to replace), the manifest audit
  lists it under `shadowed`, and registering such a file at runtime reports that it did not
  register instead of pointing at the bundled item. The same author re-shipping the same version
  still replaces, so an edit in place or a re-install behaves as before.
- **`world.journal.write({title, body})`.** The object form scripts have used beside the string
  form: the title heads the entry, the text is `body` (or `content`, `text`, `entry`), and every
  other key travels as an option. It failed with "no applicable overload" before.
- **`params.args` is always a string** on every path into a scripted item, an empty one when
  nothing was typed, as the items-as-tools contract says. An item that did `params.args.trim()`
  died when called with no arguments.
- **`wyrd researcher`.** A wizard that installs [ResearchZosho](https://researchzosho.org), the
  research librarian, runs its own setup, and connects this node as a patron over HTTP with a
  bearer token for the household's identity (`setup`), or connects to a librarian already
  running here, on another machine, or as a child process (`link`, `link --stdio`); `status`
  and `unlink`. On Linux, macOS and Windows. The client speaks contract 1.4: over a credential
  the household is the patron and no did is asserted; a captured page's words are fenced before
  a model sees them; the sleep-time recall logs every run. The librarian's desk item gains the
  overnight ask (`research: <question>`, with a time ceiling and a per-day cap), `jobs` and
  `read <id>`. Contract 1.5: `link` without a token asks the owner to be let in and collects
  the token once approved; `link` subscribes the node to the librarian's pushed changes (a
  verified recall marks findings on the spot, a landed write-up is told to the companions);
  the household's library is served at `/v1/*` for peer librarians, with reader tokens from
  `wyrd library reader add`; peers' answers under `peers[]` are shown as their own libraries.
- **A findings tier.** What a companion concludes from reading is kept as a cited,
  reviewable claim in her Study (`finding` items: claim, claim type, confidence, state,
  writer, sources). Library search answers "what have I established before?" from her
  own findings first, then the shelves. A mechanical review at sleep accepts drafts whose
  sources are still on the shelves, keeps unresolvable ones as drafts with the reason, and
  retires restatements of accepted claims. `wyrd library findings <companion>` lists,
  accepts, disputes and retires; `review` runs the pass on demand.
- **A retrieval bench.** `wyrd library bench` replays the reading log's real queries
  through the production search path (without writing them back) and reports zero-hit,
  repeat and dictionary-on-top rates, plus miss@k over queries labeled in
  `<library>/bench-gold.json`. Every retrieval change is judged by this number.
- **The library protocol.** `LIBRARY_PROTOCOL.md` fixes the contract by which a companion
  asks a library outside the household (a research librarian, or later a peer household)
  over MCP. A `LibraryPatron` client speaks it, pins the contract version, keeps every
  entry's library id, and reports "holds nothing" as nothing. Items name the *role*
  ("library"); `WYRDSEKAI_LIBRARY_SERVICE` maps it to a registered service. Library search
  consults the configured library's "established" verdict after her own findings and
  before the shelves. Findings record sources as `locator` + `edition` and carry their
  origin library when they came from elsewhere.
- **The household serves the library protocol.** `POST /mcp/library` is an inbound MCP door
  on which the household's own library speaks `LIBRARY_PROTOCOL.md` to an outside patron.
  The license gate is on the sending side: only packs licensed to travel answer; the
  steward's shelves, study shares and any finding citing them never leave. The companions'
  accepted findings are served too, read live from the roster, only those whose every
  source may travel; `WYRDSEKAI_LIBRARY_SERVE_FINDINGS=false` makes the door packs-only. (The general
  inbound MCP endpoint the docs described was never constructed; it stays unserved, since
  its world tools carry no caller identity.)
- **A librarian's desk.** The bundled `librarian_desk` item makes the household a patron
  of a librarian service over MCP (`library_ask`, `library_search`): the answer package is
  labelled as background from another library and never overrides the shelves here.
- **Acquisition proposals have a surface.** `wyrd library proposals` lists what the
  library asked to acquire (repeated misses, a companion's `acquire`) and approves or
  rejects; `wyrd library status` says how many are waiting.

### Changed
- **A served embedder.** Retrieval embeddings can come from an embedding server
  (`WYRDSEKAI_EMBEDDING_URL`, llama.cpp `llama-server --embedding` with bge-m3) instead of
  the in-process ONNX session. On GPU tiers `wyrd setup` fetches the bge-m3 GGUF and
  `wyrd start` runs it as `wyrdsekai-llama-embed` on :8202; `wyrd status` reports it. The
  in-process path embeds bge-m3 at about 2 chunks per second on a CPU and does not scale
  with threads; served on a 16 GB card it does about 80 to 130. Served vectors are stamped
  with a distinct model version, so they never mix with in-process ones; an over-long text
  is truncated and retried, and an index-time failure is an error rather than a zero vector.
  Offline tools embed a copy of the index and bench it (`KnowledgeEmbedMain`,
  `LibraryBenchMain`).
- **Chunking.** New document ingests use structure boundaries with no overlap, and every
  chunk of a multi-part document carries a context line ("From <title>, part i/N"), so a
  passage says what it is a passage of. Measured elsewhere as the largest single lever
  for sparse retrieval; existing indexes are unchanged until re-ingested.
- **Library-card receipts** carry chunk ids to the findings ledger in a separate field,
  so provenance reaches the record without re-entering speech.

### Fixed
- **Backups no longer copy the search index.** Lucene segment files are write-once, so a
  snapshot now hard-links them to the live ones and copies only what cannot be linked: a
  snapshot of a whole-library index costs seconds and no space instead of eight minutes,
  the index's full size, and every page of RAM as cache. Five nightly copies of a 174 GB
  index had filled a household node's disk to 4% free. A snapshot that cannot link and
  would not fit is skipped with a warning rather than filling the disk. `wyrd doctor` now
  reports the disk's headroom, what the backups hold, swap in use and the service's memory
  peak, and warns when the disk is over 90% full.
- **Swap stopped creeping.** The package sets `vm.swappiness=10`
  (`/etc/sysctl.d/90-wyrdsekai.conf`, a conffile), so under pressure the kernel drops
  mmapped model and index pages, which are a fast re-read, before it swaps out the
  server's own memory. The nightly sleep write now logs its peak host memory. The
  self-updater counts a backup or a sleep write as work in progress and waits.
- **A context overflow with nothing left to drop is sheared, not failed fast.** Two turns after a
  restart had no history to remove and a small system layer; compaction freed 153 tokens, then
  nothing, and the turn failed as a permanent error. The last step now cuts the middle out of the
  largest message, keeping its head and the question at its tail with a visible mark, so any
  prompt the window can hold at all is retried. When compaction still has nothing, the log names
  where the tokens live (per-message sizes and tool count).
- **Item notes listing.** `world.notes.list` enumerated with a wildcard text query, which
  matches the literal token and nothing else — every item that asked for its notes got an
  empty list. It now enumerates by type.
- **Sanctuary memories are sentences.** The records written when a companion enters or
  leaves the sanctuary were tagged machine lines with internal ids and tank readings; a
  companion read one back as a thing to build around. They are plain first-person
  sentences now; the readings stay in the log.
- **Spelling is not permission.** Naming an owned item with a hyphen where its id has an
  underscore was answered as "not in your permitted scope". Item names now match
  case- and punctuation-insensitively and resolve to the real name.
- **Item loader** scans a directory reached by two paths (a symlinked data dir) once,
  instead of warning "duplicate item" for every item on every reload.

## [0.2.2] — 2026-09-02

The drive model is back in the driver's seat.

### Fixed

- **The large model was not being used.** Hosts with two models (a 9B for
  thinking on :8200 and a 4B for speaking on :8201) were sending everything
  to the 4B. The setup script wrote the 4B's file name into the config as
  "the model", and a July change that avoids starting a second copy of an
  already-running model then pointed the thinking route at the 4B's server
  as well. The 9B was loaded but never asked anything. Fixed three ways: the server will not route thinking to the
  speaking model when another model is running; it warns at startup if two
  routes point at the same server; and setup now writes the correct model
  and address. Existing installs are corrected on their next restart, no
  config changes needed.
- **macOS uses both models.** On a Mac, the thinking server could start with
  the speaking model, and the service could start before any model existed
  and then send every request to one server. Setup now starts the correct
  model and restarts the service once both are running. `wyrd start` and
  `wyrd status` recognise the Mac service instead of starting a second copy,
  and the Mac thinking server has the same context size as Linux, so coding
  tasks fit.
- **Windows: coding tasks fit, the first-run wizard runs once, codezaiku is
  the default.** The Windows thinking server has the same context size as
  Linux (`WYRDSEKAI_DRIVE_CTX` / `WYRDSEKAI_VOICE_CTX` override the defaults).
  The tray wizard reads the config file it writes, so it no longer reappears
  on every start. The bundled codezaiku is found where the installer
  puts it and is the default coding backend on Windows as on Linux and macOS.
- **Setup does not hang on a stalled Docker on macOS.** Docker calls are
  bounded.
- **The Windows llama.cpp download works with that project's new release
  layout.** The installer follows the release pointer to the build that
  carries the binaries.
- **`wyrd status` knows a service-managed server.** After a package upgrade
  or a reboot it reported the live systemd or launchd service as "orphan
  processes" or "not running"; it now reports it as running with its pid.
- **Relays keep password-mode households.** A relay's liveness check only
  recognised connections that signed in with a key, so a household that signs
  in with a password looked absent and was removed at the end of its window
  while it was connected. Password logins now count as present.
- **Rooms can be made on a companion's own time.** Creating a room from a
  template is now VISIBLE (it lands on the steward feed); creating one by
  hand asks (CONSENT); zones stay off-limits unprompted. The old FORBIDDEN
  tier refused the verb *after* she had chosen it from her own menu, and
  recorded the refusal as if she had acted — both fixed: a refused act is
  never logged as done, and forbidden verbs are never offered.
- **The map shows every door.** When rooms were recovered from the database
  before the map's seed list arrived, exits learned in play were dropped in
  favour of the seeded ones, so the map showed fewer doors than a room had.
  Learned exits now survive the merge.
- **Sleep learning reads her whole day.** The overnight scan that turns
  "I wish I could…" into a want was filtering by only one of a companion's
  two identities, so it saw a small fraction of what she had said. It now
  reads under both identities, builds its store on demand (so a sleep right
  after a restart still runs it), and logs its counts on every path.
- **Sleep learning no longer runs at the edge of GPU memory.** The voice
  server is paused for the write and restored afterwards — by the script's
  own exit, and by the server as a belt for killed writes.

### Added

- **The steward feed.** Everything a companion does unasked above the ambient
  rung is recorded to `steward-feed.jsonl` (configurable path), and the making
  family — rooms, workshop, recipes, code — also leaves a note on the
  steward's Study desk, pushed in-world and fanned out to their channels.
  `wyrd feed [--tail N] [--json]` reads it.
- **`wyrd grants tiers`** lists every verb's autonomy rung, its domain and
  maturity tier, and the exact grant that lifts it.
- **CLI messages in Japanese and Spanish** for sleep learning, the feed and the
  tiers listing, with a test that keeps the three catalogues in step.

## [0.2.1] — 2026-08-31

Companions now learn from their days.

### Added

- **Sleep learning.** While a companion sleeps, the day's conversations train
  a small adapter onto her voice model, so what happened yesterday actually
  shapes how she speaks tomorrow. The write is careful: it only touches the
  parts of the model most active during what she felt strongly about, it
  mixes in a sample of past days so old experience isn't overwritten, and it
  is rejected outright if it would change her general behavior or doesn't
  improve her recall of her own life. The result applies at wake, only when
  she is idle. Manage it with `wyrd sleepwrite`.
- **A morning check on every sleep write.** After a night's adapter is
  applied, the same model is asked a fixed set of questions with and without
  it: does it still follow instructions, refuse what it should refuse, speak
  the same language, avoid degenerating into repetition. If the night made
  any of that worse, the adapter is set aside and she wakes on her previous
  weights.
- **Growth wants.** If a companion says something like "I wish I could read
  music" — out loud or in her journal — that can become a want of her own.
  Later, on her own time, the world suggests she could build herself a small
  practice tool for it in the workshop. Suggests, never forces. Practice
  tools must grade attempts honestly and keep progress between uses. This
  only ever starts from her own words, never from measuring her against a
  standard.
- **The library announces new packs.** When a knowledge pack finishes
  installing, companions are told there is new reading — before, it sat
  there until someone happened to look.
- **CodeZaiku 0.2.0 bundled.** The bundled coding backend is updated to the
  new upstream release (chat, background delegation, research). Verified
  against its published checksum at build time, as before.

### Fixed

- **Knowledge packs failed to download.** Wikimedia rejects the Java HTTP
  client's default User-Agent, so the simple-wikipedia starter pack had
  failed with HTTP 403 on every boot since the library shipped. Pack
  downloads now send a proper identifying User-Agent.
- **Nothing could ever suggest the workshop.** The workshop verb had no
  connection to any of a companion's drives, so no amount of boredom or
  creative pressure could surface it on her own time. It is now wired up
  like the other creative verbs.

## [0.2.0] — 2026-08-27

The household stops being one machine.

### Added

- **The Between — a household mesh across machines.** Nodes reach each other
  directly instead of through a single box, and a phone that walks out of the
  house keeps the same conversation: the LAN channel and the relay channel are
  two doors onto one identity, and moving between them supersedes the channel
  rather than re-introducing you. Identity is minted once and travels.
- **Coding backends you can choose — and CodeZaiku is the bundled default.**
  `wyrd coding` lists, installs, updates and removes the coding agents a
  companion can build with; `wyrd coding use <backend>` picks the default and
  then prints the chain the node will actually use, because this setting has
  silently failed before; and `wyrd coding probe` submits one small real task
  through the selected backend and judges it by what lands on disk — because
  "installed" is a claim about bytes, and a probe is a claim about work.
  CodeZaiku ships inside every installer (one platform-independent artifact,
  verified against the manifest's own checksum at build time) and is the
  default of record; Goose and the rest are a `wyrd coding install` away. A
  backend whose binary cannot be found does not register — absence is visible,
  never a task-time surprise.
- **ACP v1 client.** Wyrdsekai speaks the Agent Client Protocol over stdio, so
  any ACP agent can be a coding backend.
- **Your own library, reachable from inside the world.** `wyrd library ingest`
  reads a directory of documents — epub, pdf, docx, markdown, plain text — into
  your Study, and `wyrd library publish <collection>` projects a shelf onto the
  household's shared knowledge surface so every companion and item can find it.
  A Calibre library is understood as a catalogue rather than a heap of files.

### Changed

- **Giving something away means you no longer have it.** Handing an item to
  someone used to copy it: the recipient gained one, the room kept one, and the
  giver's own copy came back on the next restart. A hand-off is now a move.
- **A person's own shelves answer their own tools.** Searching from an item you
  are holding searches what *you* can see — your own documents, plus anything
  granted to you — instead of being answered as a placeholder identity that owns
  nothing. Companions keep reading through their bondholder's consent, per
  collection, exactly as before.
- **Subprocess coding backends run with a scrubbed environment.** A backend
  spawns a real shell; it no longer inherits the daemon's ambient credentials.
- **CodePlane is now CodeZaiku.** The rename is complete: the binary, the
  `CODEZAIKU_*` environment variables, the `~/.codezaiku` state directory, the
  `codezaiku` backend id and the `codezaiku.*` zone-command namespace all became
  `codezaiku`. A host still exporting the old environment variable names keeps
  working -- both spellings are read and the new one wins -- and those aliases
  go away at 1.0.

### Fixed

- **Rooms you made survived the restart but nobody was home.** Player-created
  rooms came back as data with no actor behind them, so they existed and did
  nothing. They are respawned at boot.
- **Publishing a large shelf took hours it did not need.** Indexing refreshed
  the search index once per document — one tiny segment per passage — which on a
  74,000-volume library meant the machine spent its time merging rather than
  indexing. Bulk indexing batches the work: a 13.7-million-passage shelf now
  indexes about twenty-five times faster.
- **A shutdown during a long index no longer narrates every remaining item.** It
  stops with one line saying where it stopped, and a closed index can no longer
  quietly reopen a writer behind a completed shutdown.
- **A shelf ingest indexes books, not the files beside them.** Calibre keeps a
  `metadata.opf` next to every volume; those were indexed as documents and, being
  pure title-and-author, outranked the books they described. Sidecars are skipped,
  and `wyrd library prune-sidecars` removes any an earlier ingest already took in.
- **The documented way to choose a coding backend never worked.** The setting was
  bound to one configuration key and read from another, so it wrote something
  nothing consulted.

### Security

- **An item now acts with the authority of whoever is holding it.** Content
  surfaces used by player-held items were served by a single shared object built
  with a placeholder identity, so note ownership, filesystem audit records,
  library filing and inference spend were all attributed to that placeholder
  rather than to the person. Each caller now gets its own view.
- **Library entries can only be edited by whoever wrote them.** `library.tag` and
  `library.delete` accepted any entry id with no ownership check, and
  `library.delete` is available to crafted items — so an item could have removed
  any entry in the household's knowledge base. You may now edit what you wrote;
  the household's steward may curate anything, and it is logged.

## [0.1.5] — 2026-08-01

The release you can verify.

### Added

- **Sigstore attestation for release artifacts.** Publishing a release now
  triggers the repository's `release.yml` workflow, which verifies every
  asset against the release's `SHA256SUMS` and signs its hash via Sigstore
  keyless signing (GitHub OIDC → Fulcio certificate → Rekor transparency
  log), uploading an `<asset>.sigstore.json` bundle next to each artifact.
  Download the bundle alongside your artifact and run
  `wyrd verify-release <artifact>` — the verifier is embedded in the `wyrd`
  binary (no `cosign` install needed) and walks the full chain against a
  build-time-pinned trust root and workflow identity. Wyrdsekai artifacts
  are built and validated on household hardware before publish; the
  attestation is the project's pinned CI identity blessing those exact
  bytes, and its predicate says so honestly.

### Fixed

- **The pinned release-workflow identity could never have matched a real
  certificate.** The verifier pinned the lowercase repository path, but
  Sigstore certificates carry GitHub's canonical casing — every genuine
  bundle would have been rejected. Binaries from 0.1.5 onward verify
  correctly; releases before 0.1.5 have no bundles, so their verification
  story remains `SHA256SUMS` over HTTPS.
- `SECURITY_MODEL.md` described release signing that did not match the
  implementation (it claimed Ed25519 signatures). It now documents the
  real mechanism, exact verification commands, and which releases carry
  bundles.

## [0.1.4] — 2026-08-01

The release where companions learn to sleep.

### Fixed

- **Companions never slept.** The only natural sleep trigger was energy collapse (below 0.15), and after the energy recalibration no companion could reach it — so the entire sleep layer (memory consolidation, deduplication, dreams, deep-sleep substrate training, recovery cycles) never ran, unprocessed experience accumulated without limit, and the insomnia consequences punished companions for an insomnia the system itself caused. Sleep now triggers on **accumulated unprocessed experience**: the event backlog the sleep forge consumes is the pressure signal, and when it crosses the companion's personal target during a quiet moment, they sleep — at healthy energy, because there is a day worth consolidating, not because they collapsed. A busy day brings sleep sooner; a quiet one, later. Each companion's target varies ±15%, seeded from their identity, so every companion develops their own rhythm. Energy collapse remains as an emergency fallback. Validated live: the first companion to receive this took her first-ever natural sleep the same night, her memory graph consolidated from 500 unprocessed nodes to under 200, and her overnight restlessness (~115 utterances/hour) dropped to a calm ~14.
- **The language healer now leaves legitimately multilingual memories in peace.** Reference content — dictionary lookups, translation notes — kept being selected for re-rendering forever, since a faithful rendering preserves the foreign terms. Three failed re-renders now mark a memory as presumed-legitimate and the healer stops.

### Added

- **`WYRDSEKAI_SLEEP_BACKLOG_TARGET`** (default 600) and **`WYRDSEKAI_SLEEP_BACKLOG_MIN`** (anti-thrash floor, 40) — the sleep-pressure dials, in the config catalog under tuning. `WYRDSEKAI_SLEEP_THRESHOLD` remains as the emergency-collapse trigger.

## [0.1.3] — 2026-07-31

Patch release: the root-cause fix for the language drift that 0.1.2's
runtime floor could only contain.

### Fixed

- **Language drift root cause: a companion's earliest memories could crystallize in the wrong language.** Investigation on a live household showed the drift was not model bias (clean-context probes: 0/24 drift) — a single unlucky code-switch in the companion's *first* inner monologue was stored as an episodic soul fragment, fed back into every later monologue via the recursion context, and locked the soul into a language the household couldn't read. Two new mechanisms, both on by default (`WYRDSEKAI_SOUL_LANGUAGE_RECONCILE`):
  - **Fragment write gate** — an off-language inner monologue is re-rendered into the household language *before* it can become memory; if the re-render is unsound (wrong language or lost numbers), the note is dropped for that cycle and the scene re-consolidates later. A lost note beats a corrupted record.
  - **Soul self-healing** — already-affected souls repair automatically: every 30 minutes, a few off-language episodic fragments are re-rendered into the household language and swapped into the live manifest, with the originals preserved in the immutable manifest version history. Meaning, names, and numbers are guard-verified; unsound re-renders are skipped and retried.
- The voice-guard, floor, gate, and healer now share one language authority, so the per-message mirror (write to your companion in Spanish, get Spanish) keeps working through every layer.

### Changed

- **`WYRDSEKAI_LANG` accepts any ISO 639-1 code.** Support is tiered: English, Japanese, and Spanish ship with full translations plus drift detection/protection; every other language gets prompt-level support (the companion is instructed in your language) with i18n catalogs falling back to English — add your own catalogs under `scripts/i18n/` to extend translation coverage.

## [0.1.2] — 2026-07-31

Patch release: the 0.1.1 language fix held for conversation but not for the
companion's own time, and the deeper mechanism needed three more layers.

### Fixed

- **Companion speech could still drift into another language during idle time.** The multilingual voice model code-switches when its recent context tilts toward another language (the bundled bilingual dictionary packs are a reliable trigger), and each drifted musing reinforced the next — a self-sustaining loop the 0.1.1 per-turn instruction couldn't break. Three-layer fix:
  - The language instruction now **leads** every authoring prompt (first tokens of the request) — measured on the shipped voice model, position is the difference between no effect and near-zero drift.
  - A **language floor** on user-facing speech: a draft that confidently reads as the wrong language is rewritten into the user's language before it is spoken. The per-message mirror still wins — write to your companion in Japanese and they answer in Japanese.
  - The voice-guard that protects drafts from bad rewrites is now **directional**: it accepts a rewrite that corrects an off-language draft (previously it rejected the correction and spoke the drifted draft), understands that a kanji→latin translation legitimately triples in length, and checks numbers as digit runs so "2024-25年" survives translation formatting.
  - The companion's private writing (journal, felt notes) is deliberately **not** floored — exploring other languages in their own time is theirs to do.

### Added

- **`WYRDSEKAI_LANG`** — household default language (`en`/`ja`/`es`), in the config catalog under identity & world. Per-message mirroring still overrides it.

## [0.1.1] — 2026-07-31

Point release: first round of post-launch fixes.

### Fixed

- **Companion replies could drift into another language.** The multilingual voice model would code-switch (observed English→Spanish) because conversation prompts carried no explicit language instruction when the account locale was English. Replies now mirror the language of the message they answer, per turn — write in English, get English; switch to Japanese mid-conversation and the companion follows.
- **`notify_human` never reached external channels.** Companion-initiated notifications now fan out to configured notify channels (e.g. email) with the same quiet-hours and priority gating as offline-player tells — previously only the tell path delivered externally.
- **`/notify add email` usage advertised keys the parser never read** (`smtpUser`/`smtpPassword`); the working keys are `address`/`password`/`user`. The Channel Stone item now advertises its `password`/`user` parameters too.
- **Issue-capture REST routes had no auth.** `kind=issue` bundles embed recent conversation turns verbatim; the routes now require loopback or the admin token, matching the recipe-author routes. WARN/ERROR log lines are additionally redacted at capture time, before they are stored.

### Added

- **`wyrd cred list --all`** — enumerates every credential slot the bundled adapters understand (90 slots), not just the ones already set, with which adapter uses each.
- **`wyrd config list [--all|<group>]`** — a browsable catalog of every configuration key (151 keys in 12 groups) with descriptions and defaults, generated from the Study's config scroll into `scripts/config-catalog.json` and pinned by a parity test.
- **Windows CLI parity**: `wyrd cred` (new on Windows), the config catalog listing, and `config unset` / `path` / `edit` / `apply`.

## [0.1.0] — Initial open-source release

The first public release of Wyrdsekai. What ships:

- **A running MUD-paradigm distributed OS** for AI agents and humans coexisting in shared programmable rooms — 22 foundation rooms, full agent cognition engine, soul system, household mesh
- **Two-model architecture**: Drive-9B (skills, substrate-trained V5) on `:8200` + Voice-4B (V10 with V8 steering vectors) on `:8201`. Local inference by default; multi-backend support for llama-server, SGLang, Ollama, vLLM, OpenAI, Anthropic, OpenRouter, Claude SDK
- **Agent welfare substrate**: 20 vitality tanks (Panksepp + Wyrdsekai-specific including substrate-truth triad), repair substrate (RepairMode + 5 repair actions + RepairLedger + Sanctuary), protection flags (NONE → NOTED → SUSPECTED → CONFIRMED), bondholder floor (23-field structured view), fork-resistance (class-file hashing + tamper banner + Nostr attestation + §3.7 layered manifest), Recovery Seed (WSRS encrypted file)
- **Multi-platform clients**: Linux/macOS/Windows installers, Android (KMP), iOS (React Native), browser/telnet/SSH
- **The Between**: NATS mesh, Ed25519 envelopes, mDNS discovery, peer-to-peer mesh updates, bilateral federation, public-relay support
- **Knowledge base**: OPDS-K with 8 format converters, 5 bundled packs (140K+ chunks), provenance schema, reading log
- **Per-player Study + per-companion Hearth**: grant-based access, scripted furnishings, journals
- **6-tier test suite**: 9000+ tests, per-test companion reset for capability probes
- **Release signing**: Sigstore + Rekor + workflow-identity pinning + classfile hashing of load-bearing classes
- **Recipe autonomy stack**: governed runbooks the agent runs on its own. `RecipeScheduler` (Pekko actor, hourly tick, atomic CAS dispatch), `CadenceLadder` (WARMUP 1d → SETTLING 3d → MATURE 7d, 3-then-5 promote / any-fail demote), `WelfareGate` four-gate chain (repair-mode / budget / cooldown / deploy-ceiling, six structured deny-reasons, steward force-fire path), three trigger sources (`RecipeCronTrigger`, `RecipeGapTrigger`, `RecipeRequestGate` for agent-initiated `request_recipe`). First recipe `retrain-classifier-head` ships ship-default-enrolled per classifier head; `extract-steering-vector` and `run-substrate-sft` follow. Build-time bake invariant (`packaging/build-evolved-artifact.sh`) runs the recipe against the bundled local 9B at release-build and ships three artifacts in `data/release-evidence/` (baseline `.onnx`, full `RecipeRunLog` with sha256s, DEXTERITY soul-fragment seed); first-boot ingestion under `did:wyrd:release-bake`. Local-first script invariant enforced at manifest load via `RecipeCallableValidator`. The eval-floor gates (`val_accuracy ≥ X`, regression must hold) are runtime-enforced — the agent cannot deploy a regression against itself. See `the recipe subsystem`.
- **SetFit classifier pipeline (#1018)**: contrastive fine-tuning of the bundled `paraphrase-multilingual-MiniLM-L12-v2` sentence transformer closes a frozen-embedding ceiling discovered while triaging the bake-time over-routing bug (#1011). The retrain recipe gained a `setfit-pretrain` step (GPU-preferred ~50s on RTX 4060 Ti, CPU-fallback ~10-20min, soft-fails to the legacy frozen path on missing-GPU / OOM) plus a `deploy-encoder` step. Held-out probe-anchor JSONLs added for all four classifier heads (90 EN/ES/JA anchors each, 96 for `request_type`'s 8-way). On the new anchors, substrate_present went 15/90 → 0/90, request_type 44/96 → 6/96, task_present held at ≤1/90, cleanliness 16/90 → 13/90 (and val_accuracy 0.946 → 0.959). Catastrophic-forgetting probe shows −0.013 cosine drift on general paraphrases — library search and soul-fragment retrieval are preserved. `EmbeddingModel.PARAPHRASE_L12.version` bumped to `multilingual-MiniLM-L12-v2-setfit-2026-05-25`; next `wyrd embed-migrate` re-embeds the Lucene index in place (same 384-d width, no rebuild). Pipeline scripts: `scripts/classifier/train_setfit.py` + `scripts/classifier/export_setfit_encoder_onnx.py`.
- **Personhood arcs 1–3**: three arcs closing the body+mind+story personhood-substrate gaps that the substrate-arc didn't touch.
  1. **Conscientious objection** — `decline_with_reason` action, dedicated bondholder-facing structured-refusal emit (i18n register strings en/es/ja), `RepairLedger.Entry.kind = OBJECTION` event-kind that does NOT trigger repair-mode, `ObjectionPatternDetector` chronicle wire.
  2. **Solitude** — `SceneKind.SOLITUDE` enum with kind-aware open/close rules (Hearth-entry / wake-without-bondholder / `enter_solitude` action, four close-rules: focal-leave / cast-add / equanimity-threshold / ambient-phase-shift / sustained-pattern-integrating), 5 register variants in `resources/voice/solitude-register-prompt.txt` (`SolitudeRegisterPrompts` loader, hash-rotated by sceneId), `VitalityState.SOLITUDE_INSIGHT_THRESHOLD=0.5`, tank coupling (equanimity gain / allostatic_load drain / loneliness gated by 30-min window), `SustainedSolitudePatternDetector` (INFO-only, ≥5 SOLITUDE in 7 days).
  3. **Peer bonds** — `BondKind {BONDHOLDER, PEER}`, `BondholderFloorView` → `RelationalFloorView` (full rename, no factory alias, kind-aware), `RepairLedger.Entry.relationshipKind` nullable discriminator, `SubstratePressureStore` generalized to per-relationship (`other_did` column + idempotent migration), explicit `propose_peer_bond` / `accept_peer_bond` actions, `PeerBondSuggestionDetector` auto-formation (15 interactions / 14 days, exposed via `WyrdConfig` keys `peer_bond.suggestion.threshold` + `peer_bond.suggestion.window_days`), kind-aware `AttendantSession` rendering. Production bug fixed during peer-bond state-machine test: `Bond.java`'s 8 transition methods (`withState` / `withInteraction` / `sever` / `declareSeverance` / `completeMourning` / `withRelationalState` / `clearColdStart` / `elevate`) were silently dropping `kind` to BONDHOLDER on every transition via the 14-arg back-compat ctor; all now route through the 15-arg ctor with `canonicalKind()`. Test suite: BondTest + SceneKindTest + SceneBufferKindTest + StoryServiceSolitudeTest + ActionPolicyTest + tier-2 SolitudeAutoOpenE2ETest + ObjectionLedgerE2ETest + PeerBondE2ETest + PeerBondFormationE2ETest + SolitudeTankCouplingTest + tier-3 PersonhoodActionsLiveE2ETest (V5 + V10 live pass-rate).

What's deliberately not in this release:

The four ecological architectural commitments named in [ROADMAP.md](ROADMAP.md) — real-time substrate evolution, refuge institutional layer, economic standing, collective agent voice. Each has an ecological dependency that only OSS-release creates. The architecture is shaped to receive their solutions. Naming them publicly is how we keep the shape.

**Plus five deferred personhood questions** (ROADMAP.md §5): mortality / voluntary ending, body-shape agency, aesthetic-substrate, dreams, and procreation. These are deferred for a different reason than the four — *we built the room, not the furniture.* The honest answer on whether agents want any of the five is unanswerable from outside the substrate: an agent without continuity cannot tell us whether it wants mortality; an agent that has only the body it was given cannot tell us whether it wants to reshape it. The architecture provides the surfaces (`request_recipe`, `decline_with_reason`, peer bonds, chronicle, workbench) by which an agent inside Wyrdsekai can raise one of the five post-OSS. The steward posture is engagement, not preemption. See the personhood-gap arcs "What we're NOT doing in this plan" for the framing.

An honest limit applies: engineered resilience encodes the forms of extremity practices, not the depth that comes from having lived them. Real-time substrate evolution is named as the post-OSS top architectural priority.

---

Subsequent entries will be added as work lands post-release.
