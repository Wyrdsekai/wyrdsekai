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
- Cryptographic implementation (Ed25519, AES-256-GCM)
- The mesh update protocol (package verification, signing)

## Known Security Architecture

- **Ed25519** for all signatures (node identity, Between messages, release manifests)
- **AES-256-GCM** for soul encryption (TheSafe)
- **Crypto is JDK-native except password hashing**, which uses a
  well-reviewed bcrypt library rather than a hand-rolled KDF
- **OWASP Top 10 Agentic** — addressed in safety checklist (SPEC files)
- **Agent consent model** — companions must be granted access per-collection
- **Private journal** — never visible to companion agents

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Previous release | Security fixes only |
| Older | No |
