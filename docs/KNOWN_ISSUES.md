# Known issues

This page lists problems we know about in this release. For each one it says what you will notice, who it affects and what to do about it.

It covers the small, practical problems. The larger gaps in the design are in the [ROADMAP](../ROADMAP.md). If you hit a problem that is not listed here, please open an issue. A bug that is not listed is a bug we do not know about.

## SSH goes silent after wrong passwords

SSH is one way to reach your Wyrdsekai world from a terminal.

**What you notice.** You connect over SSH and nothing happens. The connection opens and closes at once, with no message at all. It looks as if the server is broken, or as if commands show nothing.

**Who it affects.** Anyone who signs in over SSH.

**Why.** After five failed sign-ins within a minute, Wyrdsekai locks out that address and that account for five minutes. This protects against people guessing passwords. The count for an account is shared: failed sign-ins through the web login, MCP or telnet count too. During the lockout, the server closes each SSH connection without saying why.

**What to do.** Wait five minutes, then sign in with the right password. `wyrd log` shows the reason: look for `SSH auth throttled`.

The commands themselves work. `look`, `map`, `who`, moving around and the numbered `actions` menu were all checked over a live SSH session.

## `wyrd invite bootstrap` shows a Java error without `sudo`

**What you notice.** A long Java error message, called a stack trace, instead of a plain "permission denied".

**Who it affects.** On a packaged install, anyone who runs `wyrd invite bootstrap` without `sudo`. This command makes the one-time code for a new install's first account, the steward. The steward is the person who looks after the household's Wyrdsekai.

**Why.** The invite database belongs to the account the Wyrdsekai service runs as, so your own account cannot open it.

**What to do.** Run it with `sudo`:

```bash
sudo wyrd invite bootstrap --name <name>
```

## After the 0.5.0 upgrade, old phone apps and machines cannot reach the home on the network

**What you notice.** A phone app from before 0.5.0 cannot connect to your home while it is on your home network. A second machine in your household stops talking to the first one.

**Who it affects.** Households with a phone app from before 0.5.0, or with more than one machine.

**Why.** Since 0.5.0 the home's own ports are encrypted and the household bus needs a login for every machine and phone (see [INSTALLATION.md](INSTALLATION.md#upgrading-to-050-encryption-at-home)). Old apps and old machines know neither. Through a relay, an old app fails too: the home refuses requests that are not sealed to its key.

**What to do.** Update the app and pair the phone again. On each other machine, run `wyrd join` again with the line `wyrd household key` prints on the hub. While you do this, `WYRDSEKAI_HTTP_LAN_PLAINTEXT=true` and `WYRDSEKAI_NATS_LAN_PLAINTEXT=true` on the home let old apps and machines in as before, unencrypted, and `WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT=true` with `WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS=true` let old apps through a relay. Remove them when you are done: `wyrd doctor` warns while they are on.

## Phones no longer talk to each other over the household bus

**What you notice.** On a phone, the list of who else in the household is online stays empty, household-wide notices and headlines no longer arrive, and an item or message sent from another device does not arrive while the phone is on the home network. In the Android app, "Connect" in the Household panel does not connect.

**Who it affects.** Households with more than one phone.

**Why.** These features had every phone send to and read from shared subjects on the household bus, so any phone could read the others' traffic, including Study sync messages that carry a person's login. Since 0.5.0 each phone's login on the bus reaches only its own Study sync, its own inference answers and its own replies. Moving these features to requests through the home, where each person gets only what is theirs, is not done yet.

**What to do.** Nothing. Each phone still syncs its Study with the home on the home network, so one person's phones still agree through the home. Room visits, tells and the journal go through the home and work as before.

## The desktop inference helper cannot log in to the household bus

**What you notice.** `wyrdsekai-daemon`, the desktop helper that lends a computer's graphics card to the household, cannot connect.

**Who it affects.** Anyone who runs `wyrdsekai-daemon` against a 0.5.0 home.

**Why.** The household bus now needs a login and encryption, and the daemon has no way to get a login yet.

**What to do.** Join the computer with `wyrd join` and run a full node on it instead, or turn on `WYRDSEKAI_NATS_LAN_PLAINTEXT=true` on the hub for now.

## On the larger model, a long coding or library task can fail while the companion is talking

**What you notice.** A coding task in CodeZaiku, or a library research run that uses the companions' model, stops with "Context size has been exceeded", while the companion is in a long conversation.

**Who it affects.** Households on the single larger model whose coding helper or library uses that same model (the library does by default).

**Why.** The larger model now serves two requests at once, so a short question is not kept waiting behind a long reply. Both requests share one context space of 32,768 tokens. When two long requests together need more than that, both stop. Before, the second one waited its turn.

**What to do.** Run the task again when the conversation is quieter, or give the library its own model with `wyrd researcher gpu`. `LLAMA_BRAIN_PARALLEL=1` goes back to one request at a time.

## Model downloads fill a log file with progress text

**What you notice.** A saved log is full of progress-bar text, tens of thousands of characters long.

**Who it affects.** Anyone who saves the output of a command that downloads a model to a file, for example `wyrd start > log`.

**Why.** The progress bar redraws its line over and over. In a terminal you see one moving line. In a file, every redraw is kept.

**What to do.** Nothing breaks. It only makes the file hard to read.

## On a Mac, the coding helper cannot reach a model on another computer

**What you notice.** A coding helper cannot connect to a model that runs on another computer in your home. It looks like a network problem, but it is not one. `curl` to the same address works.

**Who it affects.** Macs whose drive model runs on another computer in the household. The drive is the model that plans and uses tools. If it runs on the same Mac, nothing is affected.

**Why.** macOS has a Local Network permission. It stops programs that are not Apple's from reaching other computers on your network. That includes Java, which Wyrdsekai runs on, and every coding helper. Apple's own programs, like `curl`, are exempt.

**What to do.** Grant Local Network to your terminal app, once. Open System Settings, then Privacy & Security, then Local Network. Apple requires a person to do this. It cannot be done from a script or over SSH.

## The documentation is only in English

**What you notice.** Wyrdsekai itself speaks English, Spanish and Japanese. Every document is in English only. That includes [FIRST_ENCOUNTER.md](FIRST_ENCOUNTER.md), which a new bondholder is given at install. A bondholder is the person a companion is bonded to.

**Why.** We are holding it back on purpose. That first document is the most carefully worded text in the project. A few words carry its meaning:

- **bondholder**, the person a companion is bonded to;
- **Hearth**, a companion's own room;
- **saudade**, the longing for one particular person who is away;
- **refusal**, a companion's principled no.

A translation that is only accurate would flatten them into "user", "home", "loneliness" and "denial". That is exactly the view the design argues against.

**How you can help.** This needs a translator who can keep that tone in the other language, not a quick pass. If you speak Spanish or Japanese well, it is a valuable contribution and a good place to start. Please open a discussion first. The words are worth agreeing on before the prose.

## The WhatsApp bridge is not included

**What you notice.** Wyrdsekai has a WhatsApp channel, but the helper program it talks to does not come with it.

**Who it affects.** Anyone who wants companions to talk over WhatsApp.

**Why.** The helper is built on `whatsmeow`, which depends on code under the GPL-3.0 licence. Wyrdsekai is under the Apache-2.0 licence. To keep that clear, the helper is not in this repository, and no installer builds or ships it.

**What to do.** WhatsApp does not work out of the box. If you build or run such a helper yourself, you are bound by the GPL-3.0 licence of the code it uses.

## Phone apps you build yourself

These issues affect you only if you build the Android apps yourself from the source code.

### The Android app's release build is not signed

**What you notice.** Building the Kotlin Multiplatform app, the one for Android and desktop, with `assembleRelease` gives `androidApp-release-unsigned.apk`.

**Why.** The repository has no release signing key, and it should not have one.

**What to do.** Sign the app yourself, or use the debug build, until release signing is set up.

### React Native release builds run only on real phones

**What you notice.** On an emulator, a phone simulated on a PC, the React Native app crashes over and over with `SoLoaderDSONotFoundError: libreactnative.so`.

**Why.** `build-android.sh --release` builds only for arm64, the kind of chip in real phones. An x86_64 emulator needs its own build.

**What to do.** To build for an x86_64 emulator, run:

```bash
cd clients/rn/android && ./gradlew assembleRelease -PreactNativeArchitectures=x86_64
```

### Models on the phone do not run on x86_64 emulators

**What you notice.** The app crashes when it runs a model on the device, inside an x86_64 emulator.

**Why.** The llama.cpp layer that runs the model crashes during generation there.

**What to do.** Use a real arm64 phone. That is the supported way.

### The QR scanner uses Google's ML Kit

**What you notice.** Nothing, while it works. But the Android app reads invite QR codes with Google's ML Kit. That is a closed-source part inside an otherwise Apache-2.0 app. It also pulls in Google Play Services. That is a poor fit for a project whose argument is that you should not need someone else's account to run your own software.

**The plan.** The intended replacement is [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), which is open source under Apache-2.0. It is a modern rewrite of the older ZXing scanner. The older one still works, but for years it has only been kept running, with no new work. zxing-cpp can also be used on iOS, so one scanner could later serve both kinds of phone.

**Why it is not done yet.** The code change is small. The testing is not. A camera is the part of Android that emulators copy worst. Real phones from different makers see a QR code very differently. A scanner must read an invite off a screen in a dim room on a three-year-old phone. Otherwise, getting started is broken for that person, with no useful error.

Doing it properly needs several real phones from different makers, on different Android versions. They need testing against a real invite QR code, at real distances and in real light. That includes the awkward cases: a glossy screen, a printed code, a cracked lens. We do not have those phones, and we would not guess.

**How you can help.** This is a good first contribution for someone with a drawer of Android phones. It removes the last closed-source part from the default build. To be merged, it needs evidence from real phones, not a green emulator run. The code details are under [For developers](#for-developers).

## For developers

**Running the test suite on a computer with Wyrdsekai installed breaks about a dozen tests.** The installed copy owns `~/.wyrdsekai`, as a symlink owned by root, and puts its programs on the search path. Tests that expect a clean environment then fail. Run the suite on a computer without an install, or set `WYRDSEKAI_DATA_DIR` to a folder you can write to.

**Some end-to-end test tiers need a running model server.** Without one, they fail with `initializationError`. Point them at your servers with `WYRDSEKAI_INFERENCE_URL` (the main model) and `WYRDSEKAI_E2E_VOICE_URL` (the voice model), and set `WYRDSEKAI_E2E_BACKEND=llama-server`.

**Two cross-zone streaming integration tests fail against the WireMock harness.** They do not fail against a real model server. The mock throws an internal Jetty error when it serves `GET /v1/models`, which the dead-backend guard checks. The non-streaming and quota paths pass. They use the same request handling.

**The SSH lockout message.** SSHD sends its welcome banner before sign-in runs. So a lockout found during sign-in cannot be explained over the wire from the password authenticator. Sending the message through a keyboard-interactive prompt would work, and is a welcome contribution.

**Replacing ML Kit.** The replacement is `io.github.zxing-cpp:android` on Maven Central. It is a C++20 rewrite, not the original Java ZXing. It ships Kotlin/Native bindings as well as Android ones, so the same decoder could later serve the iOS side of the KMP client. The dependency is one line in `clients/kmp/gradle/libs.versions.toml`. The only call site is `QrScanner.android.kt`. It uses CameraX for the preview and ML Kit only to decode a frame, so swapping the decoder does not touch the camera code. A native decoder adds a `.so` file for each ABI, where ML Kit came from Play Services. Measure the APK size before and after.

**The WhatsApp helper.** The contract the helper must implement is described in `core/src/main/java/org/wyrdsekai/core/agent/channels/WhatsAppChannel.java`.

**Languages.** The three languages reach scripts through `world.t()` and Java through `I18n.get()`.
