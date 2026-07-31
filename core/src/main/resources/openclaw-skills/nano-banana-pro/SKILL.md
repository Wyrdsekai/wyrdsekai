---
name: nano-banana-pro
description: Generate and edit images with Google's image model.
version: "1.0"
metadata:
  openclaw:
    requires:
      env: [GEMINI_API_KEY]
  wyrdsekai:
    room: atelier
---
Use the household's configured Gemini key (env GEMINI_API_KEY) to generate or edit an image via the Google image-generation API. Each generation costs money - keep to one image per request unless asked for more.
