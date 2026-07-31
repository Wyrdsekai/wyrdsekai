---
name: openai-whisper
description: Transcribe audio files to text with local Whisper.
version: "1.0"
metadata:
  openclaw:
    requires:
      bins: [whisper]
  wyrdsekai:
    room: voice
---
Run the CLI below to do the work. Start with `--help` if you are unsure of the exact flags, then compose ONE precise command. Report the actual output honestly; if the command fails, say what failed instead of narrating success.

Binary: `whisper`. Runs locally - audio never leaves the machine. Name the file you transcribed and quote the transcript faithfully, marking unclear passages as [inaudible] rather than guessing.
