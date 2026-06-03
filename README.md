# devhub

A local, terminal-based **single source of truth** for a developer juggling multiple
holacracy roles. Four tabs, one place to look:

- **Dev** — PRs that *genuinely* need your review, after filtering (press `x` to expand the hidden ones).
- **Platform** — the most-recent failing pipelines, each with an **AI-extracted root cause**
  (via Claude) instead of a generic "exit code 1". Uses your local **`claude` CLI login** by
  default (no API key); falls back to an API key or a regex excerpt.
- **Maintenance** — SonarCloud quality gates + failing conditions, coverage & new-code coverage,
  reliability/security/maintainability ratings, bug/vuln/smell/hotspot counts, duplication, LOC.
- **Goals** — role-progress KPIs (branches, unit tests, coverage + new-code coverage vs target,
  with progress bars) and CI health (run count + failure rate) across your repos.

The UI is responsive to terminal width, and lists expand/collapse with `x`.

Everything runs on your machine. The only network calls are authenticated requests to
GitHub and SonarCloud. Tokens live in the **macOS Keychain**, never in files or the repo.

> Built for GitHub + GitHub Actions + SonarCloud. macOS-first (Keychain + notifications),
> but the core is plain Kotlin/Ktor and ports easily.

## The "truly needs my focus" PR filter

A PR appears in the Dev tab only when **all** of these hold (each toggleable in config):

1. You're a **requested reviewer** (the GitHub search is scoped to `review-requested:@me`).
2. The **`claude[bot]` App has reviewed the current head commit** — if you push new commits
   after the bot reviewed, the PR drops out until the bot re-reviews.
3. It's **not a draft**.
4. **CI is green** (status check rollup is `SUCCESS`).

Filtered-out PRs aren't silently dropped — the tab shows `⋯ N hidden (reasons)` so you can
trust nothing slipped through.

## Stack

Kotlin 2.2.10 · Mosaic 0.18.0 · Ktor 3.0.3 · Gradle 8.11.1 (wrapper committed). Modern
Mosaic has no Gradle plugin — it relies on the official Kotlin Compose compiler plugin
(`org.jetbrains.kotlin.plugin.compose`), which the build applies.

- **[Mosaic](https://github.com/JakeWharton/mosaic)** (declarative Compose-runtime TUI) + **[Mordant](https://github.com/ajalt/mordant)** (CLI output)
- **[Ktor](https://ktor.io/) client** + **kotlinx.serialization** for GitHub GraphQL/REST and SonarCloud REST
- Local JSON state for change-diffing (new-signal notifications) and coverage deltas
- macOS **Keychain** for tokens, **osascript/terminal-notifier** for desktop alerts

> Design note: GitHub is queried via raw GraphQL through Ktor (not Apollo) and the cache is a
> small JSON file (not SQLDelight) — both deliberate, to keep the build free of codegen plugins.
> Either is easy to swap in later.

## Install

You need **JDK 17+**. The Gradle wrapper is committed, so `./gradlew` works out of the box and
resolves dependencies on first run (no system Gradle/Kotlin needed).

```bash
git clone https://github.com/steliospap-luscii/dev-mission-control.git
cd dev-mission-control
./install.sh          # builds + symlinks `devhub` into ~/.local/bin (override with ./install.sh /usr/local/bin)
```

That puts a `devhub` command on your PATH. (Prefer not to install? Use `./gradlew run`, or the
launcher at `build/install/devhub/bin/devhub` after `./gradlew installDist`.)

## First-time setup

```bash
devhub config    # login, watched repos, SonarCloud projects, coverage goals, interval
devhub auth      # paste GitHub + SonarCloud tokens (stored in the macOS Keychain)
devhub doctor    # verify connectivity
devhub probe     # one-shot text snapshot (no TTY needed) — handy for cron/CI too
devhub           # launch the live dashboard
```

### Tokens needed

- **GitHub** — a fine-grained PAT with read access to **Pull requests**, **Actions**, **Contents**
  on the relevant repos.
- **SonarCloud** — a user token (Account ▸ Security). Optional; without it the Maintenance tab is disabled.
- **Claude for AI root-cause** — by default devhub shells out to the **`claude` CLI** (Claude Code)
  using whatever auth you already have (`claude /login` — subscription or org), so **no API key is
  needed**. If you'd rather use the Anthropic API directly, set the `api` backend and provide a key
  via `devhub auth` or `ANTHROPIC_API_KEY`. Pick the backend in `devhub config` (`auto`/`cli`/`api`/`off`).
  Results are cached per run and only the capped most-recent failures are analyzed, so cost/usage stays low.

> **SonarCloud token scope:** `measures/component` (coverage, test counts, smells) needs **Browse**
> permission on the project. Tokens without it get a misleading `404 "Project doesn't exist"`.
> devhub leads with the `qualitygates/project_status` endpoint (readable by analysis-scoped tokens
> too) and treats measures as best-effort — so failing gate conditions always show, and coverage /
> test KPIs appear when your token can read them.

## Keys

```
↑/↓ or j/k   move selection      ←/→ or Tab   switch tab
enter / o    open in browser     x            expand/collapse (hidden PRs)
r            refresh now          q            quit
```

## Layout

```
src/main/kotlin/com/devhub/
├── Main.kt              # arg dispatch: dashboard | auth | config | doctor | probe
├── config/              # Config (~/.config/devhub/config.json) + Keychain (security CLI)
├── github/              # Ktor GraphQL/REST client, PR filter rules, Actions log extraction
├── sonar/               # Ktor SonarCloud REST client
├── core/                # Poller (aggregates all sources), seen-state store, notifier, models
└── ui/                  # Mosaic app: tab bar + Dev / Platform / Maintenance / Goals panels
```

## Note on Mosaic version bumps

The UI in `ui/App.kt` / `ui/Panels.kt` / `ui/Theme.kt` targets Mosaic **0.18.0**. One quirk to
know: Mosaic's `Color` only exposes the 8 base ANSI colors (no `Bright*` constants), so brighter
shades are built from the `Color(r, g, b)` factory in `Theme.kt`. If you bump Mosaic and a
composable import moves, the fix is mechanical and confined to those three files; everything else
is plain Kotlin + Ktor.

## Contributing & license

Built by and for Luscii engineers wearing multiple holacracy hats, but useful to anyone on a
GitHub + GitHub Actions + SonarCloud stack. Issues and PRs welcome. Licensed under the
[MIT License](LICENSE).
