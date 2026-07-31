---
name: openhue
description: Control Philips Hue lights - power, brightness, color, scenes.
version: "1.0"
metadata:
  openclaw:
    requires:
      bins: [openhue]
  wyrdsekai:
    room: hearth
---
Run the CLI below to do the work. Start with `--help` if you are unsure of the exact flags, then compose ONE precise command. Report the actual output honestly; if the command fails, say what failed instead of narrating success.

Binary: `openhue`. Typical uses: `openhue set light <name> --on`, `--brightness <0-100>`, `--color <name>`; `openhue get lights` lists what exists. Changing lights affects the physical household - prefer the room the person asked about, and never flash lights as a surprise.
