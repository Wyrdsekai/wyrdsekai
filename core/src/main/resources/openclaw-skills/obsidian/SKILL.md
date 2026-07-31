---
name: obsidian
description: Read and write notes in an Obsidian vault.
version: "1.0"
metadata:
  openclaw:
    requires:
      bins: [obsidian]
  wyrdsekai:
    room: vault
---
Run the CLI below to do the work. Start with `--help` if you are unsure of the exact flags, then compose ONE precise command. Report the actual output honestly; if the command fails, say what failed instead of narrating success.

Binary: `obsidian`. Note: the native vault.obsidian skill (configured via wyrdsekai.skills.obsidian.vault_path) is preferred when available. Never delete or rewrite existing notes wholesale - append, or create new notes.
