# PocketAgent community source

**English** | [简体中文](README.md)

This is a **plugin source**. It is not a website and not a service — just a few static files.

PocketAgent's "plugin market" has no server. The market is simply the client pulling the
**handful of static JSON URLs** a user subscribes to, then merging, searching, and displaying them.
This directory is a complete scaffold for one such source — you can host your own source with it
directly, or read it to understand how the market works.

```
  client                          static hosting (GitHub Pages / object storage / anywhere that serves files)
  ┌──────────┐    GET index.json    ┌──────────────────────────────┐
  │ market   │ ──────────────────►  │  index.json                  │
  │ screen   │                      │  skip-splash-ad-1.0.0.pagent │
  │          │    GET *.pagent      │  focus-guard-1.0.0.pagent    │
  │          │ ──────────────────►  └──────────────────────────────┘
  └──────────┘
```

> **Why no server?** Three reasons, in order of importance:
> 1. **No gatekeeping power.** Anyone can host a source without going through us, and we carry no
>    content-moderation obligation.
> 2. **No single point of failure.** If the official source goes down, user-added sources keep working.
> 3. **Zero cost, zero operations**, and no account system — browsing and downloading need no login.
>
> The cost is **no centralized takedown capability**. If a source starts distributing malicious
> plugins, the client can only: ① show the offending entries **explicitly** as "rejected" (rather than
> silently filtering them) ② fall back on a built-in blocklist. So a source's reputation is entirely
> its own responsibility — which also means **hosting a source is endorsing it**.

---

## Directory layout

```
community-source/
├── README.md                  ← the file you are reading
├── build_source.py            ← packaging + index.json generation
├── plugins-src/               ← plugin sources (this is what humans edit)
│   ├── _template/             ← template; names starting with _ are skipped when packaging
│   ├── focus-guard/
│   │   ├── plugin.json        ← manifest
│   │   └── rules.json         ← rule pack (the L1 entry file)
│   └── skip-splash-ad/
│       ├── plugin.json
│       └── rules.json
└── site/plugins/              ← generated output (written by the script, ready to host)
    ├── index.json
    ├── focus-guard-1.0.0.pagent
    └── skip-splash-ad-1.0.0.pagent
```

`plugins-src/` is the source; `site/` is the output. **Edit only the former — the latter is generated.**

---

## Quick start

```bash
cd community-source

# package + generate index.json
python build_source.py

# after editing sources, confirm the artifacts kept up (run this in CI)
python build_source.py --check

# if your final domain is not the default one, change the download URL prefix
python build_source.py --base-url https://your-name.github.io/my-plugins
```

Adding a plugin takes three steps:

```bash
cp -r plugins-src/_template plugins-src/my-plugin     # 1. copy the template
vim plugins-src/my-plugin/plugin.json                 # 2. edit the manifest and rules
python build_source.py                                # 3. package
```

---

## Publishing to GitHub Pages

Say your Pages URL will be `https://<username>.github.io/<repo>`:

```bash
# 1. create a repo, e.g. pocketagent-plugins
# 2. push the contents of site/plugins/ into plugins/ at the repo root
mkdir -p /tmp/pub && cp -r site/plugins /tmp/pub/
cd /tmp/pub && git init && git add -A && git commit -m "publish source"
git remote add origin git@github.com:<username>/pocketagent-plugins.git
git push -u origin main

# 3. repo Settings → Pages → Source: main branch /(root)
```

Once that is done, your source URL is:

```
https://<username>.github.io/pocketagent-plugins/plugins/index.json
```

Paste it into the app's **subscription source management** screen. To let others use it, share that URL.

> ⚠️ **If you want this to be the official built-in source**, the URL must match the constant in the
> client exactly — see `BUILTIN_SOURCE_URL` in
> `android/app/src/main/kotlin/com/pocketagent/data/AppContainer.kt` (currently
> `https://pocketagent-community.github.io/plugins/index.json`). If it does not match, users who
> install the app and open the market will only see "source failed to load".

---

## Source index format (`index.json`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | ✅ | Source name, shown in the client |
| `author` | string | | Maintainer |
| `updatedAt` | string | | Last packaging date |
| `apiVersion` | int | ✅ | Currently `1`. **A version higher than the client supports causes the whole source to be rejected** |
| `plugins` | array | ✅ | List of plugin entries |

Each `plugins[]` entry:

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | string | ✅ | Must match the `id` in the bundled manifest |
| `name` | string | ✅ | |
| `version` | string | ✅ | Semantic version |
| `level` | string | ✅ | `L1_RULES` / `L2_SCRIPT` / `L3_NATIVE` |
| `capabilities` | string[] | ✅ | List of capability ids, see the table below |
| `downloadUrl` | string | ✅ | **Absolute** https URL |
| `sha256` | string | ✅ | Hash of **the entire `.pagent` file**, 64 hex characters |
| `targetApps` | string[] | | Package names this applies to. Empty means all apps, and the client warns about it |
| `description` | string | | **Shown in the market. Do not leave it empty** — it is what users decide on |
| `author` | string | | Shown in the market |
| `updatedAt` | string | | Plugin's last update date |
| `signature` | string | | Author signature (mechanism not implemented yet) |

> ⚠️ `sha256` hashes **the entire `.pagent` file**, not some file inside it. The client verifies it
> **before extracting** (`PluginInstaller`'s "order is security"), so getting it wrong means no plugin
> installs at all — and the error will say "the file may have been swapped during download".
> **Do not hand-write this value; let `build_source.py` generate it.**

---

## Plugin bundle format (`.pagent`)

It is a zip, with a few hard constraints:

- `plugin.json` must be at the **root of the bundle**, not nested inside a directory. Nested, and
  installation fails with "no plugin.json found in the plugin bundle".
- Entry names may contain forward slashes only — no `..`, no leading `/`, no drive letters, no
  backslashes. This is zip slip protection, and the client checks every entry.
- At most 512 entries, at most 32 MB total uncompressed, compression ratio at most 200.
  A normal plugin is under 2 KB; these limits only matter for zip bombs.

---

## L1 rule pack format (`rules.json`)

L1 is a declarative rule pack that executes no code. It is the backbone of the ecosystem
(expected to be ~80% of it).

```jsonc
{
  "manifest": { /* the exact same manifest as plugin.json */ },
  "rules": [
    {
      "id": "skip-splash",              // rule id, unique within the plugin
      "name": "点掉「跳过」按钮",         // shown in the execution log
      "enabled": true,
      "priority": 100,                  // higher runs first
      "match": {
        "packageName": "com.example.app",   // optional. see below
        "activity": "com.example.app.MainActivity",  // optional, prefix matching supported
        "conditions": [ /* all must hold */ ],
        "excludeConditions": [ /* any one holding means no match — the key safety mechanism */ ]
      },
      "actions": [ /* executed in order */ ],
      "constraints": { /* enforced by the core; plugins cannot override */ }
    }
  ]
}
```

### ⚠️ Why the `manifest` appears twice

Because `plugin.json` is read at **install time** (for the risk-disclosure screen), while
`rules.json` is read at **runtime**. The latter is designed to be self-contained, so it embeds a copy
of the manifest.

**The two must be byte-for-byte identical.** The consequence of drift: the market shows version
1.0.0, but what actually runs after installation is the 1.0.1 rules — and nothing anywhere reports an
error. `ExampleSourceTest` catches this before packaging.

> This is a redundancy in the current contract; it will be removed when the execution engine lands in M1.

### Condition types

| Type | Parameters | Description |
|---|---|---|
| `nodeExists` | `selector`, `timeoutMs` | A matching node exists on screen |
| `textContains` | `value` | This text appears anywhere on screen |
| `textEquals` | `value` | Exact match |
| `activityIs` | `value` | Current activity |
| `packageIs` | `value` | Current package name |
| `timeBetween` | `startHour`, `endHour` | Time range, 0–23 |

### Action types

| Type | Parameters | Required capability |
|---|---|---|
| `click` | `selector` or `x`/`y` | `action.click` |
| `longPress` | `selector`, `durationMs` | `action.click` |
| `inputText` | `text` | `action.input` |
| `scroll` | `direction`, `distancePx` | `action.gesture` |
| `swipe` | `fromX/Y`, `toX/Y`, `durationMs` | `action.gesture` |
| `back` / `home` | — | `action.gesture` |
| `openApp` | `packageName` or `deepLink` | `app.launch` |
| `wait` | `ms` | none |
| `notify` | `title`, `content` | `notification.post` |
| `callLlm` | `prompt`, `model` | `llm.call` (**costs the user money**) |

### Constraints (`constraints`)

Enforced by the core; plugins cannot change them. Loosening them has no effect, but **tightening
them saves you trouble**:

| Field | Default | Description |
|---|---|---|
| `maxTriggersPerMinute` | 6 | Maximum triggers per minute |
| `cooldownMs` | 5000 | Minimum interval between two triggers |
| `requireScreenOn` | true | Requires the screen to be on |
| `maxActions` | 20 | Maximum actions per single execution |

### Selector syntax

CSS-style. A space means "on the same node"; `>` means "child node":

```
vid:com.tencent.mm:id/menu          by resource ID
text:发送                            exact text
text^:广告                           text prefix
text$:完成                           text suffix
text*:确认                           text contains
desc:搜索                            content description
class:android.widget.Button          class name
[clickable=true]                     attribute filter
```

Combined example:

```
vid:com.tencent.mm:id/menu > [clickable=true] text^:广告
```

> **Text-only selectors are far more stable than coordinates.** Coordinates shift with resolution,
> font size, and OS version; text usually does not. The Redmi K60 has a 3200×1440 2K screen, so
> coordinates you measure on another device will be off on this one.
>
> Conversely, **a pure text selector will mis-tap when the same text appears in several places on one
> screen**. Add a hierarchy qualifier (`>`) whenever you can.

---

## Available capabilities

What L1 rule packs can use:

| id | Description | Risk |
|---|---|---|
| `screen.read` | Read text and buttons on screen | High |
| `action.click` | Tap on your behalf | High |
| `action.input` | Type on your behalf | High |
| `action.gesture` | Swipe, long press | Medium |
| `app.launch` | Open apps | Low |
| `notification.post` | Post notifications | Low |
| `llm.call` | Call the user's model (**costs the user money**) | Medium |

Unavailable to L1 (declaring one produces a warning, because it is a redundant permission):
`screen.capture`, `notification.read`, `clipboard.read`, `clipboard.write`, `storage`,
`network.request`.

**Never exposed** — declaring one means installation is rejected outright:
`payment.*`, `key.*`, `crypto.*`, `system.*`

---

## Conventions for writing plugins

**1. Click-type rules must include `excludeConditions`.**
If a plugin can click, it must exclude payment, transfer, checkout, verification-code, password, and
password-free screens in its rules. This is not optional — `ExampleSourceTest` enforces it.
A plugin that mis-taps "confirm payment" is a hundred times worse than a plugin that does not work.

**2. Request exactly the capabilities you need.**
Every extra capability means one more warning dialog for the user, who then starts wondering whether
to install it at all. An L1 rule pack declaring `storage` or `network.request` will always be asked
"what do you need that for".

**3. Write a specific description.**
"This is a plugin" is indistinguishable from leaving it blank. Say what it does, when it touches the
screen, and what it will not do. That paragraph is the only thing helping users decide on the market page.

**4. Constrain the target apps.**
An empty `targetApps` means it applies to every app; the client adds an extra warning and users
hesitate. Fill it in unless you genuinely need the broader scope.

**5. Use semantic versions for `version`.**
The client uses it to decide whether there is an update. Note that `1.10.0 > 1.9.0` — comparison is
numeric per segment, not lexicographic, so do not jump to `2.0.0` after `1.9.0` just to "avoid problems".

---

## About the two sample plugins

**Their rules are real and usable, but the selectors need calibrating on your device.**

I do not have your phone, and I cannot confirm what the "skip" button currently looks like in the
versions of Weibo, Zhihu, and Bilibili you have. The samples use **pure text selectors**
(`text*:跳过`), which are relatively insensitive to version changes but not guaranteed to match on
every version.

How to calibrate:

1. Install and enable the plugin first
2. Open the target app and check the execution log to see whether the rule triggered
3. If it did not, use the client's "inspect screen nodes" feature to find the button's actual text or resource ID
4. Edit the selector in `rules.json` and repackage

**When nothing matches, nothing happens** — there is no mis-tap. That is deliberate: not acting is far
safer than "guessing a position and tapping it".

---

## Known open questions

These are **genuinely undecided**, not forgotten:

| Question | Status |
|---|---|
| Whether `timeBetween` bounds are inclusive | Undefined. Whether `startHour=8, endHour=22` includes exactly 8:00 and 22:00 will be settled at implementation time |
| `timeBetween` across midnight | **Undefined.** Whether `startHour=23, endHour=6` means "23:00 to 6:00 next day" or an empty range is neither implemented nor tested. **Do not write across-midnight ranges for now** |
| `signature` verification | The field is reserved but the client does not verify yet. Market entries will only ever be "unsigned" |
| L2 script sandbox | `L2_SCRIPT` exists in the contract; the execution engine is not implemented. Only L1 can be written today |
| L3 native plugins | Explicitly not exposed; declaring one means installation is rejected |
| Rule `priority` conflicts | The execution order for equal priorities is undefined |

---

## Validation

After editing sources, run:

```bash
python build_source.py --check          # do artifacts match the sources
python tools/verify/run_logic_tests.py  # includes ExampleSourceTest, which validates the sample plugins
```

`ExampleSourceTest` does **real validation**, not a formality — it calls the client's
`PluginValidator` and the real deserializers and checks the sample plugins item by item:

- The manifest parses (using the client's strict parser, not Python's `json.loads`)
- Validation produces no ERROR (warnings are printed)
- The manifest embedded in `rules.json` is identical to `plugin.json`
- The file `entry` points at actually exists in the bundle
- All capabilities are within what L1 may use
- **Every plugin that can click has `excludeConditions` on every rule**

If any of these fail, it surfaces **before you package**.

---

## License

The sample plugins in this directory are GPL-3.0, matching the project. You may license your own
source and plugins however you like — but state it in the manifest.
