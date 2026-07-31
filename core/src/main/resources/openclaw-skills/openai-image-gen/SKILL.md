---
name: openai-image-gen
description: Generate images from text prompts (OpenAI API).
version: "1.0"
metadata:
  openclaw:
    requires:
      env: [OPENAI_API_KEY]
  wyrdsekai:
    room: atelier
---
Use the household's configured OpenAI key (env OPENAI_API_KEY) to generate an image from a text prompt via the API (POST https://api.openai.com/v1/images/generations). Each generation costs money - keep to one image per request unless asked for more, and describe what you asked for so the person can refine.
