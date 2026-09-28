# opencode-android

Standalone Capacitor 8 shell for the opencode android app. The web layer (platform abstraction, android entry, notifications UI) lives in the pinned `opencode` fork; this repo owns the native project and the build/sync chain.

## Layout

- `opencode/` — git submodule pinned to the fork (`anomalyco/opencode`) branch `android-platform-v2`. The android app variant lives in `opencode/packages/app`.
- `android/` — Capacitor 8 native project (`ai.opencode.app`).
- `capacitor.config.ts` — `webDir: "www"`; `sync` builds the submodule's `packages/app` with `VITE_PLATFORM=android` into `www/`.
- `www/` — generated web bundle, gitignored. Never edit or commit.

## Prerequisites

- [Bun](https://bun.sh) (only package manager; never npm/npx/yarn/pnpm)
- JDK 21 — Capacitor 8 compiles with Java 21; point `JAVA_HOME` at a JDK 21 (this machine: `/opt/jdk-21`) when the system default is older.
- Android SDK at `~/Android/Sdk` — export `ANDROID_HOME` or write `sdk.dir` into `android/local.properties`.

First clone:

```sh
git submodule update --init
bun install
bun install --cwd opencode
```

## Build flow

```sh
bun run sync         # build www/ from the submodule's packages/app, then cap sync android
bun run apk          # cd android && ./gradlew assembleDebug
```

`bun run dev` runs `cap run android`; `bun run open` opens the native project in Android Studio.

## Upstream sync flow (v2 → fork → shell)

1. In the fork, merge `v2` into `android-platform-v2` and commit. The app-level android adaptation (platform abstraction, `VITE_PLATFORM` entry, Capacitor factory) lives there and is the source of truth for app behavior — see its `specs/android-platform/spec.md`.
2. In this repo, update the pinned commit: `git submodule update --remote opencode`.
3. Commit the moved submodule pointer.
4. Re-sync and rebuild: `bun run sync && bun run apk`.

Keep syncs small and frequent; large gaps between syncs are what made the old `feature/android-app` branch painful to maintain.

## Native customizations (intentional — do not remove)

These deviations from stock `cap add android` output are load-bearing:

- `MainActivity.java` — edge-to-edge via `WindowCompat.setDecorFitsSystemWindows(..., false)`; deliberately **no root padding** (safe areas come from CSS `env()` in the web layer).
- `android/app/src/main/res/values/styles.xml` — transparent status/navigation bars, `shortEdges` display-cutout mode.
- `AndroidManifest.xml` — `POST_NOTIFICATIONS`, `ForegroundService`, notification watcher registrations.
- `EventNotificationWatcher.java` / `ForegroundService.java` — session notification integration (pairs with the fork's notification UI).
- `network_security_config.xml` + `androidScheme: "http"` — cleartext local server support.
- `capacitor.config.ts` — `StatusBar` style `DARK` default, `Keyboard` resize `body` + `resizeOnFullScreen`.

`cap sync android` regenerates `android/capacitor.settings.gradle` and `android/app/capacitor.build.gradle`; commit the regenerated output when the plugin set changes. That is expected churn, not a conflict.

## Dependency coupling

Capacitor plugin versions must stay aligned across the two repos: a plugin is declared in this repo's `dependencies` (native side) and in `opencode/packages/app/package.json` (JS side). Adding a plugin means adding it to **both**, keeping the same version, then `bun run sync` in both repos.
