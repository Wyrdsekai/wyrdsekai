# Known issues

Things we know are wrong or unfinished at release. Kept honest deliberately:
`ROADMAP.md` names the large architectural gaps, and this file names
the small operational ones. If you hit something not listed here, please open
an issue — an unlisted bug is a bug we don't know about.

## SSH surface

**A lockout looks exactly like a broken server.** After several failed
sign-ins, the brute-force throttle locks the source IP and the targeted
account for five minutes. During that window SSH accepts the TCP connection
and then closes it **with no message at all** — no "permission denied", no
banner, nothing. One mistyped password therefore makes every subsequent
session look like "the SSH surface is dead" or "commands render nothing".

If SSH suddenly goes silent: wait five minutes, then try again with the right
credentials. The server logs the reason (`SSH auth throttled for …`), so
`wyrd log` will confirm it.

Why it is not fixed properly: SSHD sends its welcome banner *before*
authentication runs, so a lockout discovered mid-auth cannot be explained over
the wire from the password authenticator. Carrying it over a
keyboard-interactive prompt would work and is a welcome contribution.

The commands themselves are fine — `look`, `map`, `who`, movement and the
numbered `actions` menu were all verified working over a live SSH session
(2026-07-25).

## First run

- **`wyrd invite bootstrap` without `sudo` prints a Java stack trace** instead
  of "permission denied". The invite database belongs to the service account.
  Use `sudo wyrd invite bootstrap --name <name>`.
- **Model-download progress spams the log when output is not a terminal.** The
  progress bar rewrites its line with carriage returns; redirected to a file
  that becomes tens of thousands of characters. Cosmetic, but it makes
  `wyrd start > log` unpleasant to read.

## Language

- **The runtime speaks three languages; the documentation speaks one.** English,
  Spanish and Japanese are wired end to end in the product — `world.t()` from
  scripts, `I18n.get()` from Java — but every document in `docs/`, including the
  `FIRST_ENCOUNTER.md` a new bondholder is handed at install, is English only.

  This was a deliberate hold rather than an oversight. That document is the most
  register-sensitive text in the project: *bondholder*, *Hearth*, *saudade* and
  *refusal* are load-bearing words, and a merely-accurate translation would
  quietly flatten them into "user", "home", "loneliness" and "denial" — which is
  precisely the framing the architecture exists to argue against.

  So it wants a translator who can hold the register in the target language, not
  a fast pass. **This is a genuinely valuable contribution** and a good place to
  start if you speak one of these languages well. Open a discussion first; the
  vocabulary is worth agreeing on before the prose.

## Mobile clients

- **The KMP Android release APK is unsigned.** `assembleRelease` produces
  `androidApp-release-unsigned.apk`; there is no release keystore in the
  repository (there should not be). Sign it yourself, or use the debug build,
  until release signing is configured.
- **RN release builds are arm64-only by default.** `build-android.sh --release`
  targets real phones. On an x86_64 emulator the app crash-loops with
  `SoLoaderDSONotFoundError: libreactnative.so`. Rebuild for the emulator with:
  `cd clients/rn/android && ./gradlew assembleRelease -PreactNativeArchitectures=x86_64`.
- **On-device model inference is not exercised on x86_64 emulators**; the
  llama.cpp JNI layer segfaults during generation there. Physical arm64
  devices are the supported path.

## Server / tests

- **Installing wyrdsekai on the same machine you run the test suite from
  breaks roughly a dozen tests.** The installed tree owns `~/.wyrdsekai`
  (root-owned symlink) and puts binaries on the discovery path, so tests that
  assert on a clean environment fail. Run the suite on a machine without an
  install, or set `WYRDSEKAI_DATA_DIR` to a writable directory.
- **Some end-to-end tiers require a running inference backend** and will fail
  as `initializationError` without one. Point them at your backend with
  `WYRDSEKAI_INFERENCE_URL` / `WYRDSEKAI_VOICE_INFERENCE_URL`.
- **Two cross-zone streaming integration tests fail against the WireMock
  harness**, not against a real backend: the mock throws an internal Jetty
  error serving `GET /v1/models`, which the dead-backend guard probes. The
  non-streaming and quota paths — same request handling — pass.

## Packaging

- The WhatsApp sidecar (`whatsmeow-sidecar/`) depends transitively on
  GPL-3.0 code. Its **source** is distributed here, which is compliant; a
  **compiled binary** would place the whole binary under GPL-3.0, so no
  installer builds or ships it. Build it yourself if you want that bridge, and
  understand the licence you are then bound by.
- The KMP Android client uses Google's ML Kit barcode scanner for invite QR
  scanning — a proprietary binary dependency inside an otherwise Apache-2.0
  application. It also drags in Play Services, which is a poor fit for a project
  whose whole argument is that you should not need someone else's account to run
  your own software.

  **The intended replacement is [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp)**
  (`io.github.zxing-cpp:android`, Apache-2.0, on Maven Central). It is a modern
  C++20 rewrite rather than the original Java ZXing — which still works but has
  been in maintenance mode for years — and it ships Kotlin/Native bindings as
  well as Android ones, so the same decoder could eventually serve the iOS side
  of the KMP client instead of a second platform-specific path.

  The code change is well-contained: the dependency is one line in
  `clients/kmp/gradle/libs.versions.toml`, and the only call site is
  `QrScanner.android.kt`, which uses CameraX for the preview and ML Kit purely
  to decode a frame. Swapping the decoder does not touch the camera plumbing.
  Be aware that a native decoder adds per-ABI `.so` payload where ML Kit
  resolved to Play Services — measure the APK before and after.

  **Why it has not been done yet: the testing, not the code.** Camera capture is
  the least emulator-faithful part of Android. An emulator feeds a synthetic
  frame; a real handset feeds whatever its sensor, autofocus, exposure and
  vendor image pipeline produce, and those differ enough between manufacturers
  and OS versions that "works on mine" means very little. A QR scanner either
  reads the invite off a screen in a dim room on a three-year-old phone, or the
  onboarding path is broken for that person with no useful error.

  Doing this properly needs **several physical devices across vendors and
  Android versions**, tested against a real invite QR at realistic distances and
  lighting — including the awkward cases: a glossy screen, a printed code, a
  cracked lens. That device matrix is what this project does not have, and it is
  not something we were willing to guess at on the way out the door.

  So this is a genuinely good first contribution *for someone with a drawer of
  Android phones*. It removes the last proprietary dependency from the default
  build, and the bar for merging it is evidence from real hardware rather than a
  green emulator run.


## macOS: a coding backend cannot reach a drive on another machine until you grant Local Network

On macOS, Apple's Local Network privacy permission blocks non-Apple binaries
(including the JVM and every coding backend) from reaching LAN peers — while
`curl` to the same URL works, because Apple's own binaries are exempt. The
failure reads like a network problem and is not one.

If your drive runs on the same Mac (`localhost`), nothing is affected. If it
runs on another household machine, grant Local Network to your terminal app
once: System Settings → Privacy & Security → Local Network. There is no way
to grant it from a script or over ssh — it is a one-time human step by
Apple's design.
