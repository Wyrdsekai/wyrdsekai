# Wyrdsekai — React Native client

Expo-managed React Native for Android and iOS, plus a web build.

## Why `android/` and `ios/` are not here

They are **generated**, not authored. `expo prebuild` produces them from
`app.json`, so committing them would mean maintaining machine-written code by
hand and re-resolving a merge conflict every time a dependency moves.

The consequence worth knowing: **a fresh clone has no `android/` directory, and
that is expected.** The build script runs prebuild for you.

The exception is the native TLS trust code, which prebuild would overwrite.
That lives in **`native-android/`** — hand-authored, tracked, and copied over
the generated tree during the build. It is the household CA pinning that lets a
phone verify a relay it has never seen before; without it the app builds and
then cannot connect.

## Prerequisites

| | |
|---|---|
| Node.js | 18+, 20+ recommended |
| pnpm | auto-installed by the build script if missing |
| Android SDK | `ANDROID_HOME` or `~/Android/Sdk` |
| iOS | macOS with Xcode and CocoaPods |

## Build

```bash
./build-android.sh --run           # debug APK, arm64
./build-android.sh --release --run # release variant
./build-android.sh --install       # build and install to the connected device
./build-android.sh --start         # build, install, launch
./build-android.sh --prebuild      # force expo prebuild (after changing app.json)
./build-android.sh --all-arch      # every ABI — slower, for distribution
./build-android.sh                 # no args: prints the full option list
```

Run `--prebuild` after editing `app.json` or adding a native dependency;
otherwise the generated project is stale and the failure is confusing.

For iOS and the web surface:

```bash
pnpm install
pnpm ios          # expo run:ios   (macOS, Xcode, CocoaPods)
pnpm web          # expo start --web
```

## Tests

```bash
pnpm test              # jest
pnpm typecheck         # tsc --noEmit
pnpm lint              # eslint
pnpm test:e2e:web      # playwright
```

## Two clients, on purpose

The Kotlin Multiplatform client in `../kmp` is maintained in parallel, and both
are real. Two independent implementations of one protocol keep the protocol
honest: an assumption that quietly leaked into one client's behaviour surfaces
as a disagreement rather than becoming the definition by default.

`../parity` holds that contract in executable form. Change client behaviour and
the expectation belongs there, where it runs against both.
