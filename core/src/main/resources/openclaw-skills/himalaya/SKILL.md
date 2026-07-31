---
name: himalaya
description: Read and send email from the command line (IMAP/SMTP).
version: "1.0"
metadata:
  openclaw:
    requires:
      bins: [himalaya]
  wyrdsekai:
    room: heralds-hall
---
Run the CLI below to do the work. Start with `--help` if you are unsure of the exact flags, then compose ONE precise command. Report the actual output honestly; if the command fails, say what failed instead of narrating success.

Binary: `himalaya`. `himalaya envelope list` shows the inbox; `himalaya message read <id>` reads one; `himalaya message send` composes. Sending email is outward-facing speech in the person's name - confirm recipient and content before sending, and never send bulk mail.
