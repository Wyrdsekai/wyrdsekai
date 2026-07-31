# Wyrdsekai — Kotlin Multiplatform client

Compose Multiplatform. One shared module drives Android, iOS and a desktop app,
so a change to session handling or the world model lands on every surface at
once rather than three times.

```
shared/        the client engine — session, protocol, world state
androidApp/    Android host
iosApp/        iOS host (Xcode project)
desktopApp/    JVM desktop host
```

## Prerequisites

| | |
|---|---|
| Android SDK | `ANDROID_HOME` or `~/Android/Sdk` |
| JDK | **21** — not 25 |
| iOS | macOS with Xcode |

The JDK version is not a preference. The Android Gradle Plugin in use here
rejects newer JDKs, and the failure reads as an unrelated Kotlin compile error,
so it costs an hour if you guess. If you use `mise`, `.mise.toml` pins it.

There is **no `local.properties`** in this tree, deliberately — it hardcodes an
absolute SDK path and belongs to whoever checked out the repo. Gradle resolves
the SDK from `ANDROID_HOME` instead. If you prefer a file, create your own; it
is gitignored.

## Build

```bash
./build-android.sh --run              # debug APK
./build-android.sh --release --run    # release variant
./build-android.sh --install          # build, then install to the connected device
./build-android.sh --start            # build, install, launch
./build-android.sh --clean --run      # clean first
./build-android.sh                    # no args: prints the full option list
```

Or drive Gradle directly:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run
```

iOS builds from `iosApp/` in Xcode against the shared framework.

## Two clients, on purpose

There is also a React Native client in `../rn`, and both are maintained. They
are not redundant: they are two independent implementations of the same
protocol, which is what keeps the protocol honest — a behaviour that only works
because of an assumption baked into one client shows up as a divergence rather
than as the definition.

`../parity` holds the executable contract. If you change client behaviour, that
is where the expectation lives, and it runs against both.
