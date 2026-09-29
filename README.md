# opencode-android

Standalone Capacitor 8 shell for the opencode android app. The `opencode` submodule tracks the **vanilla upstream `v2` branch**; the android adaptation is carried in this repo as `overlay/android-app.patch`, applied onto the submodule at build time and reversed afterwards. The opencode tree itself stays patch-free.

## Layout

- `opencode/` — git submodule tracking the upstream fork (`anomalyco/opencode`) branch `v2`. Contains NO android code in its committed state.
- `overlay/android-app.patch` — the android app-layer adaptation (platform abstraction, `VITE_PLATFORM` entry, Capacitor factory, notifications UI), generated from the fork worktree. Applied with `git apply --3way` before each build, reversed after.
- `android/` — Capacitor 8 native project (`ai.opencode.app`).
- `capacitor.config.ts` — `webDir: "www"`; `sync` builds the patched submodule's `packages/app` with `VITE_PLATFORM=android` into `www/`.
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

## Upstream v2 + overlay model

The submodule rides upstream `v2` directly — no android branch needs to exist on the remote. `build:web` applies the overlay patch (`git apply --3way`), builds with `VITE_PLATFORM=android`, then reverse-applies it, so the submodule tree returns to pristine v2 after every build.

When upstream `v2` moves:

1. Bump the pin: `git -C opencode fetch origin && git -C opencode checkout <new-v2-commit>` and commit the moved gitlink.
2. Re-apply the overlay: `git -C opencode apply --3way overlay/android-app.patch` (run from the repo root).
   - Clean apply → done; regenerate nothing.
   - Conflict → resolve it in the submodule tree, then regenerate the patch against the new pin: `git -C opencode diff <new-v2-commit> -- packages/app > overlay/android-app.patch`. Commit patch and gitlink together.
3. Verify: `bun run sync && bun run apk`.

Upstream `v2` moves fast (it gained ~37k lines under `packages/app` in the last week of Sep 2026) and its history occasionally gets rewritten — expect to redo the conflict step regularly. Conflicts surface as loud build failures, which is the point: the overlay can never silently drift.

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
