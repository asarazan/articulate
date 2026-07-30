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
StringCatalog(sourceLanguage: String, entries: Map<StringKey, Entry>)
Entry(comment: String?, localizations: Map<LocaleTag, Localization>)
Localization = Simple(StringUnit) | Plural(Map<PluralCategory, StringUnit>)
```

- `state: "translated"` and `extractionState: "manual"` are constants, not options (settled decision — the catalog is a build artifact; state lives upstream) — and are correspondingly absent from the model entirely, living only in `CanonicalFormat`, not as defaulted fields on `Entry`/`StringUnit`.
- **Implemented as `Map`, not `SortedMap`** (revised from this section's original illustrative sketch, 2026-07-30, during milestone-1 audit): sorting is the serializer's responsibility alone, applied once in `CanonicalJson.appendObject` at every level, rather than a property callers must maintain by constructing the right map type. A `HashMap` and a `LinkedHashMap` with different insertion order are required to serialize identically — this is exactly what `DeterminismTest`'s insertion-order test holds — which is a stronger, tested guarantee than `SortedMap` typing would have provided on its own (a `SortedMap` built with an inconsistent comparator would silently produce wrong order with no test to catch it).
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

**Parsing is an ordered six-stage pipeline, not a set of independent rules.** This was the single biggest structural correction from research: several behaviors below are only explicable — and only implementable correctly — in this order. Implementing the table as independent passes in the wrong order produces wrong output on roughly a dozen cases.

```
S0  XML parse ............... entity refs resolved; CDATA delivered as ordinary text
S1  Subtree flatten ......... no-namespace tags → style spans
                              xliff:g → untranslatable section (NOT a span)
S2  Edge trim ............... ONLY IF no span was seen anywhere in the string
    ├─ S3a  Reference probe . the RAW, still-escaped text is tried as @ref / ?attr.
    │                         If it parses, the resource IS a reference and string
    │                         processing never runs at all.
    └─ S3b  String build .... otherwise: backslash escapes; `"` toggles quoting;
                              runs of ASCII whitespace → one space
S4  Format verification ..... plain strings only, NOT styled
```

**S3a and S3b are a branch, not a sequence** — this is easy to get wrong and produces a subtle bug if you do. The reference probe runs on the *raw, still-escaped* value, and only if it fails does string building happen. Implement it sequentially (escape first, then probe) and `\@string/target` breaks: it would probe the already-unescaped `@string/target`, match it as a reference, and silently alias instead of producing the literal text `@string/target`. Verified: `\@string/target` → the literal string `@string/target`.

Three consequences fall directly out of the ordering, **each independently reproduced against aapt2 2.19 on 2026-07-30**:

| Consequence | Test | Result |
|---|---|---|
| S2 before S3b — trailing `\` has whitespace trimmed first, is *then* dropped as a dangling escape | `\q\z\ ` | `qz` (not `qz `) |
| S0 before S3b — `&quot;`/`&apos;` are indistinguishable from literal `"`/`'` by the time Android's layer runs | `&amp; &lt; &gt; &quot; &apos;` | `& < >  '` — two spaces, no quote: `&quot;` toggled quoting on and vanished, which then made the space literal and the apostrophe legal |
| S1 before S2 — one `<b>` anywhere suppresses trimming for the *whole* string | `··<b>x</b>··y··` | `·x y·` (edges retained); the same string with `<xliff:g>` instead trims to `A B` |

| Rule | Behavior to implement | Corpus case |
|---|---|---|
| Escapes | `\'` `\"` `\n` `\t` `\\` `\uXXXX` **plus `\#` `\@` `\?`**; unknown `\x` → backslash dropped, `x` emitted literally; trailing lone `\` silently dropped; `\uXXXX` must be exactly 4 hex digits else error | `escapes-basic`, `escapes-hash`, `escapes-unknown-passthrough`, `escapes-trailing-backslash`, `error-bad-unicode-escape` |
| **`\r` is NOT a carriage return** | falls to the unknown-escape branch → literal `r`. Author writing `\r\n` gets `r`+newline. **We reject `\r`** rather than faithfully reproduce a bug | `escapes-carriage-return` |
| Escape/trim ordering | escapes processed *after* edge trimming (S2→S3): `\q\z\ ` → `qz`, not `qz ` | `escapes-trim-order` |
| Escapes inside quotes | quoting suppresses whitespace collapse and the apostrophe error, **not** backslash escapes | `quoted-with-escapes` |
| Unescaped apostrophe | hard error (not warning), not downgradable by `--legacy`; no Lint rule exists. Mirror AAPT2's wording: `unescaped apostrophe in string` | `error-bare-apostrophe` |
| Quoted strings | `"` is a **toggle**, not a delimiter pair; quote chars never emitted; no unbalanced-quote error in AAPT2 | `quoted-whitespace`, `quoted-mid-string` |
| **Odd quote count** | a single stray `"` enables quoting for the entire rest of the string — preserving whitespace and legalizing every later bare `'`. Almost always an authoring bug: **we error** | `error-odd-quote-count` |
| Quote state at spans | resets at every span boundary — a quoted region cannot span an inline tag | `quote-reset-at-span` |
| Whitespace collapse | Android's own bespoke pass (not XML normalization, not `xml:space`), kept bug-for-bug from AAPT1: unquoted runs → exactly one U+0020 | `whitespace-collapse` |
| **Whitespace collapse is ASCII-only** | gated on codepoint ≤ 0x7F. U+00A0/U+2003/U+2008 pass through uncollapsed. **The official Android docs are factually wrong here** — do not implement the documented behavior | `whitespace-unicode-spaces-preserved` |
| Edge whitespace | trimmed — **but only if the string contains no span**. `  <b>x</b>  y  ` keeps a leading and trailing space; `<xliff:g>` does not suppress trimming | `whitespace-edges-with-span`, `whitespace-edges-with-xliff` |
| XML entities | `&amp;` `&lt;` `&gt;` resolve and are inert. **`&quot;`/`&apos;` are NOT escape hatches**: resolved pre-S3, so `&quot;` toggles quoting and vanishes, `&apos;` hard-errors identically to a bare `'` | `xml-entities`, `error-apos-entity` |
| Invalid XML char refs | `&#11;` `&#12;` rejected by the XML parser before Android sees them | `error-invalid-xml-char-ref` |
| CDATA | **not verbatim** (corrected — D4): same escape/whitespace/quoting pipeline as normal text; only XML tag/entity interpretation is suppressed inside it. A bare `'` inside CDATA still errors | `cdata-not-verbatim`, `cdata-inert-markup`, `error-cdata-apostrophe` |
| `<xliff:g>` placeholder annotation | unwrap transparently — drop tag, keep inner text; lift `id`/`example` into catalog `comment` (D4). Not a span: format verification still runs, whitespace collapses correctly, trimming still applies. Nested `<xliff:g>` is an error | `xliff-g-unwrap`, `xliff-g-whitespace`, `xliff-g-attrs-to-comment`, `error-nested-xliff-g` |
| Inline styling markup (`<b>` `<i>` `<u>` `<annotation>` `<font>` `<br/>`, arbitrary HTML) | **hard error by default** (D4) — no iOS equivalent, information provably cannot survive; `markupPolicy` DSL override ships in v0 | `error-styled-nonpositional`, `span-double-space` |
| Foreign-namespace tags | warn, drop tag, keep children | `foreign-namespace-warn` |
| **Leading `@`/`?` is NOT an error in Android** | it silently becomes a *reference* (S4 runs on raw text). Failure surfaces at link time in a different tool, or never if the target exists. **We hard-error** — we cannot resolve Android references, and emitting literal `@string/other` into a catalog ships reference syntax to users. Only `@`/`?` are affected: `#FF0000`, `42`, `true` stay plain strings | `escapes-at-question`, `error-unescaped-leading-at`, `error-unescaped-leading-question`, `string-literal-lookalikes` |
| `translatable="false"` | **emit with `"shouldTranslate": false`, do not drop** (decided 2026-07-30 — see below). Unparseable attribute value is an error | `translatable-false`, `error-bad-translatable-value`, `error-translation-for-untranslatable` |
| **`formatted="false"`** | disables format verification for that string; `%` chars are literal. We must honor it — and since `.xcstrings` has no equivalent opt-out, every literal `%` must be **escaped to `%%`** on the way out. A real conversion, not a pass-through | `formatted-false-escapes-percent` |
| `<string-array>` | **hard error at parse time** (decided 2026-07-30 — D6, see §8) | `error-string-array` |
| Duplicate key in one file | hard error, not downgradable by `--legacy`; no last-write-wins. Keys on `(name, config)`, so same key across `values/` and `values-de/` is fine | `error-duplicate-key` |
| Duplicate/invalid `<item quantity=>` | both hard errors. Legal keywords are exactly `zero one two few many other` | `error-duplicate-quantity`, `error-bad-quantity` |
| Key present in locale but not in default `values/` | error (orphan translation) — matches Lint's `ExtraTranslation`, severity Fatal | `error-orphan-key` |
| Key legality | grammar: `XID_Start\|_` then `(XID_Continue\|.\|-)*`. No leading digit, **no `$`** (unlike Java identifiers), `.`/`-` allowed, non-ASCII allowed, case-sensitive, no reserved words. **We are stricter**: warn on `.`/`-` and non-ASCII, since those break Swift symbol generation *and* `R.` access | `error-bad-key`, `key-dot-dash-warn`, `key-unicode`, `key-reserved-word-ok` |

Parsing uses a standard StAX/DOM XML parser with DTD/external-entity resolution **disabled** (no XXE), position-aware for error messages.

**`docs/CONVERSIONS.md` remains the authoritative spec** — every rule above is stated there with its AOSP source citation, reproduced experiment, and confidence marker. This table is the working summary; consult that document before implementing any row.

**Verification status of the Android-side rules (2026-07-30).** The rules above came from a research agent; a second pass then *independently re-ran* a subset against the same aapt2 2.19 binary, without reusing the agent's outputs. Reproduced exactly, byte-checked where relevant: ASCII-only whitespace collapse (`x&#32;&#8200;&#8195;y` → `78 20 e2 80 88 e2 80 83 79`, i.e. U+2008/U+2003 preserved uncollapsed — Google's docs really are wrong); `&#160;` pairs preserved; TAB/LF/CR collapsing to one space; `\r` → literal `r`; `\#\@\?` → `#@?`; trailing lone `\` dropped; trim-before-escape ordering; the entity trace above; `<b>` edge-retention vs `<xliff:g>` trimming; span double-space; quote-reset-at-span; escaped-`@` yielding literal text.

**Target-side rules were also independently re-run** against `xcstringstool` from Xcode 26.6 (17F113), the same version as the original research. Reproduced exactly: the plural numeric-specifier requirement (verbatim error message; `%@` does not satisfy it; one conforming variant suffices; `other` genuinely not required by Xcode); `NSStringFormatValueTypeKey` derived straight from the specifier (`%lld`→`lld`, `%d`→`d`, confirming D3 is load-bearing for plurals); the total absence of validation (arity mismatch, positional type swap, a literal `%s`, empty keys, dotted/dashed keys, `class`, and keys containing spaces all compiled clean with zero diagnostics); `shouldTranslate: false` emitting its value into the build; and fully lossless escaping (leading/trailing spaces, newlines, tabs, quotes, backslashes, CJK and NBSP all round-trip verbatim).

**One claim did not survive re-testing** — the `state: "new"` rule, corrected in §2.6. It was the highest-consequence item in the spec, which is precisely why it was worth re-running.

**Still single-sourced, not re-verified:** the AOSP line-number citations (the source doc notes `master` moves and calls them approximate anchors — the function names are the real citation), and the Java-side comparisons in §2.3 (`%g` trailing zeros, `HALF_UP` vs half-to-even rounding, locale-formatting divergence). The latter need a JDK 17 run plus a Swift/C comparison to close; none of them block M2's parser, but `%g` gates a hard-error rule so it should be confirmed before that error message ships.

### D4 — inline markup & CDATA policy — DECIDED (2026-07-30)

Research (`docs/CONVERSIONS.md` §5–6) found the original one-line framing conflated three unrelated questions. Split and ruled separately:

- **CDATA — not actually a policy question.** The plan's premise ("content taken verbatim") was factually wrong: CDATA only suppresses XML tag/entity interpretation, everything else (escaping, whitespace collapse, quoting) runs identically to normal text. **Ruling: support it**, parsed per the corrected rules — it costs no parser complexity beyond what normal text already needs, so there was no real reason to carve it out as unsupported.
- **`<xliff:g>` — not markup either.** Verified as a distinct AOSP code path (an "untranslatable section," not a style span): format verification still runs, whitespace collapses correctly across it, and it's the standard real-world idiom for placeholder metadata (used throughout AOSP itself). **Ruling: unwrap transparently** — drop the tag, keep the inner text, lift `id`/`example` attributes into the catalog's `comment` field. Not optional: erroring on it would reject a large share of real-world `strings.xml` files, and treating it as a span would be a category error since it structurally isn't one.
- **Genuine styling markup** (`<b>`, `<i>`, `<u>`, `<annotation>`, `<font>`, `<br/>`, arbitrary HTML) **— the real judgment call. Ruling: hard error by default in v0.** `.xcstrings` values are flat strings with no span concept, so styling information provably cannot survive the conversion — and Android's own plain-text channel for a styled string is *already* lossy (`<br/>` produces no newline outside the span), so silently stripping tags wouldn't degrade to plain text, it would degrade to text that's *wrong*, in a language the developer can't read to catch it. An error is loud and precise instead.

**Escape hatch — wider than originally proposed.** The initial proposal was a binary `markupPolicy = ERROR | STRIP` knob. Ruling: **three-way**, `ERROR | STRIP | VERBATIM`, default `ERROR`:

```kotlin
enum class MarkupPolicy { ERROR, STRIP, VERBATIM }
```

`VERBATIM` (ship the raw tags through as literal text) was flagged in research as the weakest of the three options on its own — it produces a visible, shipped defect rather than a build-time error — but the ruling is to make it available as an explicit, informed opt-in rather than omit it: someone migrating an existing pipeline, or post-processing the catalog downstream, may have a specific reason to want it, and the non-default gate means choosing it is a deliberate act, not an accident. The knob itself ships in v0 (so the DSL shape doesn't change later); `STRIP` and `VERBATIM`'s actual *implementations* are deferred — `STRIP` to v0.1 per the original plan, `VERBATIM` unscheduled since it's pure pass-through and lower priority than `STRIP`.

Corpus cases: `cdata-not-verbatim`, `cdata-inert-markup`, `error-cdata-apostrophe`, `xliff-g-unwrap`, `xliff-g-whitespace`, `xliff-g-attrs-to-comment`, `error-nested-xliff-g`, `error-styled-nonpositional`, `span-double-space`, `foreign-namespace-warn` (all from `docs/CONVERSIONS.md` §12).

### 2.3 Placeholder conversion (the transform)

Mapping table, applied per string; conversion is total-or-error:

| Android | iOS emission | Notes |
|---|---|---|
| `%s` / `%1$s` | `%@` / `%1$@` | object slot; `%@` is the only object conversion |
| `%d` / `%1$d` | `%lld` / `%1$lld` **(decided — D3)** | see below |
| `%f`, `%.Nf`, `%e`, `%E` | unchanged | **verified** identical across Java 17 / C / Swift, character for character |
| `%g`, `%G` | **HARD ERROR** | **corrected** — not compatible. Java keeps trailing zeros (`1.0`→`1.00000`), C and Swift strip them (`1`). No faithful mechanical rewrite exists. Guidance: use `%.Nf` or `%e` |
| `%x`, `%X`, `%o` | **`%llx`, `%llX`, `%llo`** | **corrected** — same 32-bit truncation D3 exists to fix. Apple's `%x`/`%o` are `unsigned int`; `String(format:"%x", Int(4294967296))` → `"0"`. Flags/width/precision preserved (`%#08x` → `%#08llx`) |
| `%%` | `%%` | literal percent both sides |
| `%c`, `%C` | **HARD ERROR** | Java `%c` is a Unicode character; Foundation `%c` is an **8-bit `unsigned char`**. Not equivalent |
| `%S` | **HARD ERROR** | Java `%S` is an uppercased string; Foundation `%S` is a **null-terminated UTF-16 array** — different meaning and memory-unsafe |
| `%.Ns` (precision on a string) | **HARD ERROR** | Java/C truncate to N chars; `%@` does not honor precision, so the truncation would silently vanish. Width alone (`%10s`) also errors pending verification |
| `%<` (argument reuse) | **HARD ERROR** | no iOS equivalent; AAPT2 rejects it too ("positions can be moved around during translation") |
| `%a`, `%A` | **HARD ERROR** | C99 hex-float, unexercised, rare — error rather than guess |
| `%b`, `%B`, `%h`, `%H`, `%n`, `%,d`, `%(d`, `%tX` | **HARD ERROR** with per-specifier guidance | Java-only semantics; silent mistranslation risk. For `%,d` point at iOS `NumberFormatter` / `formatted(.number)` |

- **`%d` policy — DECIDED (D3, 2026-07-30): emit `%lld`, unconditionally, not configurable.**
  Android's `%d` (Java `Formatter`) accepts any integer width — `byte`/`int`/`long` — and the XML source cannot express which. C/`printf` formatting on iOS is strict: `%d` is exactly 32-bit `int`, while Swift's default `Int` is 64-bit on every Apple platform. Values ≥ 2³¹ therefore truncate silently: no crash, no warning, wrong number, in every locale at once — precisely the silent-shipped-bug class the brief forbids.
  `%lld` (`long long`, 64-bit on all Apple platforms) absorbs every Android integer width without loss, so it is correct for 100% of inputs; `%d` is correct only until a count gets large. There is no symmetric safe choice in the other direction.
  Accepted cost: generated catalogs read `%lld` where source reads `%d` — a visible, deliberate transformation. Rejected: per-project configurability (nothing here needs to vary per consumer; a knob only creates a way to get it wrong).
  *Caveat now resolved (2026-07-30):* the original write-up flagged the "Xcode's own extraction emits `%lld`" claim as directional and unverified. Research confirmed the underlying behavior empirically and from Apple's own documentation — `%d` is documented as 32-bit `int`, and `String(format: "%d", Int(4294967296))` returns `"0"` on Xcode 26.6. Independently, `xcstringstool` derives a plural's `NSStringFormatValueTypeKey` straight from the specifier, so `%d` in a plural declares a 32-bit type against a 64-bit `Int` (see §2.4). The decision stands, now on verified rather than first-principles grounds.
- **`%x`/`%o` follow D3's logic — DECIDED (2026-07-30): emit `%llx` / `%llX` / `%llo`.** Verified: Apple documents `%x`/`%X`/`%o` as *unsigned 32-bit*, and `String(format: "%x", Int(4294967296))` returns `"0"` — the identical truncation D3 was created to eliminate for `%d`. Since `strings.xml` cannot encode the argument's Java type and Swift's `Int` is unconditionally 64-bit, the `ll` modifier is the only choice that never truncates. Flags, width and precision are preserved (`%#08x` → `%#08llx`; `#` alternate-form behavior confirmed identical between Java and C). Residual, documented not fixed: for a negative value Android passed as an `Integer`, Android renders 8 hex digits and iOS 16 — unavoidable without type information, and negative hex in user-facing translated copy is not a realistic case.
- **Positional discipline:** if a string has ≥2 specifiers, all must be explicitly positional (`%1$s %2$d`) or it's an error — translators reorder arguments, and unnumbered reordering silently corrupts on both platforms. **Confirmed this matches AAPT2's own default** (hard error, downgradable only via `--legacy`). Corpus: `error-nonpositional-multi`, `error-arg-reuse`.
- **We cannot inherit AAPT2's positional check — it has two verified holes**, and both are corpus cases: (a) **styled strings skip verification entirely**, so `%s and %d` errors but `%s and <b>%d</b>` compiles clean; (b) **a `Time`-format look-alike short-circuits the scan** — any conversion char in `D F K M W Z k m w y z` makes the verifier return success immediately, so `%y and %s and %d` (three non-positional args) compiles clean. Articulate must run its own check on **every** string, styled or not. Corpus: `error-styled-nonpositional`, `error-time-format-shortcircuit`.
- Specifier parity across locales: every localization of a key must use the same multiset of specifiers as the default locale, else error. **Research materially strengthened this:** `xcstringstool` performs essentially *no* validation — arity mismatches, positional type swaps, a literal `%s`, and even empty keys all compile clean. Every one is a runtime crash or garbage render. **Articulate is the only validation layer that exists**; this is the strongest available evidence for the project's own thesis and belongs in the README. Corpus: `error-locale-arity-mismatch`, `error-locale-type-mismatch`.
- The brief's note stands: `%d` vs `%s` *typing* must be authored correctly in source; we validate consistency, we don't infer intent. Documented in README.
- **Two platform divergences we document rather than fix.** (a) *Rounding*: Java specifies `HALF_UP`, C/Foundation use IEEE-754 half-to-even — `%.1f` of `0.25` is `0.3` on Android and `0.2` on iOS. (b) *Locale sensitivity*, which matters for an i18n tool: Java's `%f`/`%e`/`%d` localize via the formatter's locale, while Foundation's `String(format:)` without an explicit locale is POSIX — so `%.2f` of `1234.5` renders `1234,50` on a German Android device and `1234.50` on iOS. Neither is expressible in the catalog format nor fixable by the converter. Note both in generated-file headers and the README; consider a lint in a later milestone. Corpus: `float-passthrough`, `float-rounding-divergence-doc` (documentation-only).

### 2.4 Plurals

Android `<plurals>` → xcstrings `variations.plural`:

- Quantities `zero|one|two|few|many|other` map 1:1 to CLDR categories in `variations.plural.<category>.stringUnit`. **Verified as a pure identity mapping** — Xcode accepts exactly Android's keyword set.
- **Legal Android plurals can produce an `.xcstrings` that will not build — this is the highest-value rule in this section.** `xcstringstool` emits exactly one hard error in all testing, and it is this: *"Plural variation requires referencing the number in the string."* So this perfectly valid `strings.xml`:
  ```xml
  <plurals name="items">
    <item quantity="one">One item</item>
    <item quantity="other">Several items</item>
  </plurals>
  ```
  converts to a catalog that **breaks the user's Xcode build**. Articulate must detect it at conversion time and fail with the actionable message (split into two top-level strings), rather than emitting a file that fails later inside a generated artifact the user didn't write. Precise rule: at least one variant must contain a **numeric** specifier — `%@` does not satisfy it. Scope: the check runs against the **source language** when it has a plural for that key, falling through to each present locale when it does not; translations are exempt once the source conforms. Corpus: `error-plural-no-number-reference`, `plural-number-in-one-variant-only`.
- `other` required in every locale that defines the plural (error otherwise) — both platforms fall back to it. **Xcode does not enforce this** (a plural with only `one` compiles fine), so it is ours to enforce; Android's own docs instruct authors to always supply `one` and `other`.
- Quantities are passed through per-locale exactly as authored; we do not second-guess CLDR applicability per language (Android ignores non-applicable categories at runtime; iOS likewise).
- Placeholder rules of §2.3 apply inside each quantity string; the count specifier follows the D3 ruling. **D3 is load-bearing here, independently confirmed:** `xcstringstool` derives `NSStringFormatValueTypeKey` directly from the specifier, so `%d` produces a `.stringsdict` declaring a 32-bit type that then reads 32 bits off a 64-bit Swift `Int` at runtime. With multiple arguments it also computes the count's argument index into the format key, which **requires positional arguments** — a further independent justification for the positional-discipline rule.
- Corpus: `plurals-basic`, `plural-all-categories`, `plurals-multi-locale`, `error-plural-missing-other`, `error-plural-specifier-mismatch`, `plural-lld-value-type`, `error-duplicate-quantity`, `error-bad-quantity`.

### 2.5 Comments

Settled: `comment` field populated from XML comments. Rule: an XML comment immediately preceding a `<string>`/`<plurals>` element in the **default locale** becomes that entry's `comment`; comments in translation files are ignored (source language owns metadata). Corpus: `comments-basic`, `comments-multiline`. *(See §9 flag F1 — the hub lists comment passthrough as a possible v0.x item, but the brief settles it; brief wins here.)*

### 2.6 Target-format rules (`.xcstrings` side)

Rules with no Android-side counterpart, verified against `xcstringstool` from Xcode 26.6:

- **Never emit `"state": "new"` — but the rule is narrower than first recorded (corrected 2026-07-30 by independent re-test).** The original research claim was that `state: "new"` unconditionally causes silent deletion. Re-running it found the drop is **conditional on `extractionState` being absent**:

  | entry | `state: "new"` result |
  |---|---|
  | with `"extractionState": "manual"` | **emitted** — as are `translated`, `needs_review`, `stale`, a made-up `reviewed`, and outright garbage |
  | with no `extractionState` key at all | **silently dropped**, no diagnostic (`translated` and `stale` siblings in the same file survive) |

  The coherent model: absent `extractionState` + `new` reads as "Xcode extracted this from source and nobody has translated it yet" → dropped so the key falls back. An explicit `manual` means the author owns the entry → respected.

  **This makes our position doubly safe rather than newly risky:** we always emit *both* `extractionState: "manual"` *and* `state: "translated"`, so we are in the protected branch on two independent counts. Both remain `CanonicalFormat` constants from milestone 1, and this is a real reason not to make either configurable. Corpus assertion `xcstrings-state-translated` stands, but it must assert the *conjunction*, not `state` alone.
- **`shouldTranslate: false`** is the native equivalent of Android's `translatable="false"` — verified one-for-one: the entry compiles normally and its value *is* emitted into the built `.strings`, i.e. excluded from translation, not from the build. See the D-ruling below.
- **Escaping is plain JSON escaping.** No quoting convention, no apostrophe rule, no whitespace collapsing; leading/trailing spaces, newlines and tabs round-trip verbatim. **The conversion is therefore lossless on the escaping axis** — every value Android's pipeline can produce is representable. Our escaping job is exactly: undo Android's layer, then JSON-encode. Corpus: `xcstrings-json-escaping`.
- **`.xcstrings` imposes no key constraints at all** — empty keys, `a.b-c`, `class`, keys with spaces all compile. All key validation is Android-side plus symbol-generation-side (see the key-legality row in §2.2).

**`translatable="false"` — DECIDED (2026-07-30): emit with `"shouldTranslate": false`, do not drop.** The original plan excluded such strings from the catalog entirely. Research found `.xcstrings` has a native equivalent whose semantics match Android's exactly — in both systems the string ships in the build and is merely withheld from translation tooling. Dropping it instead would break key parity between platforms for no reason, silently remove a string the iOS app may legitimately reference, and discard information the target format can represent natively. Also ruled: supplying a translation for a key marked non-translatable is an error (no definitive Lint precedent found, but it is unambiguously an authoring mistake and erroring costs nothing).

**Exit criteria:** corpus ≥ 65 cases green including all error cases (raised from ~40 — research added ~49 new cases; see `docs/CONVERSIONS.md` §12 for the full index); `docs/CONVERSIONS.md` kept 1:1 with the corpus as the cited spec of record.

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

### D6. `<string-array>` policy — DECIDED (2026-07-30)
**Reject at parse time in v0**, with an error that names the array and suggests the manual pattern (`foo_0`, `foo_1`, … as plain strings). Rationale: iOS has no array resource; auto-emitting indexed keys invents a convention consumers can't predict, and ordering/size changes become silent translation bugs — exactly the 95%-right trap the brief forbids. Rejection is cheap and fully reversible; indexed emission can arrive as an opt-in in v0.x if demand shows up.

Research adds one supporting argument: AAPT2's `ParseArrayImpl` accepts a general type mask, so array items may be references or non-string items. Auto-indexing would therefore have to invent **both** a key convention *and* a type policy — two guesses, not one. Corpus: `error-string-array`.

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
- ✅ **D4 — Inline markup & CDATA policy** (§2.2): CDATA supported (corrected — was never actually a policy question); `<xliff:g>` unwrapped transparently (not optional — real-world necessity); genuine styling markup hard-errors by default, with a three-way `markupPolicy = ERROR | STRIP | VERBATIM` DSL escape hatch shipped in v0 (`STRIP`/`VERBATIM` implementations deferred). Full ruling in §2.2. *Decided 2026-07-30.*
- ✅ **D6 — `<string-array>` policy** (§8): **reject at parse time in v0**, error names the array and suggests `foo_0`/`foo_1` plain strings. *Decided 2026-07-30.*
- ✅ **D3a — `%x`/`%o` mapping** (§2.3): **`%llx` / `%llX` / `%llo`**, extending D3's reasoning to hex/octal on verified evidence of the same 32-bit truncation. *Decided 2026-07-30.*
- ✅ **`translatable="false"` handling** (§2.6): **emit with `"shouldTranslate": false`**, do not drop — `.xcstrings` has a native equivalent with matching semantics. *Decided 2026-07-30.*

**Milestone 2 is fully unblocked.** Every decision M2's corpus and parser depend on is now ruled; §2.2–§2.6 carry the merged, research-corrected rules, with `docs/CONVERSIONS.md` as the cited spec of record.

### Still open

5. **D5 — Locale edge policy** (§3.1): (a) `zh-rCN→zh-Hans` canonicalization default with `localeOverrides` escape hatch (recommended) vs literal pass-through; (b) non-locale qualifiers in `:i18n` are a hard error (recommended) vs ignored. *Blocks m3.* **Research strengthened (b) considerably:** `values-de` and `values-de-night` would both map to `de` — a silent key collision resolved by read order, i.e. data loss rather than mere divergence.
7. **D7 — v0 scope** (§8): milestones 1–5 in v0, Swift lint as v0.1 (recommended). *Hub open question; shapes everything after m3.* **Two research flags:** the m2 corpus grew ~40 → ~65 cases (real added work in the milestone the plan already calls the gate); and m6 should be re-specced to drive off `xcstringstool generate-symbols` output rather than the currently-planned regex scanner — exact instead of heuristic. Timing still correct, design needs revisiting before anyone writes that regex.
8. **D8 — SwiftPM story** (§8): documented workaround, no v0 code (recommended). *Hub open question; docs-only, can be ruled anytime before release.*
9. **D9 — Gradle/AGP floor** (§E2): Gradle 8.5 + AGP 8.1 floor with 3-cell matrix (recommended). *Needed by m4.*
10. **D10 — Plugin ID / DSL shape** (§E4): two plugin IDs (recommended) vs one. *Needed by m4.*
11. **D11 — Publishing** (§E5): Plugin Portal first (recommended). *Needed before first release; also gates the portal-collision pre-flight check on the hub.*

Plus one **pending input, not a decision**: the Xcode fixture trio (§1.4) — `handwritten.xcstrings`, `opened.xcstrings`, `xcode-version.txt` — from the hub pre-flight checklist. Milestones 1–3 proceed without it; the round-trip test and final `CanonicalFormat` values wait on it.

---

## 12. Bootstrap (one-time, human)

Generating a Gradle wrapper requires an existing Gradle — chicken-and-egg. This machine has no `gradle` on `PATH` and no linked JDK (Homebrew `openjdk` 23 is installed but unlinked). Resolved once by a human, then never again: every subsequent invocation is `./gradlew`.

**Correction (2026-07-30):** `gradle wrapper` cannot run in an empty directory — modern Gradle's `wrapper` task requires an existing build, so a settings file must come first. Sequence below is the corrected one.

1. A minimal `settings.gradle.kts` (just `rootProject.name`) — no versions, no modules, committed already.
2. `gradle wrapper` using the system Gradle, once:
   ```bash
   cd /Users/asarazan/projects/articulate && gradle wrapper
   ```
3. Thereafter `./gradlew` only; the system Gradle is never needed again and may be uninstalled.

### Pinned versions (verified, not assumed)

| Component | Version | Why this value |
|---|---|---|
| Gradle (wrapper) | **9.5.0** | KGP 2.4.10's maximum *fully supported* Gradle. See note below. |
| Kotlin / KGP | **2.4.10** | current stable, [kotlinlang.org/docs/releases](https://kotlinlang.org/docs/releases.html) |
| JDK — daemon + compile toolchain | **17** | pinned in-repo; matches AGP 8.x's requirement |
| JDK — launcher | **any 17+** | developer's `JAVA_HOME`; deliberately *not* pinned — see below |
| foojay-resolver-convention | **1.0.0** | required for daemon-JVM auto-provisioning |

**Why Gradle 9.5.0 and not 9.6.1.** The system Gradle that bootstrapped the wrapper was 9.6.1, but KGP 2.4.10 lists Gradle **7.6.3 – 9.5.0** as its fully-supported range ([Kotlin: Configure a Gradle project](https://kotlinlang.org/docs/gradle-configure-project.html)); past that ceiling Kotlin warns of deprecation warnings and features that may not behave as expected. Milestone 1 would likely have been fine, but milestone 4 is configuration-cache-sensitive plugin work with TestKit — exactly where an untested pairing costs the most to debug. Downgrading was free; we gain nothing from 9.6.1.

**JDK slots — pin two, leave one free.** There are three, and only two belong to the repo:

| Slot | Pinned by | Value |
|---|---|---|
| Compile JDK | Java toolchain in build scripts | 17 |
| Daemon JDK | `gradle/gradle-daemon-jvm.properties` (committed) | 17 |
| Launcher JDK | developer's `JAVA_HOME` / `java` on `PATH` | any 17+ |

The launcher is unavoidably environmental — `gradlew` is a shell script that needs a JVM before any Gradle logic runs, so no in-repo file can pin it. That is fine, because it has no influence on build output: the daemon and compile JDKs are both pinned independently, so the build is byte-identical whether the developer launches on 17, 21, or later.

**Contributors should therefore point `JAVA_HOME` at their newest supported JDK, not at 17.** A machine-wide `JAVA_HOME` is inherited by every Java tool on the system; setting the global default to the *oldest* JDK a project needs is backwards. Projects pin downward — which is exactly what this repo does. Better still, scope `JAVA_HOME` per-project with a version manager (mise, SDKMAN) rather than setting it globally at all.

*(Superseded: an earlier revision of this plan argued for aligning all three slots on 17. That was optimizing for a simplicity the committed daemon criteria already provides, and it pushed a stale JDK onto the whole machine as a side effect.)*

**Daemon convergence, for free.** Google's guidance is to match `JAVA_HOME` with Android Studio's *Gradle JDK* setting so the two don't spawn separate daemons ([Android: Java versions in Android builds](https://developer.android.com/build/jdks)). The committed daemon criteria makes CLI and Studio converge on the same daemon JVM regardless of either setting — a more robust fix than matching by hand.

**JDK distribution — prefer Temurin or Corretto over Oracle builds.** Oracle JDK 17's free NFTC updates ended at **17.0.12 (July 2024)**; later 17.x releases fall under the OTN license and require a paid subscription for production use, with Oracle JDK 21 on the same one-year-past-next-LTS clock. Eclipse Temurin and Amazon Corretto are GPLv2-with-Classpath-Exception, free for commercial use, and continue receiving security updates — the sane default for both contributors and CI.

**Daemon JVM criteria.** `gradle/gradle-daemon-jvm.properties` is committed (generated via `./gradlew updateDaemonJvm --jvm-version=17`) so every contributor and CI runner gets an identical daemon JVM, auto-downloading one if absent. This requires the foojay resolver in `settings.gradle.kts` — without it the task fails with "Toolchain download repositories have not been configured." Note the generated URLs are opaque foojay redirect IDs pinning specific builds; good for reproducibility, but they are a external dependency that could rot, so treat regeneration as a routine maintenance action rather than a one-time event.

### Carried forward

- **D9 needs revision at milestone 4.** KGP 2.4.10 supports AGP **8.5.2 – 9.1.0**, which conflicts with §E2's provisional AGP 8.1 floor. The floor is about what *consumers* may use, not what we build with, but the `sample/` build and any TestKit matrix are constrained by this. Resolve when D9 is actually ruled.
- Build-with Gradle 9.5.0 versus the supported *floor* remains a milestone-4 question — `core` has no Gradle API surface, so milestones 1–3 are unaffected.
