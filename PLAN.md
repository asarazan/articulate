# Articulate — Implementation Plan

**Coordinates:** `net.sarazan.articulate` · **Date:** 2026-07-30 · **Status:** pre-implementation, pending decisions listed at the end.

Derived from the Decision Brief — Agent Constraints (Notion, read in full 2026-07-30). All *Settled Decisions* from the brief are treated as hard constraints and are not re-derived here. Constraint concerns are flagged in §9, not acted on.

---

## 0. Architecture at a glance

Three layers, dependency-ordered:

1. **`core`** — pure Kotlin, zero Gradle API. Parser for Android `strings.xml`, the neutral in-memory model, the placeholder/escape converter, the locale mapper, and the canonical `.xcstrings` serializer. Everything in milestones 1–3 lives here and is testable in plain JUnit at unit-test speed.
2. **`plugin`** — the Gradle plugin(s): tasks (`generateStrings`, `verifyStrings`), the AGP variant wiring, the DSL. Thin; delegates all logic to `core`. Milestones 4–5.
3. **`lint`** — the Swift key-parity scanner. Milestone 6. Also pure logic in `core` with a task shell in `plugin`.

This split exists so the hard, correctness-critical work (serialization, escaping, plurals, locales) never needs Gradle TestKit to test, and so the plugin module stays small enough to audit for configuration-cache violations.

---

## 1. Milestone 1 — Canonical serializer + byte determinism (full detail)

**Goal:** given an in-memory catalog model, emit the exact byte sequence Xcode itself would produce, such that opening the file in Xcode is a no-op (no rewrite, no diff). This is the enforcement mechanism from the brief: determinism + CI no-op check, because JSON permits no "DO NOT EDIT" header.

### 1.1 In-memory model (`core`)

Immutable data classes, no JSON types leaking out:

```
StringCatalog(sourceLanguage: String, entries: SortedMap<Key, Entry>)
Entry(comment: String?, extractionState = MANUAL, localizations: SortedMap<LocaleTag, Localization>)
Localization = Unit(value, state = TRANSLATED) | PluralVariations(SortedMap<PluralCategory, Unit>)
```

- `state: "translated"` and `extractionState: "manual"` are constants, not options (settled decision — the catalog is a build artifact; state lives upstream).
- The catalog `version` is **not** in the model. It is a single constant, `CanonicalFormat.VERSION`, sourced from the human fixture (§1.4). Until the fixture lands it holds a clearly-marked placeholder (`"1.0" /* PROVISIONAL — pending fixture, see fixtures/xcode/README.md */`).

### 1.2 Serializer

Hand-rolled JSON writer — **not** kotlinx-serialization pretty-printing — because we must match Xcode's idiosyncratic output byte-for-byte, and Xcode's style (e.g. the `"key" : value` spaced-colon separator seen in real catalogs) is not what any standard pretty-printer emits.

Every formatting behavior that the fixture will pin down is a parameter of one object, `CanonicalFormat`, so the fixture landing changes exactly one file:

| Parameter | Provisional value | Fixture decides |
|---|---|---|
| `VERSION` | `"1.0"` | actual value current Xcode emits (`1.1` expected per brief refs) |
| Indent | 2 spaces | width and character |
| Key/value separator | `" : "` | exact spacing |
| Key ordering comparator | UTF-16 code-unit ordinal | Xcode's actual sort (ordinal vs code-point vs localized) |
| String escaping | escape `"` `\` control chars only; UTF-8 literal otherwise | which non-ASCII chars Xcode `\u`-escapes, forward-slash policy |
| Trailing newline | present | present/absent |
| Encoding / EOL | UTF-8 no BOM, LF | confirm |

Hard invariants regardless of fixture (settled): sorted keys, no timestamps, no environment-dependent output, LF.

**Determinism rules baked into `core`:**
- All sorting via an explicit comparator on code units — never `String.compareTo` under a default locale, never `toLowerCase()` without `Locale.ROOT` (Turkish-İ class of bug).
- No iteration over `HashMap`; sorted maps only.
- No `System.currentTimeMillis()`, no `user.name`, no absolute paths in output.

### 1.3 Parser (for round-trip only)

A lenient reader (`kotlinx.serialization` `JsonElement` is fine here — parse side needn't be canonical) that loads an `.xcstrings` file into the model. Needed for the fixture round-trip test and later for `verifyStrings` diagnostics (nice diffs). Unknown fields → recorded and surfaced as warnings, not errors (Apple adds fields; accepted risk in the audit).

### 1.4 The pending human fixture — protocol, not blocker

The hub pre-flight checklist (in progress) produces the observed-Xcode data. The plan treats it as a **test fixture with a defined drop location**, so nothing blocks and nothing is guessed:

```
core/src/test/fixtures/xcode/
  README.md            ← instructions for the human (written in this milestone)
  handwritten.xcstrings  ← the minimal catalog you authored (PENDING)
  opened.xcstrings       ← same file after open+save in Xcode (PENDING)
  xcode-version.txt      ← e.g. "Xcode 26.0 (17A123)" (PENDING)
```

Tests gated on the fixture are written now but marked `@Disabled("pending xcode fixture — see fixtures/xcode/README.md")`; CI reports them as skipped, not green. When the files land:

1. `RoundTripTest`: `serialize(parse(opened.xcstrings)) == bytes of opened.xcstrings` — the definitional test of canonical form.
2. `CanonicalFormat` parameters updated to match; `VERSION` un-provisioned.
3. The diff between `handwritten` and `opened` is transcribed into `CanonicalFormat`'s KDoc as the normalization spec of record.

Everything else in milestones 1–3 proceeds against the provisional format; only `CanonicalFormat` values shift when the fixture arrives, and the golden corpus (§2) regenerates mechanically (one gradle task, reviewed diff).

### 1.5 Milestone 1 tests

- Byte-identity: serialize same model twice → identical arrays; serialize under `Locale("tr")` default → identical.
- Property-ish: arbitrary model permutation (insertion order shuffled) → identical bytes.
- Golden: one small catalog checked in as expected bytes.
- Round-trip (disabled until fixture): §1.4.

**Exit criteria:** serializer emits stable bytes for a representative catalog; fixture protocol in place; round-trip test written and parked.

---

## 2. Milestone 2 — Golden-file corpus: escaping, placeholders, plurals (full detail)

**Goal:** the moat. Per the brief: a converter that's 95% right is worse than none — the failing 5% are silent runtime bugs in shipped translations. Therefore: every semantic rule is a golden case, and **anything not explicitly supported is a hard parse/convert error, never a pass-through guess.**

### 2.1 Corpus mechanism

Directory-per-case golden tests, discovered automatically:

```
core/src/test/corpus/
  <case-name>/
    input/values/strings.xml          (+ values-de/, values-pt-rBR/, … as needed)
    expected.xcstrings                ← byte-exact expectation
  <error-case-name>/
    input/values/strings.xml
    expected-error.txt                ← substring(s) the failure message must contain
```

- A JUnit `@TestFactory` walks the tree; each directory is one test. Adding a case = adding files, no code.
- Every `expected.xcstrings` must itself round-trip through the canonical serializer (self-consistency check run over the whole corpus).
- A `regenerateCorpus` Gradle task rewrites all `expected.xcstrings` from current code — used exactly when `CanonicalFormat` changes (e.g. fixture lands); the diff is human-reviewed in PR.
- Error cases assert message quality, not just failure: messages must name the file, the key, and the fix ("key `home_title` in values-de/strings.xml: …").

### 2.2 Android XML parsing semantics (the input side)

Android's resource compiler applies non-obvious lexing rules that we must replicate, because the source of truth is *Android-style* by settled decision:

| Rule | Behavior to implement | Corpus case |
|---|---|---|
| Escapes | `\'`, `\"`, `\n`, `\t`, `\\`, `\uXXXX` unescaped to literal chars | `escapes-basic` |
| Unescaped apostrophe | error in AAPT → error for us, same message shape | `error-bare-apostrophe` |
| Quoted strings | `"  spaced  "` preserves interior whitespace and bare `'` | `quoted-whitespace` |
| Whitespace collapse | unquoted runs of whitespace/newlines collapse to single space | `whitespace-collapse` |
| Leading `@` / `?` | must be escaped in Android; unescape on parse | `escapes-at-question` |
| XML entities | `&amp;` `&lt;` `&gt;` `&quot;` `&apos;` resolved by the XML parser | `xml-entities` |
| CDATA | content taken verbatim | policy — see D4 |
| Inline markup (`<b>`, `<i>`, `<u>`, `<annotation>`, arbitrary HTML) | **no iOS equivalent** | policy — see D4 |
| `translatable="false"` | excluded from catalog entirely (and from Android copy? no — copied for Android, skipped for iOS) | `translatable-false` |
| Duplicate key in one file | error | `error-duplicate-key` |
| Key present in locale but not in default `values/` | error (orphan translation) | `error-orphan-key` |
| Key legality | must be a valid Android resource name; validated even though source is hand-authored | `error-bad-key` |

Parsing uses a standard StAX/DOM XML parser with DTD/external-entity resolution **disabled** (no XXE), position-aware for error messages.

### 2.3 Placeholder conversion (the transform)

Mapping table, applied per string; conversion is total-or-error:

| Android | iOS emission | Notes |
|---|---|---|
| `%s` / `%1$s` | `%@` / `%1$@` | object slot |
| `%d` / `%1$d` | `%lld` / `%1$lld` **(decided — D3)** | see below |
| `%f`, `%.2f`, `%e`, `%g` | unchanged | C-compatible on both |
| `%x`, `%o` | unchanged + width/precision flags preserved | |
| `%%` | `%%` | literal percent both sides |
| `%b`, `%h`, `%c`, `%n`, `%,d` (grouping), `%(d`, `%tY` (date/time) | **hard error** with per-specifier guidance | Java-only semantics; silent mistranslation risk |

- **`%d` policy — DECIDED (D3, 2026-07-30): emit `%lld`, unconditionally, not configurable.**
  Android's `%d` (Java `Formatter`) accepts any integer width — `byte`/`int`/`long` — and the XML source cannot express which. C/`printf` formatting on iOS is strict: `%d` is exactly 32-bit `int`, while Swift's default `Int` is 64-bit on every Apple platform. Values ≥ 2³¹ therefore truncate silently: no crash, no warning, wrong number, in every locale at once — precisely the silent-shipped-bug class the brief forbids.
  `%lld` (`long long`, 64-bit on all Apple platforms) absorbs every Android integer width without loss, so it is correct for 100% of inputs; `%d` is correct only until a count gets large. There is no symmetric safe choice in the other direction.
  Accepted cost: generated catalogs read `%lld` where source reads `%d` — a visible, deliberate transformation. Rejected: per-project configurability (nothing here needs to vary per consumer; a knob only creates a way to get it wrong).
  *Caveat carried forward:* the claim that Xcode's own Swift extraction emits `%lld` for `Int` is directional, not verified against a citation. The correctness argument above stands independently of it — this table is generated, not typed against Xcode's autocomplete. Worth confirming opportunistically when the Xcode fixture (§1.4) is produced.
- **Positional discipline:** if a string has ≥2 specifiers, all must be explicitly positional (`%1$s %2$d`) or it's an error — translators reorder arguments, and unnumbered reordering silently corrupts on both platforms. Matches Android Lint's own rule. Corpus: `error-mixed-positional`.
- Specifier parity across locales: every localization of a key must use the same multiset of specifiers as the default locale, else error (`error-specifier-mismatch-locale`). This is the single highest-value check in the whole tool — it's the shipped-translation crash class.
- The brief's note stands: `%d` vs `%s` *typing* must be authored correctly in source; we validate consistency, we don't infer intent. Documented in README.

### 2.4 Plurals

Android `<plurals>` → xcstrings `variations.plural`:

- Quantities `zero|one|two|few|many|other` map 1:1 to CLDR categories in `variations.plural.<category>.stringUnit`.
- `other` required in every locale that defines the plural (error otherwise) — both platforms fall back to it.
- Quantities are passed through per-locale exactly as authored; we do not second-guess CLDR applicability per language (Android ignores non-applicable categories at runtime; iOS likewise).
- Placeholder rules of §2.3 apply inside each quantity string; the count specifier follows the D3 ruling.
- Corpus: `plurals-basic`, `plurals-all-quantities`, `plurals-multi-locale`, `error-plural-missing-other`, `error-plural-specifier-mismatch`.

### 2.5 Comments

Settled: `comment` field populated from XML comments. Rule: an XML comment immediately preceding a `<string>`/`<plurals>` element in the **default locale** becomes that entry's `comment`; comments in translation files are ignored (source language owns metadata). Corpus: `comments-basic`, `comments-multiline`. *(See §9 flag F1 — the hub lists comment passthrough as a possible v0.x item, but the brief settles it; brief wins here.)*

**Exit criteria:** corpus ≥ 40 cases green including all error cases; a `CONVERSIONS.md` reference doc generated or hand-kept 1:1 with the corpus.

---

## 3. Milestone 3 — Locale directory mapping (full detail)

**Goal:** `values-*` directory names → BCP-47 tags Xcode accepts, with the legacy long tail handled exactly.

### 3.1 Mapping rules (pure function `androidQualifierToBcp47`)

Implemented as an explicit hand-rolled table + tiny parser — **not** `java.util.Locale` (its legacy-code behavior is version- and flag-dependent; we need deterministic, testable output):

| Input | Output | Class |
|---|---|---|
| `values` | source language (default `en`, DSL-configurable) | default |
| `values-de` | `de` | plain language |
| `values-pt-rBR` | `pt-BR` | language + region |
| `values-b+sr+Latn` | `sr-Latn` | BCP-47 form |
| `values-b+zh+Hans+CN` | `zh-Hans-CN` | BCP-47 full |
| `values-in` | `id` | legacy ISO-639 remap |
| `values-iw` | `he` | legacy |
| `values-ji` | `yi` | legacy |
| `values-zh-rCN` / `values-zh-rTW` | `zh-Hans` / `zh-Hant` **(proposed — Decision D5)** | script canonicalization |

- **D5 (zh policy):** Apple's ecosystem keys Chinese by script (`zh-Hans`/`zh-Hant`), not region. Proposal: canonicalize `zh-rCN→zh-Hans`, `zh-rTW→zh-Hant`, `zh-rHK→zh-Hant-HK`(? or `zh-Hant`), with a DSL escape hatch `localeOverrides = mapOf("zh-rCN" to "zh-Hans")` making every canonicalization overridable and visible. Alternative: literal `zh-CN` pass-through (Xcode tolerates it, but it diverges from every Apple default and from hand-authored catalogs). Needs ruling on the default; the override hook ships regardless.
- **Non-locale qualifiers** (`values-night`, `values-v21`, `values-sw600dp`, `values-en-rGB-night`): proposal (D5b, same ruling) — **hard error in the `:i18n` module.** The module is platform-neutral by settled decision; density/night/API qualifiers are platform presentation concerns and have no iOS meaning. Erroring keeps the source tree honest. Alternative: silently ignore for iOS but copy for Android — rejected as a silent-divergence trap, but listed for completeness.
- **Collision detection:** `values-iw` + `values-he` both present (or any two dirs mapping to one tag) → error naming both directories.
- Region/script casing normalized (`pt-rbr` → `pt-BR`).

### 3.2 Tests

Exhaustive table test over every row above + property test: output always matches BCP-47 syntax regex; idempotence (`map(map(x))` undefined — outputs are not inputs — instead assert output uniqueness across a realistic 30-locale fixture set). Corpus integration: `locales-long-tail` case exercising the full table end-to-end into one catalog.

**Exit criteria:** table test exhaustive; corpus case green; behavior documented in `CONVERSIONS.md`.

---

## 4. Milestone 4 — Gradle wiring (outline)

- **Prerequisite (brief mandate):** read ListenUp's `listenup.localization.gradle.kts` (`ListenUpApp/ListenUp`, `tools/build-logic/convention/...`) before writing the shell — it encodes production configuration-cache and task-output lessons. First task of this milestone.
- `:i18n` module: applies the source plugin; source at `src/main/strings/values-*/strings.xml`.
- `generateStrings` task: `@InputDirectory` strings tree → two outputs: Android res tree under `build/generated/i18n/res` (per-locale `strings.xml`, normalized), and the committed `Shared.xcstrings` at a DSL-configured path in the iOS project tree.
- Android consumption via `androidComponents.onVariants { variant.sources.res?.addGeneratedSourceDirectory(...) }` in the **app module** (settled; also dodges the AGP-9 KMP-library res question flagged in the brief).
- Configuration-cache compatible from day one: no `Project` at execution time, Provider API throughout, TestKit test asserting `--configuration-cache` reuse.
- Optional keys-only `commonMain` object generation (settled decision, small).

## 5. Milestone 5 — `verifyStrings` drift gate (outline)

- Inputs-only task (no declared outputs / `doNotTrackState`) so it never goes UP-TO-DATE (settled).
- Regenerates the catalog in memory, byte-compares against the committed file; failure message: exact path + "run `./gradlew generateStrings` and commit the result", plus a structural diff (added/removed/changed keys) from the §1.3 parser.
- CI recipe documented: `./gradlew generateStrings && git diff --exit-code` *and/or* `verifyStrings` (equivalent guarantees; docs recommend one).

## 6. Milestone 6 — Swift key-parity lint (outline)

- Scan `.swift` sources for statically resolvable references to the generated table (`.Shared.someKey`, `String(localized:table:)` literals); fail the build if a referenced key is absent from source XML (iOS renders missing keys as raw key text with no error anywhere — the brief's rationale).
- Regex/heuristic scanner in `core`, task shell in `plugin`; documented limitations (dynamic keys invisible). Ships after v0 per scope proposal (D7).

---

## 7. Engineering choices the brief doesn't settle — options + trade-offs (no silent decisions)

### E1. Repo layout
**DECIDED 2026-07-30: (a) multi-module.**
- **(a) Multi-module: `core` + `plugin` (+ `sample/`)** — chosen. Fast unit tests for the hard logic; plugin stays thin; `core` reusable by a future CLI. Slightly more build scaffolding.
- **(b) Single plugin module** — rejected: every corpus run pays Gradle API classpath, and logic/wiring blur together.
- Kotlin DSL, version catalog, included-build `sample/` app (Android app + `:i18n` + a fake `ios/` tree) as living integration test.

### E2. Supported Gradle/AGP range
- Floor candidates: **Gradle 8.5 + AGP 8.1** (variant API + `addGeneratedSourceDirectory` mature, config cache stable) — recommended floor; CI matrix {floor, current stable, AGP 9 beta} per the brief's AGP-9 warning.
- Alternative: latest-only (Gradle 9/AGP 9) — smaller matrix, excludes most real apps for a v0 aimed at adoption.

### E3. Test strategy
**DECIDED 2026-07-30: three tiers.**
- Chosen: (1) `core` unit + corpus (milestones 1–3, no Gradle), (2) TestKit functional tests (tasks, config cache, up-to-dateness), (3) `sample/` composite build in CI as the end-to-end smoke.
- Rejected: sample-only — faster to write, but up-to-date/config-cache regressions become invisible until integration.
- Note: only tier 1 exists during milestones 1–3. Tiers 2–3 arrive with milestone 4.

### E4. Consumer DSL shape
- **(a) Two plugin IDs** — `net.sarazan.articulate` (on `:i18n`: source + generation + verify) and `net.sarazan.articulate.android` (on the app module: variant res wiring). Explicit, each module's role visible. Recommended.
- **(b) One plugin ID, behavior keyed on what's applied alongside** — fewer IDs, magic detection; harder to document and to fail clearly when misapplied.
- Extension sketch (either way):
  ```kotlin
  articulate {
    sourceLanguage = "en"                       // default "en"
    ios { catalog = file("../ios/App/Shared.xcstrings"); table = "Shared" }
    localeOverrides.put("zh-rCN", "zh-Hans")     // escape hatch, see D5
    kotlinKeys { enabled = true; packageName = "…" }   // optional commonMain keys object
  }
  ```

### E5. Publishing
- **(a) Gradle Plugin Portal** (recommended for v0) — canonical discovery for `id("net.sarazan.articulate")`; requires portal account + `net.sarazan` namespace claim. Do the portal-collision check from the hub checklist before naming ships.
- **(b) Maven Central (+ marker POMs)** — more infra (Sonatype, signing) but org-controlled; can add later.
- **(c) Both** — eventual end state; not needed for v0.

### E6. Toolchain & bootstrap
**DECIDED 2026-07-30: Gradle wrapper, committed; toolchain-pinned JDK.**

- **Wrapper is the only entry point.** `gradlew` / `gradlew.bat` / `gradle/wrapper/` committed to the repo. Local dev, CI, and agents all invoke `./gradlew` — never a system `gradle`. This removes "which Gradle is on `PATH`" and "which JDK is linked" as failure modes permanently. (Committing `gradle-wrapper.jar` is standard Gradle practice; CI adds Gradle's `wrapper-validation` check to guard its integrity.)
- **Compilation JDK is pinned via Gradle Java toolchains**, not inherited from whatever launches Gradle:
  ```kotlin
  kotlin { jvmToolchain(17) }   // exact version per D12 ruling
  ```
  This decouples the launcher JDK from the compile JDK — so a machine with only JDK 23 installed still produces bytecode for the target version, and builds are reproducible across dev machines and CI.
- **Bootstrap is a one-time human step** (chicken-and-egg: generating a wrapper requires a Gradle to run). Once done, never needed again. See §12.
- **Deferred to milestone 4, not needed now:** the *build-with* Gradle version versus the *supported-floor* Gradle version (§E2/D9). `core` has zero Gradle API surface, so milestones 1–3 are unaffected by that question; it only binds when `plugin` starts depending on `gradleApi()`.

---

## 8. Hub Open Questions — recommendations (human rules on each)

### D6. `<string-array>` policy
**Recommend: reject at parse time in v0** with an error that names the array and suggests the manual pattern (`foo_0`, `foo_1`, … as plain strings). Rationale: iOS has no array resource; auto-emitting indexed keys invents a convention consumers can't predict, and ordering/size changes become silent translation bugs — exactly the 95%-right trap the brief forbids. Rejection is cheap and fully reversible; indexed emission can arrive as an opt-in in v0.x if demand shows up.

### D7. v0 scope
**Recommend: v0 = milestones 1–5** — strings + plurals + comments + locale mapping + `generateStrings` + `verifyStrings` + the `commonMain` keys object. The drift gate (m5) belongs in v0 because determinism-without-enforcement is half the pitch. **Swift key-parity lint (m6) = v0.1** — it's the best marketing feature but needs heuristics that shouldn't gate the core release. String-arrays rejected (D6), inline HTML per D4.
Note: comment passthrough is *in* v0 because the brief settles catalog `comment` population (see flag F1).

### D8. SwiftPM story
**Recommend: documented workaround, out of code scope for v0.** The catalog targets an Xcode app target where symbol generation works out of the box (the brief's "additive to Xcode 26" pitch). For SwiftPM packages, `swift build` doesn't generate catalog symbols — document the known approaches (Saidi's and Elegant Chaos's write-ups are already in the brief's references, xcstrings-tool as the escape hatch) in a `docs/swiftpm.md`, and revisit only if users ask. Building tooling here would drag in exactly the toolchain-coupling the no-runtime pitch avoids.

---

## 9. Constraint flags (per brief protocol: flag, don't act)

- **F1 — Brief vs hub inconsistency on comments.** The brief's Settled Decisions table says catalog `comment` comes from XML comments; the hub's v0-scope open question lists "comment passthrough" as a candidate v0.x deferral. This plan follows the brief (comments in v0, §2.5). If the deferral was intended, the brief's metadata row needs a human amendment + Decision Log entry.
- **F2 — `%d` under-specification.** The brief's build order fixes `%1$s → %1$@` but is silent on integer specifiers, while noting `%d`-vs-`%s` authoring must be documented. Not a wrong constraint — just a gap the corpus can't leave open; surfaced as Decision D3 rather than decided silently.
- **F3 — Pinned `version` is a moving target by design.** Byte-determinism with a pinned `version` means every Xcode format bump (1.0→1.1 already) obligates a plugin release before users upgrade Xcode, or `verifyStrings` and Xcode fight. This matches the audit's accepted annual-maintenance risk; flagging that the *fixture protocol* (§1.4) should therefore be re-runnable per Xcode release, not one-shot.

---

## 10. Proposed repo skeleton

```
articulate/
├── settings.gradle.kts              # includes core, plugin; includeBuild("sample")
├── build.gradle.kts
├── gradle/libs.versions.toml
├── PLAN.md                          # this document
├── README.md
├── docs/
│   ├── CONVERSIONS.md               # escaping/placeholder/plural/locale spec, 1:1 with corpus
│   └── swiftpm.md                   # D8 workaround doc
├── core/                            # pure Kotlin, no Gradle API
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/net/sarazan/articulate/core/
│       │   ├── model/               # StringCatalog, Entry, PluralCategory…
│       │   ├── parse/               # Android strings.xml parser (XXE-safe, position-aware)
│       │   ├── convert/             # placeholder & escape conversion
│       │   ├── locale/              # androidQualifierToBcp47 + table
│       │   └── serialize/           # CanonicalFormat, XcstringsWriter, XcstringsReader
│       └── test/
│           ├── kotlin/…             # unit + corpus runner (@TestFactory)
│           ├── corpus/              # §2.1 golden cases
│           └── fixtures/xcode/      # §1.4 — README.md now; 3 files PENDING human
├── plugin/                          # milestones 4–6
│   ├── build.gradle.kts             # java-gradle-plugin, plugin IDs per E4 ruling
│   └── src/
│       ├── main/kotlin/net/sarazan/articulate/gradle/
│       │   ├── ArticulatePlugin.kt / ArticulateAndroidPlugin.kt
│       │   ├── ArticulateExtension.kt
│       │   └── tasks/               # GenerateStringsTask, VerifyStringsTask, (SwiftKeyLintTask)
│       └── test/kotlin/…            # TestKit functional tests
└── sample/                          # included build: end-to-end smoke
    ├── settings.gradle.kts
    ├── app/                         # Android app consuming generated res
    ├── i18n/                        # the strings module
    └── ios/App/                     # fake tree holding committed Shared.xcstrings
```

(`lint` folded into `core`/`plugin` rather than a third module until m6 proves it needs one.)

---

## 11. Decisions needed from you, in order

Blocking first; later items can wait until their milestone starts.

### Decided

- ✅ **D1 — Repo layout** (§E1): **multi-module** `core` + `plugin` + `sample`. *Decided 2026-07-30.*
- ✅ **D2 — Test strategy** (§E3): **three tiers** (core unit/corpus → TestKit → sample smoke). *Decided 2026-07-30.*
- ✅ **D3 — Integer specifier mapping** (§2.3): **`%d → %lld`**, unconditional, not configurable. *Decided 2026-07-30.*
- ✅ **D12 — Toolchain** (§E6): **committed Gradle wrapper + toolchain-pinned JDK**; bootstrap is a one-time human step. *Decided 2026-07-30.*

**Unblocked by the above:** repo scaffolding and all of milestone 1. Milestone 1 touches only the in-memory model → canonical bytes; it needs no ruling below.

### Still open

4. **D4 — Inline markup & CDATA policy** (§2.2): hard error in v0 (recommended — no iOS equivalent, silent-loss risk) vs strip-tags vs verbatim pass-through. *Blocks corpus (m2).*
5. **D5 — Locale edge policy** (§3.1): (a) `zh-rCN→zh-Hans` canonicalization default with `localeOverrides` escape hatch (recommended) vs literal pass-through; (b) non-locale qualifiers in `:i18n` are a hard error (recommended) vs ignored. *Blocks m3.*
6. **D6 — `<string-array>`** (§8): reject in v0 (recommended). *Hub open question; blocks m2 error corpus.*
7. **D7 — v0 scope** (§8): milestones 1–5 in v0, Swift lint as v0.1 (recommended). *Hub open question; shapes everything after m3.*
8. **D8 — SwiftPM story** (§8): documented workaround, no v0 code (recommended). *Hub open question; docs-only, can be ruled anytime before release.*
9. **D9 — Gradle/AGP floor** (§E2): Gradle 8.5 + AGP 8.1 floor with 3-cell matrix (recommended). *Needed by m4.*
10. **D10 — Plugin ID / DSL shape** (§E4): two plugin IDs (recommended) vs one. *Needed by m4.*
11. **D11 — Publishing** (§E5): Plugin Portal first (recommended). *Needed before first release; also gates the portal-collision pre-flight check on the hub.*

Plus one **pending input, not a decision**: the Xcode fixture trio (§1.4) — `handwritten.xcstrings`, `opened.xcstrings`, `xcode-version.txt` — from the hub pre-flight checklist. Milestones 1–3 proceed without it; the round-trip test and final `CanonicalFormat` values wait on it.

---

## 12. Bootstrap (one-time, human)

Generating a Gradle wrapper requires an existing Gradle — chicken-and-egg. This machine has no `gradle` on `PATH` and no linked JDK (Homebrew `openjdk` 23 is installed but unlinked). Resolved once by a human, then never again: every subsequent invocation is `./gradlew`.

**Route A — Android Studio (no installs).** Create a throwaway project via the New Project wizard, then copy its four wrapper artifacts into this repo:
```
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```
Delete the throwaway. `chmod +x gradlew`.

**Route B — Homebrew (one command, one-time install).**
```bash
brew install gradle && cd /Users/asarazan/projects/articulate && gradle wrapper
```

**Report back three values** so build files are pinned against reality rather than guessed:
1. `distributionUrl` from `gradle/wrapper/gradle-wrapper.properties` (the Gradle version)
2. Output of `./gradlew --version` (confirms the wrapper runs, and names the JDK it found)
3. Android Studio's bundled JDK version, if using Route A (Settings → Build Tools → Gradle → Gradle JDK)

Everything else — `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `core/build.gradle.kts` — is plain text requiring no toolchain, written once those versions are known.
