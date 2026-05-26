# AGENTS.md

Instructions for AI coding agents (Jules, Codex, Claude Code, Cursor, etc.) working in
this repo. The goal is for agent-authored changes to be indistinguishable in style and
scope from human-authored ones, so reviewers don't have to launder them through a
second pass.

If a request conflicts with anything below, **stop and ask** rather than guess.

---

## What this project is

SKapsule is an unofficial Android (arm64) port of *Spiral Knights*. The Android app
(`launcher/`) boots a bundled JRE that runs the real desktop game client, with native
shims (gl4es, openal-soft, LWJGL Android, caciocavallo, frenchpress) supplying what
the desktop client expects from its host platform. See [README.md](README.md) for the
full picture and [the layout section](README.md#repository-layout) for what lives
where.

The buildable Android project is `launcher/`. Everything else under the repo root is
either a native-component submodule or a build/CI script.

---

## Build & verification

Don't blind-edit. Before claiming a change works, build the affected component:

- **Launcher / Kotlin / Java (`launcher/`):** `cd launcher && ./gradlew :app:assembleDebug`
  (or `assembleRelease`). Use JDK 25 as `JAVA_HOME`. A successful compile is the
  minimum bar; for behavior changes, also run the relevant unit tests
  (`./gradlew :app:testDebugUnitTest`).
- **Native submodules (`gl4es/`, `openal-soft/`, `lwjgl3/`, `caciocavallo17/`,
  `frenchpress/`):** rebuild via the matching script in `scripts/`. Each script
  expects specific JDKs in its environment — see
  [README §Building from source](README.md#building-from-source-developers) for the
  full matrix. In particular, `build-lwjgl3-android.sh` needs both `JAVA_HOME` →
  JDK 25 and `JAVA8_HOME` → JDK 8 (the JDK-8 `rt.jar` feeds LWJGL's Java-8
  multi-release layer, and no later JDK ships it). If you touch a submodule, the
  *outer* repo must also be updated (the submodule pointer is what CI builds from).
- **CI is the canonical recipe.** [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)
  is the source of truth for the from-source build. If your change requires a build
  step that isn't in CI, that's a red flag — surface it.

If you cannot run the build in your sandbox, say so explicitly in the PR. Do **not**
claim a change is verified when it isn't.

---

## Scope discipline

The single most common failure mode for agentic PRs in this repo is **overreach**:
splitting one logical change across multiple "micro" PRs, or piling unrelated cleanups
into a feature PR. Don't do either.

- **One PR = one logical change.** A perf pass on a single file is one PR, not three.
  Four trivial commits that each touch `TouchControlOverlay.kt` should have been one.
- **Don't bundle drive-by cleanups** (unused imports, formatting, reorders) into a
  feature/fix PR. If you spot them, mention them in the PR description and let the
  human decide — or open a separate, clearly-scoped `chore:` PR.
- **Don't refactor opportunistically.** If the task is "fix bug X", fix bug X. Don't
  also rename variables, extract helpers, or reflow code that isn't part of the fix.
- **Don't add features, abstractions, or config knobs that weren't asked for.** Three
  similar lines is better than a premature abstraction. No "future-proofing."
- **Don't add error handling, fallbacks, or validation for scenarios that can't
  happen.** Trust internal callers and framework guarantees. Validate only at system
  boundaries.
- **Don't write new files when an existing one will do.** Never create
  documentation/README files unless explicitly asked.

When in doubt about scope, smaller is better, and "ask first" is always allowed.

---

## Commit & PR conventions

Match the existing history. Run `git log --oneline -30` before writing your first
commit message.

**Format:** [Conventional Commits](https://www.conventionalcommits.org/), lowercase
type, lowercase subject, no trailing period, no emoji:

```
feat: add initial support for touch controls
fix: make touch controls work without physical gamepad attached
perf: optimize hot loop in TouchControlOverlay onTouchEvent
chore: bump lwjgl3
test: add unit tests for NativeBridgePrompt fallback logic
```

Common types in this repo: `feat`, `fix`, `perf`, `chore`, `test`, `docs`.

**Subject style:**
- Imperative mood ("add", "fix", "bump") — not "added", "fixes", "bumping".
- Lowercase. No emoji (⚡, 🚀, ✨ etc. — none). No Title Case.
- Aim for ≤ ~72 chars. Backticks around symbol names are fine when they help
  (`` perf: optimize hot loop in TouchControlOverlay `onTouchEvent` ``).

**Bodies:**
- Most commits in this repo are title-only. Don't pad with a body unless there's
  genuine context (the *why*, a non-obvious tradeoff, a linked issue, a workaround for
  an upstream bug). A body that just restates the diff in prose is noise — delete it.

**PR titles:** same rules as commit subjects. **PR descriptions:** explain *why*,
list the user-visible effect, and call out anything reviewers should double-check
(performance assumptions, behavior changes, files you didn't touch but considered).

---

## Code style

### Kotlin / Java (launcher)

- Match the surrounding file. Indentation, brace placement, import order, and naming
  should be invisible in the diff.
- **Comments: write fewer.** Only comment the *why* when it's non-obvious — a hidden
  constraint, a subtle invariant, a workaround. Don't write comments that restate
  what well-named code already says. Don't write comments that reference the task
  ("added for perf pass", "cached for fast updates") — those rot.
- No `TODO(agent):`, `FIXME(jules):`, or similar agent-attribution markers in code.
- Don't reformat or reorder code you aren't otherwise changing. A noisy diff hides
  the real change.
- Don't add `@Suppress` annotations or lint-disable comments to make warnings go
  away — fix the cause.
- Don't introduce new dependencies, Gradle plugins, or Kotlin compiler flags without
  asking.

### Native (C/C++ submodules)

- Touch submodules only when the task requires it. Most tasks land entirely in
  `launcher/`.
- Prefer upstreaming fixes to the submodule's own repo over carrying local patches.
  If you must patch, the patch belongs in the submodule, and the outer repo's commit
  bumps the submodule pointer.

### Build scripts (`scripts/`, Gradle, CI)

- Keep them readable and reproducible. CI must be able to run them unattended.
- Don't add interactive prompts or assume a developer machine layout.

---

## Performance work — extra rules

This repo has seen a wave of "micro-optimization" PRs (cache `findViewById`, swap
`indices.reversed()` for `downTo`, hoist `RectF` out of `onDraw`, etc.). Some are
genuinely useful; many are noise. Before opening one:

1. **Identify the hot path.** Is this code actually on a hot path (per-frame draw,
   per-touch dispatch, tight inner loop)? If not, the change is probably not worth
   the diff.
2. **Quantify, or at least justify.** "Avoids allocation in `onDraw`" is a real
   reason. "Looks faster" is not. Say which it is in the PR description.
3. **Bundle related nits into one PR**, scoped to one file or one subsystem. Four
   one-line PRs against the same file is exactly what to avoid.
4. **Don't trade readability for micro-wins** outside hot paths.

---

## Tests

- Unit tests live under `launcher/app/src/test/`. Add tests when adding non-trivial
  pure logic (parsing, fallback decisions, state machines). Don't add tests that
  exercise the Android framework or require an emulator — those don't run in CI.
- Don't delete or weaken existing tests to make a change pass. If a test is wrong,
  say so in the PR and fix it deliberately.

---

## Things to never do without asking

- Force-push, rewrite shared history, or amend already-pushed commits.
- Push to `main`. All work goes through PRs.
- Modify `.github/workflows/`, signing config, release plumbing, or anything under
  `launcher/app/src/main/assets/` (those are staged build outputs, not source).
- Update submodule pointers as a side effect of an unrelated change.
- Add new top-level files or directories.
- Introduce new third-party dependencies, license-incompatible code, or anything
  that would change the licensing story in [README §License](README.md#license).
- Commit secrets, keystores, or `keystore.properties`.

---

## When you're unsure

Ask. A short clarifying question in the PR (or before opening one) is always cheaper
than a wrong-direction change that has to be reverted.
