# opencode-android

Standalone Capacitor 8 shell for the opencode android app. The web layer (platform abstraction, android entry, notifications UI) lives in the pinned `opencode` fork; this repo owns the native project and the build/sync chain.

## Layout

- `opencode/` — git submodule pinned to the fork (`anomalyco/opencode`) branch `android-platform-v2`. The android app variant lives in `opencode/packages/app`.
- `android/` — Capacitor 8 native project (`ai.opencode.app`).
- `capacitor.config.ts` — `webDir: "www"`; `sync` builds the submodule's `packages/app` with `VITE_PLATFORM=android` into `www/`.
- `www/` — generated web bundle, gitignored.

## Sync flow (fork → shell)

1. In the fork, merge `v2` into `android-platform-v2` and commit.
2. In this repo, update the pinned commit: `git submodule update --remote opencode`.
3. Commit the moved submodule pointer.

## Build flow

```sh
bun install          # shell dependencies
bun install --cwd opencode   # submodule dependencies (first clone)
bun run sync         # build www/ from the submodule's packages/app, then cap sync android
bun run apk          # cd android && ./gradlew assembleDebug (requires ANDROID_HOME, SDK at ~/Android/Sdk)
```

`bun run dev` runs `cap run android`; `bun run open` opens the native project in Android Studio.

Capacitor 8 compiles with Java 21: point `JAVA_HOME` at a JDK 21 (this machine: `/opt/jdk-21`) when the system default is older.
