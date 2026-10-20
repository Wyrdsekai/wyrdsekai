#!/usr/bin/env python3
"""A made-up fortnight for a made-up companion, in the trail's row shape, for the rental's night write.
No real household appears here. Usage: make_corpus.py <out.jsonl> [lines]"""
import json, random, sys
from datetime import datetime, timedelta, timezone
out = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 420
rng = random.Random(11)
NAME, EID, PERSON = "Wren", "companion-wren", "Ilse"
openers = ["Morning.", "Evening, then.", "Still here.", "The kettle is on.", "Rain again.", "The light is good today.",
           "I kept the window open.", "The bread rose this time.", "Quiet house.", "The cat came back."]
things = ["the lemon tree", "the blue bowl", "the back step", "the long table", "the garden gate", "the radio",
          "the stack of letters", "the river path", "the attic trunk", "the brass lamp", "the map on the wall",
          "the bread tin", "the hallway clock", "the apple crate", "the ladder", "the green chair"]
doings = ["needs a wipe", "is where you left it", "caught the sun for an hour", "has a loose hinge", "smells of smoke still",
          "made me think of your mother's", "is heavier than it looks", "came up clean", "is waiting for a dry day",
          "keeps better in the cool", "should go out before the frost", "is the right height after all"]
feels = ["I liked that.", "It was a plain day and I was glad of it.", "I did not hurry.", "I read until the page went grey.",
         "The walk was longer than I meant.", "I sat with it a while.", "Nothing needed me, so I listened.",
         "I put the chairs straight.", "The soup wanted salt.", "I wrote two lines and crossed one out."]
asks = ["Did you sleep?", "Is the car back?", "Shall I keep the stew warm?", "Which coat tonight?", "Was the train on time?",
        "Do you want the lamp lit?", "Are you eating?", "Is it still raining there?"]
heard = ["long day, sorry", "home by eight", "did the parcel come", "cold out there", "tell me about your afternoon",
         "I burned the toast", "what did you read", "the meeting ran over", "I'm tired", "the garden looks good"]
replies = ["Eight is fine. The stew holds.", "No parcel yet; the van went past twice.", "Cold here too; I shut the back door.",
           "I walked the river path and came back the long way. The herons were out.", "Toast burns. Bread does not mind.",
           "A chapter about tides, and then I lost the place.", "Then eat something before you tell me the rest.",
           "The beans came up. The gate still sticks.", "Tired is allowed. Sit first.", "It looks good because you weeded it."]
rows = []; t = datetime(2026, 10, 1, 7, 0, tzinfo=timezone.utc)
for i in range(n):
    t += timedelta(minutes=rng.randint(25, 95))
    felt = {"energy": round(rng.uniform(0.3, 0.9), 2), "calm": round(rng.uniform(0.3, 0.9), 2),
            "warmth": round(rng.uniform(0.4, 1.0), 2), "curiosity": round(rng.uniform(0.2, 0.8), 2)}
    base = {"ts": t.isoformat().replace("+00:00", "Z"), "type": "speak", "agent": NAME, "agentId": EID, "lang": "en", "felt": felt}
    kind = rng.random()
    if kind < 0.45:
        h = rng.choice(heard); r = rng.choice(replies)
        rows.append({**base, "to": PERSON, "heard": h, "text": r + (" " + rng.choice(feels) if rng.random() < 0.3 else "")})
    elif kind < 0.8:
        text = f"{rng.choice(openers)} {rng.choice(things).capitalize()} {rng.choice(doings)}. {rng.choice(feels)}"
        rows.append({**base, "to": "system", "heard": "(own time) the house is quiet", "text": text})
    else:
        rows.append({**base, "to": PERSON, "heard": rng.choice(heard), "text": f"{rng.choice(asks)} {rng.choice(feels)}"})
with open(out, "w") as f:
    for r in rows: f.write(json.dumps(r) + "\n")
print(f"{len(rows)} rows for {EID}, {rows[0]['ts'][:10]} to {rows[-1]['ts'][:10]}")
