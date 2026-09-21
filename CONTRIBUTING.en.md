# Contributing

**English** | [简体中文](CONTRIBUTING.md)

Thanks for your interest in PocketAgent.

Please read this whole file before submitting anything. **This project's three red lines are
non-negotiable**; PRs that violate them are closed without further discussion.

---

## 🚫 Three red lines

### Red line 1: no payments, no financial operations

**Forbidden** to submit any rule or plugin targeting these screens:

- Payments, billing, transfers, receiving money
- Password entry, verification-code entry, fingerprint/face payment
- Bank cards, ID documents, statements, top-ups, withdrawals, loans

The app blocks these screens at the code level. **Any attempt to bypass the block is treated as a
malicious contribution.**

### Red line 2: no coupon farming, no cheating

**Forbidden** to submit rules or plugins for:

- Red-packet grabbing, auto check-in for coupons, order brushing, flash-sale scripts
- Game assistants, cheats, auto-grinding
- Bulk registration, bulk operations, scraping other people's data
- Anything whose purpose is "getting around a third-party app's risk controls"

### Red line 3: no forbidden capabilities

Plugins and rules **must not** contain these capabilities:

```
payment.*    key.*    crypto.*    system.*
```

These are not exposed at the architectural level, and requests for them are rejected automatically.

---

## What you can contribute

### 1. Rule packs (L1) — most recommended, lowest barrier

Perform a specific action on a specific screen of an app, under specific conditions.

**The correct workflow for writing a rule:**

1. Open the target screen on a real device
2. Inspect the node tree with the in-app **snapshot inspector**
3. Confirm the target node's `vid` (resource ID), `text`, hierarchy, and `clickable` state
4. Write the minimal match condition (**match only what is necessary — do not over-specify**)
5. Test on a real device: change the window content, go back and re-enter, and watch for repeated triggers
6. Submit a PR describing the use case and known false-trigger risks

**Selectors must include hierarchy — never match on text alone.**
A bare `text:广告` will match in a menu, a list, and a dialog at the same time, causing one match to
produce multiple taps.

Correct form:

```
vid:com.example.app:id/menu > [clickable=true] text^:广告
```

### 2. Device compatibility reports

We have no budget to buy test devices. If you hit inconsistent permission-grant paths, a service being
killed by the system, an overlay that will not show, or similar issues on a particular ROM,
**please report it**.

Use the [device compatibility issue template](.github/ISSUE_TEMPLATE/rom_compat.yml) and include:
device model, ROM version, Android version, and screenshots of the actual paths.

### 3. Plugins (L2 / L3)

See the [plugin API docs](android/plugin/api/) and the [plugin template repo](#) (to be created).

### 4. Documentation, translation, testing

Also welcome.

---

## Submission conventions

### Branches

```
main           releasable state, protected
 └── develop   integration branch
      ├── feature/<module>-<short description>
      ├── fix/<short description>
      └── docs/<short description>
```

### Commit messages (Conventional Commits)

```
<type>(<scope>): <description>

type:  feat | fix | refactor | perf | test | docs | build | ci | chore | security
scope: module name, e.g. action / perception / plugin-rules / display
```

Examples:

```
feat(plugin-rules): 新增选择器对 desc 属性的支持
fix(display): 修复虚拟屏释放顺序导致的黑屏
security(crypto): Key 使用后强制清零，修复异常路径泄漏
```

> Note: this project's commit messages are written in Chinese — the examples above are the real
> convention, not placeholders. English descriptions are fine too; just keep the `<type>(<scope>):`
> prefix intact, since it is what tooling parses.

**`security` commits must be reviewed by a maintainer.**

---

## Code conventions (for core development)

| Rule | Description |
|------|------|
| No `!!` | Unless a comment explains why it cannot be null |
| No swallowed exceptions | `catch (e: Exception) {}` gets sent back |
| Public APIs need KDoc | Explain **why**, not just **what** |
| No file over 500 lines | Split it if it grows past that |
| Core logic test coverage ≥ 70% | |

### Patterns that get a PR sent straight back

| Problem | Reason |
|------|------|
| Plaintext keys written to disk or logs | Security red line |
| Logging directly, bypassing `LogSanitizer` | Same as above |
| Depending on `AccessibilityService` instead of the `ActionExecutor` abstraction | Architectural constraint (Android 17 tightening) |
| Adding an action path not covered by `SafetyGuard` | Safety floor |
| Screenshot `Bitmap` without an explicit recycle path | Memory leak |
| Introducing a new sensitive permission | Requires maintainer approval |
| Hardcoding credentials of any kind | Security red line |

---

## ⚠️ Pre-submit checklist

```
□ I have not submitted any credentials, tokens, keys, or signing files
□ I have not submitted snapshot files containing real screen content
□ My rules do not touch payment, password, or verification-code screens
□ My rules do not involve coupon farming or game assistance
□ My rules do not request forbidden capabilities
□ I have verified my selectors with the snapshot inspector
□ I have documented the use case and known false-trigger risks
□ I have tested on a real device (not just read the code and assumed it works)
□ My commit messages follow Conventional Commits
```

---

## License

By submitting, you agree to license your contribution under [GPL-3.0](LICENSE).

---

## Code of conduct

- Critique the work, not the person. Criticizing code is fine; attacking people is not.
- Be patient with newcomers' questions. You were new once.
- Do not discuss sensitive topics unrelated to this project.
- Report security vulnerabilities **privately** — do not disclose details in a public issue.
