# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Wyrdsekai, please report it responsibly.

**Do NOT open a public issue.**

Instead, email: **wyrd@wyrdsekai.org**

We will acknowledge receipt within **3 business days** and give you an initial
assessment within **14 days**. If a fix is warranted we aim to release it and
publish an advisory within **90 days** of the report — the usual disclosure
window — and sooner when an issue is being actively exploited. If we need
longer, we will tell you why rather than let the deadline pass in silence.

## Credit

Unless you ask us not to, we will name you in the advisory and the release
notes for the fix. Tell us how you would like to be credited (name, handle,
affiliation, a link); if you say nothing we will use the name you reported
under. We do not run a bug bounty and cannot offer payment — credit and a
straight answer are what we have.

We will not pursue or support legal action against anyone who reports in good
faith, stays within the scope below, avoids privacy violations and service
disruption, and gives us reasonable time to respond before disclosing.

## Scope

This policy covers:
- The Wyrdsekai server (`server/`, `core/`, `between/`)
- The wire protocol (WebSocket, Telnet, SSH, Between/NATS)
- Authentication and authorization (AuthService, WardService)
- Agent safety systems (ModerationService, SanctionEnforcer)
- Cryptographic implementation (Ed25519, X25519, ChaCha20-Poly1305, AES-256-GCM)
- The relay and the encrypted links through it

## Known Security Architecture

- **Ed25519** signs each home's identity and the messages homes send each other.
- **Releases** are signed with Sigstore. `wyrd verify-release` checks a download
  against the project's release workflow and refuses anything else.
- **X25519 + ChaCha20-Poly1305** seal a phone's connection and requests end to end
  to its home, so the relay passes them on without being able to read them.
- **AES-256-GCM** encrypts the credential safe and the vault's backups. A
  companion's soul record is stored unencrypted on the home's own disk.
- **Crypto is JDK-native except password hashing**, which uses a
  well-reviewed bcrypt library rather than a hand-rolled KDF
- **Every /api route needs a login** unless it carries its own proof (a password,
  a pairing code, a signed webhook). Each person reaches only their own Study and
  journal.
- **Agent consent model** — companions must be granted access per-collection
- **Private journal** — never visible to companion agents
- **Each person's words** — what one person tells a companion privately is not
  read into another person's conversation (see SECURITY_MODEL.md, "Each person's
  words and data", for what is and is not covered)

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Previous release | Security fixes only |
| Older | No |
