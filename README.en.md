# PocketAgent

**English** | [简体中文](README.md)

> A BYOK Android AI agent — bring your own model, let your phone get things done.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](#requirements)
[![Status](https://img.shields.io/badge/Status-M0%20in%20progress-orange.svg)](#project-status)

---

## ⚠️ Project status: M0 essentially complete; the P1 gateway core has landed

**It builds into an installable APK. The gateway (model access layer) is implemented and fully green,
but the agent loop — the part where the AI actually "does things for you" — is not wired up yet.**

Done:

- ✅ Full project designs (v1.0 / v2.0 / v3.0) and an M0 technical validation handbook (18 experiments)
- ✅ Project skeleton (35 Gradle modules, 128 Kotlin source files, ~37k lines)
- ✅ Provider layer (OpenAI-compatible protocol, 11 vendors covered) + text-to-speech (TTS)
- ✅ Safety guardrails (sensitive screens / sensitive widgets / dangerous actions / rate limiting + audit)
- ✅ **Plugin system** — contract, three-tier classification, capability allowlist, forbidden-prefix blocking
- ✅ **Plugin market** — browse / search / download / install. No server; subscription-based static sources
- ✅ **Local import** — `.pagent` bundles, itemized risk disclosure, high-risk plugins require typing a confirmation phrase
- ✅ **Plugin management + subscription source management** — inspect capabilities and risk, uninstall, add/remove/restore sources
- ✅ **Community source scaffold** — reproducible packaging, index generation, 2 sample plugins + a template
- ✅ **API key management UI** + multi-key / multi-model routing layer (`modelrouter`)
- ✅ **`GatewayCore`** (1.4.1–1.4.5) — routing / budget circuit-breaking / decryption / forwarding / metering
- ✅ **`HttpGatewayServer`** (1.4.6) — loopback HTTP + SSE, for dsh to consume
- ✅ **`GatewayTokenProvider`** (1.4.7) — local token issuance + constant-time comparison + six hardening rules
- ✅ **667 offline unit tests passing** (including 44 real-socket integration tests)
- ✅ **All five P0 on-device experiments reached conclusions** (see "M0 on-device results" below)
- ✅ **A debug APK that builds**

Not done (honest list):

- ❌ **Perception / action / agent loop** — the part where the AI actually "does things for you".
  `:perception` / `:action` / `:agent` currently hold interface contracts only, with no implementations
- ❌ **The three cross-cutting components the loop needs** — `AgentBudget` / `TaskCheckpoint` / `PerceptionLadder`
- ❌ **Wiring the gateway into dsh's config** (1.4.6c) and **wiring the gateway into the agent loop** (1.4.8)
- ❌ **Plugin runtime** — the execution engine for the three plugin tiers (`:plugin:runtime` / `:script` etc. are empty modules)
- ❌ **Memory, self-update, onboarding, contribution, channel distribution** — those modules have not been started
- ❌ Release signing
- ❌ **Built-in source is not live yet** — the `pocketagent-community.github.io` repo does not exist,
  so on first launch the market shows "source failed to load". This is an **honest report**, not a bug;
  the subscription source screen exists precisely as the way out (see [`community-source/`](community-source/))
- ❌ **Virtual-display execution** — **rejected** by on-device measurement (see below)

### What the current APK can and cannot do

**Can**: install / view / uninstall plugins, manage subscription sources, manage API keys and model config.
Plugin bundles are fully validated — hash checking, zip slip / zip bomb protection, manifest rules,
forbidden-capability blocking. None of it is skipped.

**Cannot**: it **does not read the screen, does not tap**. Installed plugins do not run either, because
the execution engine is not implemented. The gateway itself already works (667 tests cover it), but no UI
hands it to a user yet — until the agent loop is connected, it is **a verified foundation**, nothing more.

> The plugin system and the gateway were built out first because they are **the only parts that can be
> fully verified without accessibility permissions, without Shizuku, and without a real device**. And they
> happen to be the most safety-critical parts: validation, extraction, capability allowlisting, key
> encryption and forwarding — code like that does not fail loudly when it is wrong. It quietly lets a
> malicious plugin through, or quietly leaks a key.

### M0 on-device results (2026-09-22, Redmi K60 / HyperOS / Android 15)

| Experiment | Verdict | One-line conclusion |
|---|:---:|---|
| **P0-1** can dsh run | ✅ pass | dsh really runs (`QUOTA: Insufficient Balance` proves the whole startup chain is intact) |
| **P0-2** sideload restricted settings | ✅ pass | HyperOS **does not** enforce `ACCESS_RESTRICTED_SETTINGS` → the biggest distribution risk is gone |
| **P0-3** EX-16 virtual display | ❌ **rejected** | The secondary display can be created and injected into, but **cannot launch apps or take screenshots** |
| **P0-4** memory budget | ✅ pass | Bare app idle PSS **119 MB** (budget floor 150 MB) — but this is a lower bound, excluding Node/dsh |
| **P0-5** power baseline | ✅ pass | Readings usable; a single task cannot be measured (±1% quantisation error swamps the signal) |

Details: [`tools/p0/reports/2026-09-22-结论汇总.md`](tools/p0/reports/2026-09-22-结论汇总.md).

---

## What this is

An Android app that lets you drive an agent — one that can read the screen and operate apps for you —
using **your own LLM API key** (OpenAI / DeepSeek / Qwen / Volcano Ark / Claude / Gemini / local models…).

### How it differs from an "AI phone assistant"

| Dimension | System-level AI assistant | PocketAgent |
|------|---------------|-------------|
| Model | Vendor's own, server-side inference | **Any model you bring, swappable at any time** |
| Data | Uploaded to the vendor's servers | **No intermediary server; connects straight to your provider** |
| Cost | Vendor pricing | **Billed by your own provider; this app has zero inference cost** |
| Permission source | Vendor's system-level partnership | User grants manually |
| Transparency | Black box | **Every step visible, abortable, auditable** |

### What it is, and what it is not

**Is**:

- A transparent, model-swappable, data-sovereign phone automation tool
- A plugin platform you can extend by writing your own rules

**Is not**:

- ❌ A red-packet grabbing / brushing / coupon-farming tool
- ❌ A game assistant / cheat
- ❌ Anything involving payments, transfers, or password entry
- ❌ Cracking, injecting into, or hooking third-party apps

---

## Core design principles

These are written up in the [design docs](docs/) and they constrain every line of code:

1. **No backend of our own** — all data stays local, model requests go from the client straight to
   the provider you configured. This is both the key to zero cost and the moat that keeps this a
   "tool" rather than a "service".
2. **Multi-channel abstraction in the action layer** — accessibility / Shizuku / IME / overlay-prompt
   are four pluggable channels that can degrade at runtime. **No module may depend on
   `AccessibilityService` directly** (Android 17 is tightening it).
3. **The perception layer does not depend on the accessibility tree** — everything goes through
   `ScreenSnapshot`: prefer the tree when available (cheaper), fall back to screenshots when not.
4. **Payments are voluntarily out of scope** — hitting a sensitive-screen rule stops immediately,
   discards the screenshot, and hands control back to the user. Rules can only be tightened,
   never configured to bypass.
5. **Manual guidance mode is a first-class product form** — not a crippled version, but the
   lifeline for when policy tightens.
6. **Keys never touch disk in plaintext** — Keystore + AES-GCM + SQLCipher; not in logs, not in
   crash reports, not in backups.
7. **The core provides *capabilities*; plugins provide *intent*** — every plugin call must go
   through the capability proxy. Plugins never get the plaintext API key, never get raw node
   objects, and cannot bypass the safety guard.
8. **Credentials are always injected at runtime, never hardcoded** — including GitHub reporting credentials.

---

## Architecture overview

```
┌──────────────────────────────────────────────────────────┐
│                    app (UI shell)                         │
│  chat │ key mgmt │ tasks │ plugins │ settings │ onboarding │ contribute │
├──────────────────────────────────────────────────────────┤
│  plugin layer  contract / runtime / rule engine / JS sandbox / store   │
├──────────────────────────────────────────────────────────┤
│  display layer  virtual display mgmt / preview / coordinate mapping    │
├──────────────────────────────────────────────────────────┤
│  domain layer  pure Kotlin, zero Android dependencies     │
├──────────────────────────────────────────────────────────┤
│  data layer    Room / OkHttp / Keystore / update / channel│
├──────────────────────────────────────────────────────────┤
│  platform layer  accessibility / Shizuku / MediaProjection / overlay   │
└──────────────────────────────────────────────────────────┘
```

35 modules in total — see [`android/README.en.md`](android/README.en.md) and the [design docs](docs/) (currently Chinese only).

### Three-tier plugin system

| Tier | Form | Capabilities | Barrier to entry | Expected share |
|:---:|------|------|:---:|:---:|
| **L1** | Rule pack (declarative JSON) | Screen matching + conditions + actions | Low | 80% |
| **L2** | JS script (sandboxed) | L1 + logic + core API calls | Medium | 18% |
| **L3** | Native APK (signature-checked) | Full Android capabilities | High | 2% |

**L1 is the workhorse** — because the barrier to community contribution determines how big the
ecosystem gets. Writing a rule should not require knowing how to program.

### Plugin sources: the market has no server

The "plugin market" is not a server. It is **a handful of static JSON URLs**. The client pulls the
sources a user subscribes to, merges them, searches them, and displays them. The benefits, in order
of importance:

1. **No gatekeeping power** — anyone can host a source without going through us, and we carry no
   content-moderation obligation
2. **No single point of failure** — if the official source goes down, user-added sources keep working
3. **No account system**, zero operations, zero cost

The cost is **no centralized takedown capability**. If a source starts distributing malicious plugins,
the client can only **show the offending entries explicitly as "rejected"** (rather than silently
filtering them out) and fall back on a built-in blocklist. So a source's reputation is entirely its
own responsibility.

[`community-source/`](community-source/) is a **complete, publishable source scaffold** containing
two real sample plugins, a template, and the packaging script:

```bash
cd community-source
python build_source.py          # package + generate index.json
python build_source.py --check  # verify artifacts match sources (good for CI)
```

The resulting `site/plugins/` can be dropped straight onto GitHub Pages. **Anyone can be a source** —
that design promise only holds if standing up your own source is cheap enough, which is why this
directory and its [README](community-source/README.en.md) are infrastructure, not sample code.

> ⚠️ Packaging is **reproducible**: zip timestamps, permission bits, entry order, and compression
> level are all fixed, so identical input always yields an identical sha256. This is not fussiness —
> a hash mismatch makes the client refuse to install before extraction, and the error says
> "the file may have been swapped during download", pointing the user in completely the wrong direction.

---

## Requirements

| Item | Requirement |
|----|------|
| Android version | **12 (API 31) or later** |
| Storage | ~100 MB (more with local models) |
| Network | Used only to reach the model provider you configure |
| Optional dependency | [Shizuku](https://shizuku.rikka.app/) — required for virtual display mode |
| Primary target device | Redmi K60 (HyperOS) |

---

## Installation

There is no official release channel yet, so you have to build it yourself:

```bash
cd android && ./gradlew assembleDebug
# output: android/app/build/outputs/apk/debug/app-debug.apk (~61 MB)
```

Transfer the APK to your phone and open it from a file manager. On first install you will need to
allow "install unknown apps" in system settings.

> ⚠️ This is currently a **debug-signed** build (`CN=Android Debug`) with a `.debug` package-name
> suffix, so it can coexist with a future release build. The release signing strategy is undecided —
> community distribution requires every version to be signed with the same keystore (otherwise users
> cannot install updates over the top), and whether that keystore should live in a public repo is a
> call the maintainer has to make.

> ⚠️ **Android 13+ "restricted settings"**: a sideloaded app is flagged as restricted by the system,
> which blocks subsequent permission grants (accessibility in particular), and **the flag is reset
> after an in-place update**. This is the number-one risk on the community-distribution path; see the
> community-distribution chapter of the design docs. M0 does not use accessibility permissions, so it
> is unaffected for now.

---

## Roadmap

| Phase | Content | Status |
|------|------|:---:|
| M0 | Technical validation (18 experiments, including 2 decisive ones) | ✅ **Essentially complete** (all five P0 experiments concluded; both decisive questions answered, virtual display rejected) |
| M1 | MVP + plugin framework | ✅ Plugin framework landed; MVP awaits the agent loop |
| M2 | Task engine + script plugins + ~~virtual display integration~~ | 🔄 Gateway ready, engine not started (virtual display rejected) |
| M3 | UX polish + dev tools + GitHub channel | ⏳ |
| M4 | Open-source governance + release | ⏳ |
| M5 | Plugin ecosystem operations | ⏳ |

> **Execution-mode change (after on-device measurement)**:
> ~~Virtual display~~ (**rejected**) → **freeform window (primary form)** → full-screen takeover → manual guidance.
>
> Cost: we lose the "isolated background execution that does not disturb your phone" selling point.
> But `input -d <id> tap` injection does work, so **the bottleneck is down to the single "launch" step** —
> if Android ever opens up cross-display launching, the virtual display can be revived.

See [`docs/M0技术验证与工程落地手册-v1.0.md`](docs/) and [`docs/社区分发版方案-v3.0.md`](docs/) (in Chinese).

---

## Safety boundaries

**The project enforces the following limits so they cannot be bypassed** (enforced in code, not by
good intentions):

| Limit | Description |
|------|------|
| 🚫 Payments and transfers | Stop on detection, discard the screenshot, hand back to the user |
| 🚫 Password and verification-code entry | Never read, never type |
| 🚫 Financial apps | Package-name blocklist; allowed to open only as far as the home screen |
| 🚫 Red-packet grabbing / brushing / coupon farming | Explicitly forbidden; such rule contributions are not accepted |
| 🚫 Game assistants | Explicitly forbidden |
| 🚫 Plugins requesting `payment.*` / `key.*` / `crypto.*` / `system.*` | Installation rejected outright |

**If you find any way to bypass these limits, please treat it as a security vulnerability** — see
[SECURITY.md](SECURITY.md) (to be added).

### Why there is no "read the disclaimer to unlock" switch

We get this proposal often. The answer is no — not out of caution, but because **such a switch is
structurally ineffective**.

First, separate three kinds of limit — they are not the same thing:

| Kind | Example | Can a disclaimer waive it? |
|------|---------|:---:|
| **Product boundary** | Whether plugins may call `payment.*` | **No gate needed** — a product decision, not a legal red line |
| **User-assumed risk** | Storing an API key in plaintext | **Yes**, if informed consent holds (specific / clear / understandable / revocable) |
| **Third parties + public law** | Completing a payment, typing a verification code | **No** |

Almost all of the six limits above fall into the **third** category. For those, a disclaimer offers
no protection at all:

1. **A disclaimer only allocates risk between the contracting parties.** A user clicking "I agree"
   does not mean the bank, the merchant, or the payee agreed to have its interfaces automated —
   that is a **third-party contract**, and the user has no standing to waive it on their behalf.
2. **Public-law liability cannot be contracted away.** Where criminal law applies (e.g. aiding
   information-network crimes; providing tools to intrude into or illegally control computer
   information systems), an agreement between private parties is not binding on the **state**.
3. **A disclaimer can become evidence against you.** A document that spells out the risks in detail
   is precisely written proof that you *knew* the risks and shipped anyway.
4. Under Art. 497 of China's Civil Code and Art. 26 of its Consumer Protection Law, standard-form
   clauses that exempt the drafter's own liability may be **void outright**.

**Open source also makes the switch pointless.** GPL-3.0 forbids additional restrictions, so anyone
can fork and delete the gate — a real abuser is through in five minutes, while only people who would
never abuse it get stopped. **The cost lands on exactly the wrong group.**

So we build the prohibitions as **capabilities that do not exist**, not **settings that can be
turned off**: `payment.*` is simply not implemented in `PluginHost`. You cannot disable something
that isn't there, and forking it out would mean rewriting the whole capability layer.

### If you genuinely need such a capability

**Ship a narrower capability, not a switch.** Instead of "pay on the user's behalf", offer **payment
assist**: fill in the form, navigate to the payment page, and **hand the final step back to the
user**. What users actually want is usually not "let the AI pay" but "stop making me type all this".

---

## Privacy

- **No account system**, no personal information collected
- **No servers of our own**; all data stays on your device
- **Model requests go straight to the provider you configured**, with no intermediary
- **Screen content is read only while a task runs**, destroyed when the task ends, never uploaded
- Crash reporting is **off by default**; it requires your explicit opt-in and its contents are redacted

---

## Development and verification

### Full build (requires JDK 17 + Android SDK 36)

**First tell Gradle where your SDK is**, otherwise the build dies during configuration:

```bash
# pick one
export ANDROID_HOME=/path/to/android-sdk        # environment variable
echo "sdk.dir=/path/to/android-sdk" > android/local.properties   # or a local properties file
```

`local.properties` is excluded by `.gitignore` and is **per-machine** — it records a local path, so
committing it only creates noise for everyone else.

```bash
cd android
./gradlew test          # unit tests
./gradlew assembleDebug # build APK → app/build/outputs/apk/debug/app-debug.apk
```

Use JDK **17** (not 21+; AGP 8.x is unstable on newer JDKs).

> ⚠️ **The project path must not contain non-ASCII characters** (a Chinese directory name, for
> instance) — AGP refuses to build on Windows in that case. Temporary workaround:
> `./gradlew assembleDebug -Pandroid.overridePathCheck=true`.
> This is a local workaround — **do not put it in `gradle.properties`**; collaborators should not
> inherit the bypass.

> ⚠️ If `./gradlew` hangs while downloading the Gradle distribution, it is almost certainly a network
> problem. The wrapper in this repo points at a Tencent Cloud mirror by default
> (`services.gradle.org` is unreachable from mainland China in practice; `curl` returns `000`).
> The comments in `gradle/wrapper/gradle-wrapper.properties` show how to switch back to the official URL.

### ⚠️ Do not set `-Dfile.encoding=UTF-8` for the Gradle daemon

This one bit us once and deserves its own section, because **the error message points nowhere near
the real cause**.

Symptom: under `./gradlew test`, **every single test class** fails with

```
java.lang.ClassNotFoundException: com.pocketagent.plugin.api.PluginBundleTest
```

even though the `.class` files are sitting right there on disk and `javap` reads them fine. JUnit's
own classes show up in the stack trace — which makes it very easy to conclude "the test code is broken".

The real cause is an **encoding mismatch**:

| Step | Encoding used |
|---|---|
| Gradle writes the test worker's classpath into an `@argfile` | the daemon's `file.encoding` |
| The JVM reads `@argfile` | `sun.jnu.encoding` (**platform encoding, cannot be changed with `-D`**) |

Force the daemon to UTF-8 while the platform encoding is GBK (Chinese Windows) and paths get mangled:

```
passed directly: D:/手机agent开发/android/...        → loads fine
UTF-8 argfile:   D:/鎵嬫満agent寮?鍙?/android/...  → ClassNotFoundException
```

The directory in the classpath cannot be found, so the test class cannot load. Meanwhile JUnit's own
jars live under a pure-ASCII path (`~/.gradle/caches`), so they load fine — which misleads you further.

**So `gradle.properties` deliberately omits this flag** — without it, the daemon and the worker both
use the platform encoding, they agree, and both Linux and Chinese Windows work. The full derivation
is in that file.

> This trap only triggers when the **project path contains non-ASCII characters**. Put the project
> under a pure-ASCII path like `D:\pocketagent` and it will not happen either way — but relying on
> "the path happens to be ASCII" to dodge an encoding bug is worse than just aligning the encodings.

### Version ceilings (read before changing dependencies)

| Dependency | Current | Why it cannot go higher |
|---|---|---|
| Compose BOM | `2026.06.01` | From 1.12.0 it requires `minCompileSdk=37` / `minAGP=9.1.0`, beyond this project's toolchain |
| `activity-compose` | 1.11.0 | Requires `minCompileSdk=36` / `minAGP=8.9.1` — **right on the line** |
| `core-ktx` | 1.17.0 | Same as above |

### Six verification scripts

```bash
python tools/verify/run_logic_tests.py                     # offline unit tests, no Android SDK needed
python tools/verify/check_version_catalog.py android       # reconcile libs.* accessors
python tools/verify/check_module_deps.py android           # find missing project dependencies
python tools/verify/check_kt_quotes.py android             # ASCII quotes misused in Chinese copy
python tools/verify/check_aar_metadata.py --bom 2026.06.01 # read compileSdk ceilings from aars
python community-source/build_source.py --check            # do source artifacts match the sources
```

The first five live under `tools/verify/`; the last one travels with the community source (it
validates that directory's artifacts, and keeping it there is what stops it being forgotten).

**`run_logic_tests.py`** — the bulk of this project's high-risk logic (SSE parsing, key redaction,
token estimation, cost calculation, plugin validation, zip protection) lives in **zero-Android-dependency**
pure Kotlin modules, but their Gradle modules declare `com.android.library`, so without an SDK they
will not even compile. This script bypasses Gradle and AGP, driving the Kotlin command-line compiler
directly to build them and run JUnit. Dependencies (~70 MB) are downloaded into a user-level cache
directory and never enter the repo.

> This is only a **stopgap for machines without an Android SDK**. Once the SDK is in place,
> `./gradlew test` is the sole authority, and both must pass.
>
> ⚠️ **But it has an important blind spot: it cannot see undeclared module dependencies.**
> It feeds every module into **a single kotlinc invocation**, so when `:agent` references a type from
> `:action`, it resolves "by the way" even if `build.gradle.kts` never declared that dependency.
> Gradle compiles each module separately and will fail outright with `Unresolved reference`.
> That is exactly why the next script exists.

**`check_module_deps.py`** — the per-module `build.gradle.kts` files are produced by a generator,
and that generator **hardcodes a single `project(":core:common")`** with no mechanism to express
inter-module dependencies. So `:provider:openai-compat` imported 13 symbols from `:provider:api`
without declaring the dependency, and `:agent` used `:action` and `:perception` without declaring
them either — modules that fail to compile under Gradle, while the offline test runner sees nothing wrong.

This script scans source for `import` statements and **fully-qualified references**, maps them back to
modules, and reconciles that against the declared `project(":...")` entries. It turns an eight-minute
build-feedback loop into two seconds.

**`check_version_catalog.py`** — Gradle version-catalog accessors (`libs.androidx.core.ktx`) are
resolved at **configuration time**. A typo in any of the 35 modules fails configuration, and the error
points at a module you are not even using. This script reconciles all 31 `build.gradle.kts` files
before the build runs.

**`check_aar_metadata.py`** — downloads an aar, reads the
`META-INF/com/android/build/gradle/aar-metadata.properties` inside, and prints `minCompileSdk` and
`minAndroidGradlePluginVersion`. Run it before upgrading any AndroidX dependency and you skip the
four-minute build failure.

**`check_kt_quotes.py`** — when writing quotes in Chinese copy it is easy to type an ASCII `"`, which
in Kotlin is a string delimiter and terminates the string early. The mistake is nearly invisible in a
monospace font, and the error line points several lines further down.

---

## Contributing

Contributions are welcome, especially:

- **Rule packs (L1)** — lowest barrier, most direct benefit
- **Device compatibility reports** — we have no budget to buy test devices; your feedback is valuable
- **Documentation and translation**

Please read [CONTRIBUTING.en.md](CONTRIBUTING.en.md) first; it lists what must not be submitted.

**Three red lines to read before contributing:**

1. Do not submit rules targeting payment, transfer, password, or verification-code screens
2. Do not submit red-packet grabbing, brushing, coupon-farming, or game-assistant rules
3. Do not request forbidden capabilities in rules or plugins

---

## License

[GPL-3.0](LICENSE)

This is a non-profit open-source project. GPL-3.0 was chosen to ensure that **any derivative must also
be open source**, preventing anyone from building a closed-source skin or abusing it commercially.

---

## Disclaimer

This software is provided "as is", without warranty of any kind.

Users bear sole responsibility for:

- Account risk-control or bans that may result from using this software to operate third-party apps
- Any loss caused by misoperation
- Fees incurred through third-party model providers

**This project does not provide, proxy, or resell any model service.** Model calls are initiated by
third-party accounts that users supply themselves, and the service relationship between the user and
that provider has nothing to do with this project.

### What this disclaimer does not cover

The paragraph above only allocates risk **between this project and the user**. It does **not**:

- substitute for the terms of service of third parties (banks, payment institutions, e-commerce
  platforms, game vendors) — a user accepting this disclaimer is not those parties agreeing to have
  their interfaces automated;
- exempt anyone from **public-law liability** (administrative or criminal);
- authorise the user to bypass any limit listed under [Safety boundaries](#safety-boundaries).

In one sentence: **a disclaimer can allocate risk between you and us; it cannot change our
obligations toward third parties and the law.**
