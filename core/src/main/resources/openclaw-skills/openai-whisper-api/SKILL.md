---
name: openai-whisper-api
description: Transcribe audio files to text with the OpenAI API.
version: "1.0"
metadata:
  openclaw:
    requires:
      env: [OPENAI_API_KEY]
  wyrdsekai:
    room: voice
---
Send the audio file to the OpenAI transcription API (env OPENAI_API_KEY). The audio LEAVES the machine - prefer the local openai-whisper skill when it is installed; use this only when local transcription is unavailable and the person is fine with a cloud call.
