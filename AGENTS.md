# AGENTS.md

Rules for agents working in this repository.

- This repo is a Capacitor 8 native shell only. The `opencode` submodule tracks the vanilla upstream `v2` branch and contains no android code in its committed state; the android app-layer adaptation is carried as `overlay/android-app.patch` and applied only at build time. Do not add JS/TS app code here; the only TS in this repo is `capacitor.config.ts`.
- Treat the submodule as read-only. Its tree must be pristine v2 whenever you are not mid-build (`build:web` applies the overlay and reverses it). Never commit inside `opencode/`.
- Bump ritual when upstream `v2` moves: bump the gitlink, re-apply the patch with `git apply --3way`, resolve any conflicts in the submodule tree, regenerate the patch with `git -C opencode diff <new-pin> -- packages/app > overlay/android-app.patch`, and commit patch + gitlink together. See README "Upstream v2 + overlay model".
- Never edit generated paths by hand: `www/` (rebuilt by `bun run sync`), `android/capacitor.settings.gradle`, `android/app/capacitor.build.gradle`, and the gradle wrapper. When `cap sync` rewrites them, commit the regenerated output as-is.
- The native customizations listed in `README.md` (edge-to-edge `MainActivity` with no root padding, transparent system bars, notification services, cleartext scheme, Keyboard/StatusBar plugin config) are intentional. Do not "clean them up" or revert them during regeneration.
- Keep safe-area handling in the web layer: CSS `env()` mapped to `--safe-area-inset-*` custom properties in the fork's `packages/app/src/index.css`. Do not add native root padding or remove `viewport-fit=cover` here.
- Capacitor plugins are declared in both this repo and the fork's `packages/app`; keep versions identical and majors aligned (8.x). A plugin added on only one side breaks the sync.
- Use Bun exclusively: `bun install`, `bun add`, `bunx` (never npm/npx/yarn/pnpm).
- Build chain: `bun run sync` then `bun run apk`. `gradlew` needs JDK 21 (`JAVA_HOME`) and `ANDROID_HOME` (SDK at `~/Android/Sdk`). Never run tests or gradle from inside the submodule.
- Conventional commits: `type(scope): summary` with types `feat`, `fix`, `docs`, `chore`, `refactor`; scope `android` or `capacitor` when helpful. Branch names: short, hyphen-separated, no type prefixes.
- The fork is the source of truth for android app behavior; its `specs/android-platform/spec.md` specifies the published contract. Behavior changes go through the fork; this repo changes only when the native wrapper or build chain must follow.
