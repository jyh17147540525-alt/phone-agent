# PocketAgent — Android project skeleton

**English** | [简体中文](README.md)

> A BYOK phone AI agent.
> This directory is the M0-stage project skeleton: Gradle configuration and the core interface
> contracts (real, compilable code).
> **Note**: it builds and produces an installable debug APK. What it does not yet have is the
> perception layer, the action layer, or the agent loop — see the root [README](../README.en.md).

## Module layout

```
android/
├── settings.gradle.kts              module registration and repository config
├── build.gradle.kts                 root build script (plugins declared centrally)
├── gradle.properties                global build properties
├── gradle/libs.versions.toml        version catalog (dependencies managed in one place)
│
├── app/                             ★ application shell: UI, navigation, DI wiring
│
├── core/                            ── infrastructure layer (no business logic)
│   ├── common/                      utilities, Result wrapper, dispatchers
│   ├── crypto/                      Keystore wrapper, encryption, secure storage
│   ├── database/                    Room + SQLCipher, DAOs, migrations
│   └── network/                     OkHttp, SSE parsing, log redaction
│
├── provider/                        ── model access layer
│   ├── api/                         LlmProvider abstraction and data models (zero deps)
│   ├── openai-compat/               OpenAI and compatible vendors (8+ covered)
│   ├── anthropic/                   Claude Messages API
│   ├── gemini/                      Gemini generateContent
│   └── local/                       Ollama / LM Studio / llama.cpp
│
├── perception/                      ── perception layer
│   accessibility tree reading, MediaProjection capture, OCR, unified ScreenSnapshot
│
├── action/                          ── action layer (multi-channel)
│   ActionExecutor abstraction + A11y / Shizuku / IME / OverlayPrompt implementations
│
├── agent/                           ── decision layer
│   AgentOrchestrator (planning) + Grounder (locating) + Verifier (checking) + Memory
│
├── safety/                          ── safety guardrails
│   sensitive-screen blocking, dangerous-action confirmation, automation policy registration
│
├── keymgmt/                         ── BYOK system
│   key import, validation, health checks, usage stats, budget circuit breaker, model routing
│
├── memory/                          ── memory system
│   three-tier memory (short-term trace / mid-term summary / long-term preference) + UI graph cache
│
├── overlay/                         ── floating interaction
│   floating ball, execution visualization, result cards
│
├── plugin/                          ── plugin system (six modules)
│   ├── api/                         plugin contract, capability definitions, Manifest — zero Android deps
│   ├── runtime/                     loader, lifecycle, capability proxy, sandbox
│   ├── rules/                       L1 rule packs: selector engine, matcher, action execution
│   ├── script/                      L2 JS sandbox runtime
│   ├── store/                       plugin store: listing, subscription sources, import/export, conflict detection
│   └── devtools/                    snapshot inspector, rule editor, test bench
│
├── display/                         ── virtual display
│   virtual display creation/destruction, app launching, availability checks
│   └── preview/                     secondary-display preview overlay + coordinate mapping
│
├── update/                          ── in-app self-update, hash verification
├── channel/                         ── channel identifiers and privacy-friendly analytics
├── onboarding/                      ── per-ROM permission guidance wizard and self-check
├── github/                          ── GitHub Device Flow / BYO token, reporting channel
└── contribute/                      ── rule and plugin contribution flow (PR generation, content preview)
```

30 modules in total.

## Dependency direction (hard constraint)

```
        app
         │
   ┌─────┴──────┬──────────┬─────────┐
   ▼            ▼          ▼         ▼
keymgmt    agent       overlay    safety
   │            │          │         │
   ▼            ▼          ▼         │
provider/*  perception   memory      │
   │            │          │         │
   └────────────┴──────────┴─────────┘
                ▼
              action
                ▼
        core/* (common/crypto/database/network)
```

**Forbidden dependencies**:

- `agent` must not depend on any `provider/*` implementation — only on `provider/api`
- `perception` and `action` must not depend on `agent` (they must be independently unit-testable)
- `core/*` must not depend on any higher-level module
- The `domain` layer (inside each module) must not pull in Android dependencies

## Quick start

```bash
# 1. Open this directory (android/) in Android Studio
# 2. The first sync downloads Gradle and dependencies; a proxy may be required
# 3. Make sure sdk.dir in local.properties points at your Android SDK

# build
./gradlew :app:assembleDebug

# unit tests
./gradlew test

# static checks
./gradlew detekt lint
```

> ⚠️ The project path must not contain non-ASCII characters — AGP refuses to build on Windows in
> that case. Temporary workaround: `-Pandroid.overridePathCheck=true`. Do not commit that bypass.

> ⚠️ If `./gradlew test` fails with `ClassNotFoundException` for every test class while the `.class`
> files clearly exist, you are hitting an `@argfile` encoding mismatch. **Do not "fix" it by adding
> `-Dfile.encoding=UTF-8` to `org.gradle.jvmargs`** — that is what causes it. The root
> [README](../README.md#️-do-not-set--dfileencodingutf-8-for-the-gradle-daemon) has the full story.

## Version notes

The versions in `gradle/libs.versions.toml` are planning values as of 2026-09, and **must be
reconciled against your actual SDK before starting work**, using Android Studio's AGP Upgrade
Assistant. targetSdk has to satisfy Google Play's API 36 requirement (deadline 2026-08-31).

⚠️ **Version ceilings**: Compose BOM is pinned at `2026.06.01` (Compose 1.11.4). Compose 1.12.0 is
the dividing line — it requires `minCompileSdk=37` / `minAGP=9.1.0`, beyond this project's
AGP 8.13.0 + compileSdk 36 toolchain. `activity-compose 1.11.0` and `core-ktx 1.17.0` require
`minCompileSdk=36` / `minAGP=8.9.1`, exactly on the line. Run
`python tools/verify/check_aar_metadata.py --bom <version>` before upgrading any AndroidX dependency.
