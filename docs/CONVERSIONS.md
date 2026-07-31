# CONVERSIONS.md — Android `strings.xml` → Xcode String Catalog (`.xcstrings`)

Authoritative conversion spec for **Milestone 2** (golden-file corpus). Every rule below is stated
with its verified behavior, a citation, a confidence marker, and the corpus case that must encode it.

> **Status of this document relative to `PLAN.md`.** This document supersedes the first-pass tables
> in `PLAN.md` §2.2 and §2.3 wherever they disagree. Every disagreement is called out inline as
> **[CORRECTS PLAN]**; every rule the plan does not mention at all is called out as **[NEW]**.
> `PLAN.md` has deliberately not been edited (parallel work in flight) — a human merges from here.

---

## 0. How this document was produced

Three independent evidence classes, cited per rule:

| Tag | Meaning | Role |
|---|---|---|
| `[EXP-aapt2]` | Reproduced by running a real `aapt2` binary: **AAPT 2.19-12006047** (ships with AGP 8.7.2), macOS 26.5.2, on **2026-07-30**. Compile + link + `aapt2 dump resources`. | **Normative** |
| `[EXP-xcs]` | Reproduced by running **`xcstringstool`** from **Xcode 26.6 (17F113)**, on **2026-07-30**. `compile` a hand-written `.xcstrings` and inspect the emitted `.strings` / `.stringsdict`. | **Normative** |
| `[AOSP:<file> <Function>]` | Read from AAPT2 source: `aosp-mirror/platform_frameworks_base`, branch `master`, paths relative to `tools/aapt2/`. Observed **2026-07-30**. | Explanatory |
| `[DOC:<url>]` | Official vendor documentation. | Explanatory (see warning below) |

**Which citation is the real one.** The normative source of truth for this project is the *observable behavior of the shipped tools our users actually run* — not AOSP `master`. We are not implementing "whatever the AOSP source tree currently does"; we are implementing what a pinned `aapt2` and a pinned `xcstringstool` do. So every VERIFIED rule is warranted by a reproducible experiment against a pinned tool version, and §13 gives the exact recipe to re-run any of them.

AOSP citations are **explanatory**: they answer *why* a behavior exists, which experiments cannot. That value is real — the AAPT1-compatibility comment behind the double-space-around-spans wart tells you the bug is deliberate and won't be fixed, which changes how you treat it — but it lives entirely in the function and its comments.

**Citations therefore name functions, not line numbers.** Line numbers against a moving branch decay immediately and are worse than nothing: a stale one sends a reader to unrelated code and invites them to distrust claims that are actually correct. Function names (`StringBuilder::AppendText`, `ResourceParser::FlattenXmlSubtree`, `VerifyJavaStringFormat`, …) survive refactors and are directly greppable. If line-level precision is ever genuinely needed, pin a release tag or commit SHA rather than citing `master`.

Confidence markers:

- **VERIFIED** — confirmed by a reproduced experiment against a pinned tool, usually corroborated by source. Safe to write a parser against.
- **BEST-EFFORT** — no definitive primary source found, or not empirically exercised; a judgment
  call is recorded and flagged.

> ⚠️ **A note on the official Android documentation.** During this research the official
> `developer.android.com` string-resource page was found to be **factually wrong** about whitespace
> collapsing (see Rule W3). Where docs and AAPT2 behavior disagree, **AAPT2 behavior wins** — it is
> what actually ships. Cite the docs for intent; cite the source for behavior.

### Scope boundary

The authority for "what does this Android string *mean*" is **AAPT2 at build time**, not the
`android.content.res` runtime. Articulate reads `strings.xml` at build time and must agree with the
value AAPT2 would have baked into the resource table, because that is the value the Android app
actually ships.

---

## 1. The parsing pipeline (order matters)

**[CORRECTS PLAN]** `PLAN.md` §2.2 presents parsing as a flat table of independent rules. It is not.
AAPT2 runs a **six-stage pipeline**, and several of the plan's rules only make sense — and several
surprising behaviors only become explicable — once the ordering is fixed. A parser that implements
the plan's table as a set of independent passes in the wrong order will produce wrong output on
roughly a dozen of the cases below.

```
  ┌─ S0 ── XML parse (expat) ─────────────────────────────────────────┐
  │        entity refs resolved; CDATA delivered as ordinary text     │
  ├─ S1 ── Subtree flatten ───────────────────────────────────────────┤
  │        no-namespace tags  -> style spans (text kept, tag removed) │
  │        xliff:g            -> untranslatable section (tag removed) │
  │        other-namespace    -> warn + drop tag, keep children       │
  ├─ S2 ── Edge trim ─────────────────────────────────────────────────┤
  │        ONLY IF no span was seen anywhere in the subtree:          │
  │        trim leading ws of first text run, trailing ws of last     │
  ├─ S3 ── Android escape / quote / whitespace pass (StringBuilder) ──┤
  │        backslash escapes; `"` toggles quoting; ASCII ws runs -> ' '│
  │        bare `'` outside quoting = ERROR                           │
  ├─ S4 ── Item-type reinterpretation ────────────────────────────────┤
  │        the RAW (pre-S3) text is tried as @ref / ?attr / etc FIRST │
  ├─ S5 ── Format verification ───────────────────────────────────────┤
  │        VerifyJavaStringFormat — plain strings only, NOT styled    │
  └───────────────────────────────────────────────────────────────────┘
```

Source: `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]` (S1/S2), `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]` (S3),
`[AOSP:ResourceParser.cpp ResourceParser::ParseXml]` (S4), `[AOSP:ResourceParser.cpp ResourceParser::ParseString]` (S5).

Three consequences that fall directly out of the ordering and are each a corpus case:

1. **S2 before S3** means edge trimming operates on *raw* text, so a trailing `\` is trimmed of its
   following whitespace and then dropped as a dangling escape (Rule E5).
2. **S0 before S3** means `&quot;` and `&apos;` are indistinguishable from literal `"` and `'` by the
   time Android's escape layer runs (Rule X2 — this is a trap).
3. **S1 before S2** means the presence of *any* `<b>` anywhere in a string changes the leading/trailing
   whitespace of the *whole* string (Rule W2).

---

## 2. Escapes

### E1 — Backslash escape set — VERIFIED

The complete set recognized by AAPT2 is:

| Escape | Produces |
|---|---|
| `\t` | U+0009 TAB |
| `\n` | U+000A LF |
| `\#` | `#` |
| `\@` | `@` |
| `\?` | `?` |
| `\"` | `"` (and does **not** toggle quoting) |
| `\'` | `'` |
| `\\` | `\` |
| `\uXXXX` | the code point; exactly 4 hex digits, else hard error |
| `\` + *anything else* | **the backslash is discarded and the character is emitted literally** |

`[AOSP:ResourceUtils.cpp StringBuilder::AppendText]` `[EXP-aapt2]` `[DOC:https://developer.android.com/guide/topics/resources/string-resource]`

**[CORRECTS PLAN]** The plan lists `\'  \"  \n  \t  \\  \uXXXX`. It is **missing `\#`** entirely
(`\#` → `#`; verified: `\#\@\?` → `#@?`). The plan also lists "leading `@`/`?`" as a separate row, but
`\@` and `\?` are ordinary members of this same escape set and are **not** positional — `a\@b` → `a@b`.

**[NEW] `\r` is not a carriage return.** It falls into the default branch: the backslash is dropped
and a literal `r` is emitted. `carriage\rreturn` → `carriagerreturn` `[EXP-aapt2]`. This is a genuine
silent-corruption vector: an author who writes `\r\n` gets `r` + newline. Articulate must **reject**
`\r` rather than pass it through, because passing it through would faithfully reproduce a bug.

- Corpus: `escapes-basic`, `escapes-at-question`, **new:** `escapes-hash`, **new:** `escapes-carriage-return`, **new:** `escapes-unknown-passthrough`

### E2 — Unicode escape must be exactly 4 hex digits — VERIFIED

`\u` not followed by four valid hex digits is a hard error:
`"invalid unicode escape sequence in string\n\"<text>\""` `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]`.
Note `\uXXXX` is UTF-16-ish in spelling but AAPT2 appends the code point directly — there is no
surrogate-pair recombination of two adjacent `😀` escapes at this layer. **BEST-EFFORT**
on the surrogate-pair question: not empirically exercised; treat astral characters written as
surrogate pairs as an Articulate error rather than guessing.

- Corpus: **new:** `error-bad-unicode-escape`

### E3 — Trailing lone backslash is silently dropped — VERIFIED

If `\` is the last character, `iter.HasNext()` is false and nothing is appended.
`abc\` → `abc` `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]` `[EXP-aapt2]`.

- Corpus: **new:** `escapes-trailing-backslash`

### E4 — Escapes are processed *inside* quoted regions too — VERIFIED

Quoting suppresses whitespace collapsing and the apostrophe error; it does **not** suppress backslash
escapes. `"a\nb  c"` → newline preserved, `b  c` double space preserved `[EXP-aapt2]`.

- Corpus: **new:** `quoted-with-escapes`

### E5 — Escape processing happens *after* edge trimming — VERIFIED

`\q\z\ ` (note trailing space) → `qz`. The trailing space is trimmed at S2, leaving `\q\z\`, whose
dangling backslash is then dropped at S3 `[EXP-aapt2]`. An implementation that unescapes first and
trims second would produce `qz ` — wrong.

- Corpus: **new:** `escapes-trim-order`

---

## 3. Quoting and whitespace

### Q1 — `"` is a *toggle*, not a delimiter pair — VERIFIED

Each unescaped `"` flips a boolean. The quote characters themselves are **never emitted**. There is
no "unbalanced quote" error. `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]`

Consequences, all verified `[EXP-aapt2]`:

| Input | Output |
|---|---|
| `"  spaced  don't  "` | `  spaced  don't  ` |
| `a"  b  "c` | `a  b  c` (mid-string quoted region) |
| `say "hello  world  and don't stop` | `say hello  world  and don't stop` |

**[NEW]** That third row is the important one. A *single stray* `"` turns quoting on for **the entire
rest of the string** — preserving all whitespace and legalizing every subsequent bare apostrophe. The
exact same apostrophe that is a hard error in one string compiles silently in another purely because
of an earlier unmatched quote. Articulate should **warn or error on an odd number of unescaped `"`**;
it is almost always an authoring mistake and it changes the meaning of everything after it.

- Corpus: `quoted-whitespace`, **new:** `quoted-mid-string`, **new:** `error-odd-quote-count`

### Q2 — Quoting state **resets at every span boundary** — VERIFIED

`ResetTextState()` sets `quote_ = preserve_spaces_` (i.e. `false`) at the start and end of every span.
A quoted region therefore **cannot span an inline tag**.
`[AOSP:ResourceUtils.cpp StringBuilder::ResetTextState]`, `[AOSP:ResourceUtils.h StringBuilder class doc comment]`

Verified `[EXP-aapt2]`: `"x  <b>y</b>  z"` → `x  y z`. The two spaces before `<b>` survive (still
quoted); the two spaces after `</b>` collapse to one (quoting was reset). **[NEW]** — the plan has
nothing on this and it is not intuitive.

- Corpus: **new:** `quote-reset-at-span`

### W1 — Whitespace collapsing is Android's own rule, not XML's — VERIFIED

**[CORRECTS PLAN]** The plan's §2.2 does not say whose rule this is; the research question asked
explicitly. Answer: it is **neither** XML attribute-value normalization **nor** `xml:space`. It is a
bespoke pass in AAPT2's `StringBuilder`, applied to element *content* after XML parsing, and it is
maintained bug-for-bug from AAPT1: `"NOTE: This is all the way it is because AAPT1 did it this way.
Maintaining backwards compatibility is important."` `[AOSP:ResourceUtils.h StringBuilder class doc comment]`

The rule: outside a quoted region, a run of one or more whitespace characters collapses to exactly
one U+0020. `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]`

### W2 — Leading/trailing whitespace: trimmed, **unless the string contains a span** — VERIFIED

This is the answer to the "trimmed vs preserved" question, and it is conditional.

- **No span anywhere in the string:** the first text run is left-trimmed and the last text run is
  right-trimmed, on the raw text, before escape processing. `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`
- **Any no-namespace tag anywhere in the string:** trimming is skipped entirely, so leading and
  trailing whitespace survives — collapsed to a single space by W1, but **not removed**.

Verified `[EXP-aapt2]`:

| Input | Output |
|---|---|
| `   hello   world   ` | `hello world` |
| `  <b>x</b>  y  ` | `␣x y␣` (leading and trailing single space retained) |
| `  <xliff:g id="x">A</xliff:g>  B  ` | `A B` (xliff:g is not a span, so trimming applies) |

The AAPT2 header comment states the general case as *"This happens at the start and end of the string
as well, so leading and trailing whitespace is possible"* `[AOSP:ResourceUtils.h StringBuilder class doc comment]` — which is
true of `StringBuilder` in isolation, but `ResourceParser` pre-trims in the common (span-free) path.
Both layers must be modeled; modeling only one gives the wrong answer for half the cases.

- Corpus: `whitespace-collapse`, **new:** `whitespace-edges-with-span`, **new:** `whitespace-edges-with-xliff`

### W3 — Only **ASCII** whitespace collapses — VERIFIED, and the official docs are wrong

The collapse test is guarded by `codepoint <= std::numeric_limits<char>::max()` (i.e. ≤ 0x7F) before
calling `isspace()`. Non-ASCII Unicode spaces are **not** whitespace for this purpose.
`[AOSP:ResourceUtils.cpp StringBuilder::AppendText]`

Verified `[EXP-aapt2]`, byte-for-byte:

| Input | Actual output |
|---|---|
| `x&#32;&#8200;&#8195;y` | `x` `U+0020` `U+2008` `U+2003` `y` — **not** collapsed |
| `a&#160;&#160;b` | `a` `U+00A0` `U+00A0` `b` — **not** collapsed |
| `a&#9;&#10;&#13;b` | `a` `U+0020` `b` — TAB/LF/CR all collapsed together |

**[CORRECTS DOCS]** The official page claims: *"`<string> &#32; &#8200; &#8195;</string>` (space,
punctuation space, Unicode Em space) all collapse to a single space"*
`[DOC:https://developer.android.com/guide/topics/resources/string-resource]`. **This is false** for
AAPT2 2.19. Only the ASCII space collapsed; U+2008 and U+2003 passed through untouched and unmerged.
Do not implement the documented behavior.

**[NEW]** `&#11;` (VT) and `&#12;` (FF) are rejected by the *XML* parser before Android ever sees
them: `error: xml parser error: reference to invalid character number` `[EXP-aapt2]` — they are
outside XML 1.0's legal character set.

- Corpus: **new:** `whitespace-unicode-spaces-preserved`, **new:** `error-invalid-xml-char-ref`

---

## 3a. XML entities

### X1 — Entities are resolved by the XML parser, before Android sees anything — VERIFIED

`&amp; &lt; &gt;` behave as expected and their resolved characters are inert to Android's layer
`[EXP-aapt2]`. Numeric character references work the same way. This half of the plan's `xml-entities`
row is correct.

### X2 — `&quot;` and `&apos;` are **not** escape hatches — VERIFIED **[CORRECTS PLAN]**

**[CORRECTS PLAN]** The plan's row reads *"`&amp;` `&lt;` `&gt;` `&quot;` `&apos;` resolved by the
XML parser"* — true but dangerously incomplete. Because S0 precedes S3, the resolved `"` and `'` are
then **re-processed by Android's escape layer** and behave exactly like literal ones:

- `&quot;` produces a `"` that **toggles the quoting state and is then consumed** — it does *not*
  appear in the output.
- `&apos;` produces a `'` that hits the apostrophe check and **hard-errors** (A2).

Verified `[EXP-aapt2]`, byte-exact: input `&amp; &lt; &gt; &quot; &apos;` compiles to `& < >  '` —
note the **two** spaces before the apostrophe, and no double quote anywhere. Trace: `>` `space`, then
`&quot;`→`"` turns quoting *on* and vanishes, then the next space is preserved verbatim *because
quoting is now on*, then `&apos;`→`'` is legal *only because quoting is on*. Remove any one of these
and the string errors or renders differently.

The only reliable way to get a literal double quote is `\"`, and a literal apostrophe is `\'`.

- Corpus: `xml-entities` *(semantics change: must assert the two-space, no-quote output above)*,
  **new:** `error-apos-entity`

## 4. The apostrophe

### A1 — A bare `'` outside quoting is a hard **error** — VERIFIED

Answering the research question directly: **error, not warning.** `[AOSP:ResourceUtils.cpp StringBuilder::AppendText]`

Exact AAPT2 diagnostic (reproduced verbatim `[EXP-aapt2]`):

```
res/values/strings.xml:3: error: unescaped apostrophe in string
"don't".
res/values/strings.xml:3: error: not a valid string.
res/values/strings.xml: error: file failed to compile.
```

Note the shape: a two-line message (the second line is the offending raw text chunk, quoted), then a
cascading `not a valid string` from `ParseString`, then a file-level failure. Articulate's message
should mirror the first line's wording so that Android developers recognize it.

It is **not** downgradable: `aapt2 compile --legacy` ("Treat errors that used to be valid in AAPT as
warnings") leaves it a hard error `[EXP-aapt2]`.

**Lint rule name:** none. This is an AAPT2 compile error, not a Lint check — there is no
`UnescapedApostrophe` issue id. The nearest Lint neighbours are `StringFormatInvalid` /
`StringFormatMatches` (format-specifier checks) and `ExtraTranslation` / `MissingTranslation`
(key-parity checks). **BEST-EFFORT** on the negative claim: verified by absence in the
`android-custom-lint-rules` checks index rather than by a positive source.

- Corpus: `error-bare-apostrophe`

### A2 — `&apos;` errors identically — VERIFIED, and this is a trap

**[NEW]** Because XML entity resolution (S0) precedes Android escape processing (S3), `&apos;`
produces a real `'` codepoint that then hits the apostrophe check. `don&apos;t` fails with the *exact
same* error as `don't` `[EXP-aapt2]`. The entity is not an escape hatch.

- Corpus: **new:** `error-apos-entity`

### A3 — A bare `'` inside CDATA also errors — VERIFIED

See §5. `<![CDATA[don't]]>` → same error `[EXP-aapt2]`. CDATA is not an escape hatch either.

- Corpus: **new:** `error-cdata-apostrophe`

---

## 5. CDATA — **[CORRECTS PLAN]**, decisively

### C1 — CDATA content is **not** taken verbatim — VERIFIED

`PLAN.md` §2.2 states: *"CDATA | content taken verbatim"*. **This is wrong.**

Mechanism: AAPT2's XML layer registers `XML_SetCdataSectionHandler`, which pushes bare
`kCdataStart` / `kCdataEnd` markers `[AOSP:xml/XmlPullParser.cpp XmlPullParser::StartCdataSectionHandler]`. The *content* arrives
through the ordinary `CharacterDataHandler` as a `kText` event
`[AOSP:xml/XmlPullParser.cpp XmlPullParser::CharacterDataHandler]`. `FlattenXmlSubtree`'s event switch has no case for
`kCdataStart`/`kCdataEnd` — they fall to `default: // ignore` `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`.
CDATA text is therefore fed to exactly the same `StringBuilder` as any other text.

Verified consequences `[EXP-aapt2]`:

| Input | Output | Meaning |
|---|---|---|
| `<![CDATA[  a   b  ]]>` | `a b` | whitespace collapsed **and** edge-trimmed |
| `<![CDATA[don't]]>` | **ERROR** `unescaped apostrophe in string` | escape layer still applies |
| `<![CDATA["don't"]]>` | `don't` | Android quoting works inside CDATA |
| `<![CDATA[<b>bold</b> &amp; more]]>` | `<b>bold</b> &amp; more` | XML markup and entities are inert |

**What CDATA actually does** is suppress **S0** only: no tag interpretation, no entity resolution.
Everything from S2 onward is unchanged. So `\n` inside CDATA is still a newline, `\'` is still needed,
and `%` is still a format specifier.

### C2 — CDATA is valid but non-idiomatic Android — BEST-EFFORT

It is legal XML and AAPT2 accepts it. It appears in the wild almost exclusively as a way to embed
HTML markup for `Html.fromHtml()` at runtime — i.e. precisely the case where the author wants the tag
text to *survive as text* rather than become a style span. No official Android documentation
recommends or even mentions CDATA in `<string>`; the string-resource page does not contain the word.
Judgment call recorded: treat CDATA as a **rare edge case whose only real-world purpose is runtime
HTML**, which is why it lands squarely in D4 (§10).

- Corpus: `cdata-not-verbatim` (renames the plan's implicit "policy — see D4" row into a real case),
  **new:** `cdata-inert-markup`, **new:** `error-cdata-apostrophe`

---

## 6. Inline markup — **[CORRECTS PLAN]** on mechanism

### M1 — Tags become **style spans**; the text survives, the tag does not — VERIFIED

Answering the research question: it is **neither** verbatim pass-through **nor** a `Html.fromHtml`
round trip. Any element inside `<string>` with an **empty namespace** is converted into a
`android::Span` — a `(name, start, end)` triple recorded in a channel *parallel* to the text — and the
resource becomes a `StyledString` rather than a `String`
`[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`, `[AOSP:ResourceParser.cpp ResourceParser::ParseXml]`.

The span name encodes attributes as `tag;attr=value;attr=value`
`[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`.

Verified `[EXP-aapt2]` (from `aapt2 dump resources`, `name:start,end`):

| Input | Text | Spans |
|---|---|---|
| `a <b>b <i>c</i> d</b> e` | `a b c d e` | `b:2,6` `i:4,4` |
| `Hello <annotation font="title">World</annotation>` | `Hello World` | `annotation;font=title:6,10` |
| `line1<br/>line2` | `line1line2` | `br:5,4` |

Note `<br/>`: the text contains **no newline**. The line break exists only as a span that
`Html.fromHtml`/`getText()` interprets at render time. `getString()` returns `line1line2`.
This is the key structural fact for D4: **the text channel of a styled string is already lossy on
Android itself**, and it is the only channel `.xcstrings` has.

### M2 — Spans defeat whitespace collapsing at their boundaries — VERIFIED

`ResetTextState()` clears the "last codepoint was a space" flag at every span start and end, so a
space immediately adjacent to a tag is always emitted.
`[AOSP:ResourceUtils.cpp StringBuilder::ResetTextState]`, documented as a known wart at `[AOSP:ResourceUtils.h StringBuilder class doc comment]`.

Verified `[EXP-aapt2]`: `This <b> is </b> spaced` → `This␣␣is␣␣spaced` — **double** spaces, whereas
the same string without tags collapses correctly. This is an AAPT1-compatibility bug that AAPT2
deliberately preserves.

- Corpus: **new:** `span-double-space`

### M3 — Spans **bypass** format-string verification — VERIFIED, safety-relevant

`VerifyJavaStringFormat` runs only on the `String` branch of `ParseString`; the `StyledString` branch
sets translatable and returns without checking `[AOSP:ResourceParser.cpp ResourceParser::ParseString]`.

Verified `[EXP-aapt2]`: `%s and %d` → **hard error** (non-positional, 2 args).
`%s and <b>%d</b>` → **compiles clean**, spans `b:7,8`.

**[NEW]** Wrapping one placeholder in `<b>` silently disables AAPT2's only argument-ordering safety
check. Articulate must run its own positional-discipline check on **all** strings, styled or not —
it cannot inherit AAPT2's, because AAPT2 has a hole here.

- Corpus: **new:** `error-styled-nonpositional`

### M4 — `<xliff:g>` marks an untranslatable section, not a span — VERIFIED **[NEW — entirely missing from the plan]**

The XLIFF 1.2 namespace `urn:oasis:names:tc:xliff:document:1.2` is special-cased
`[AOSP:ResourceParser.cpp kXliffNamespaceUri]`. `<xliff:g>` produces an `UntranslatableSection` (a start/end index
pair), **not** a span `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`. Consequences:

- The resource stays a plain `String`, so **format verification still runs** (unlike M3).
- Whitespace collapses **correctly** across the boundary — explicitly contrasted against the span bug
  at `[AOSP:ResourceUtils.h StringBuilder class doc comment]`. Verified: `This <xliff:g id="n">is</xliff:g> spaced` →
  `This is spaced` (single spaces) `[EXP-aapt2]`.
- Edge trimming still applies, because `saw_span_node` stays false. Verified:
  `  <xliff:g id="x">A</xliff:g>  B  ` → `A B` `[EXP-aapt2]`.
- Nested `<xliff:g>` is a hard error: `illegal nested XLIFF 'g' tag` `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`.
- Non-`g` XLIFF tags are silently ignored, with no warning `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]`.

**This matters a great deal.** `<xliff:g id="name" example="Bob">%1$s</xliff:g>` is the *recommended*
idiom for annotating placeholders in Android string resources, and it is extremely common in
real-world projects — including AOSP's own. A converter that treats it like `<b>` (a span) or that
errors on it will reject a large fraction of real input. Verified round trip `[EXP-aapt2]`:
`Hello <xliff:g id="name" example="Bob">%1$s</xliff:g>, you have <xliff:g id="n">%2$d</xliff:g>`
→ `Hello %1$s, you have %2$d`.

**Recommended handling:** unwrap `<xliff:g>` transparently (keep the inner text, drop the tag), and
optionally lift the `id`/`example` attributes into the `.xcstrings` `comment` field, which is exactly
what they are for. This should be **explicitly excluded from D4** — it is not "inline markup", it is
placeholder metadata, and it has a clean, lossless mapping. Treating it as part of the D4 markup
question would be a category error.

- Corpus: **new:** `xliff-g-unwrap`, **new:** `xliff-g-whitespace`, **new:** `xliff-g-attrs-to-comment`, **new:** `error-nested-xliff-g`

### M5 — Foreign-namespace tags warn and are dropped — VERIFIED

Any other namespaced element produces
`warn: ignoring element '<name>' with unknown namespace '<uri>'`, the tag is dropped and its children
are kept `[AOSP:ResourceParser.cpp ResourceParser::FlattenXmlSubtree]` `[EXP-aapt2]`. Relevant for `tools:` attributes and
stray namespaced markup.

- Corpus: **new:** `foreign-namespace-warn`

---

## 7. Keys, duplicates, references, translatability

### K1 — Resource entry name grammar — VERIFIED

**[CORRECTS PLAN]** The plan says "must be a valid Android resource name" without specifying it.
The actual grammar `[AOSP:text/Unicode.cpp IsValidResourceEntryName]`:

```
entry-name  ::=  first  rest*
first       ::=  <any codepoint with Unicode XID_Start>  |  '_'
rest        ::=  <any codepoint with Unicode XID_Continue>  |  '.'  |  '-'
```

- **Cannot start with a digit** (digits are XID_Continue but not XID_Start). Verified: `1abc` →
  `error: resource 'string/1abc' has invalid entry name '1abc` `[EXP-aapt2]`. (The unbalanced quote
  is AAPT2's own bug, reproduced verbatim `[AOSP:ResourceTable.cpp ResourceTable::AddResource]`.)
- **`$` is not allowed** in a resource entry name — unlike a Java identifier, where it is. Verified:
  `a$b` rejected `[EXP-aapt2]`. This is the one place `IsValidResourceEntryName` deliberately diverges
  from `IsJavaIdentifier` `[AOSP:text/Unicode.cpp IsJavaIdentifier]`.
- **`.` and `-` are allowed** and are *not* Java identifier characters. Verified: `a.b` and `a-b` both
  compile clean `[EXP-aapt2]`.
- **Non-ASCII identifiers are allowed.** Verified: `héllo` compiles `[EXP-aapt2]`.
- **Case-sensitive**, no case folding. `MyKey` compiles and stays `MyKey` `[EXP-aapt2]`.
- **No reserved words.** `IsJavaIdentifier`'s own comment says *"This does not check against the set
  of reserved Java keywords"* `[AOSP:text/Unicode.h IsJavaIdentifier doc comment]`, and `IsValidResourceEntryName` is even
  looser. Verified: `class` compiles as a key `[EXP-aapt2]`.

**[NEW] Articulate should be stricter than AAPT2 here.** `.` and `-` are legal in `strings.xml` but
break the Swift symbol generation on the other end (Xcode's `xcstringstool generate-symbols`) and
break `R.` access in Java/Kotlin. `.xcstrings` itself imposes no key constraint at all — verified:
empty-string keys, dotted keys, dashed keys and `class` all compile fine `[EXP-xcs]`. So the
constraint is not "what does either format accept", it is "what survives symbol generation on both
sides". Recommend: **accept the AAPT2 grammar, but warn on `.`/`-` and on non-ASCII**, since those
strings will not produce usable generated symbols.

- Corpus: `error-bad-key`, **new:** `key-dot-dash-warn`, **new:** `key-unicode`, **new:** `key-reserved-word-ok`

### K2 — Duplicate key in one file is a hard error — VERIFIED

**[CONFIRMS PLAN, now with a citation.]** The plan asserted "error" without a source. Confirmed.

Two strong non-attribute values for the same (name, config) resolve to `CollisionResult::kConflict`
`[AOSP:ResourceTable.cpp ResourceTable::ResolveValueCollision]`, which emits `[AOSP:ResourceTable.cpp ResourceTable::AddResource]`:

```
res/values/strings.xml:3: error: duplicate value for resource 'string/a' with config ''.
res/values/strings.xml:3: error: resource previously defined here.
```

Verified `[EXP-aapt2]`; **not** downgraded by `--legacy`. There is no last-write-wins and no silent
merge. Note the diagnostic keys on `(name, config)`, so the same key in `values/` and `values-de/` is
of course fine — that is the whole point.

- Corpus: `error-duplicate-key`

### K3 — Duplicate `<item quantity=…>` in one `<plurals>` is a hard error — VERIFIED **[NEW]**

`error: duplicate quantity 'one'` `[AOSP:ResourceParser.cpp ResourceParser::ParsePlural]` `[EXP-aapt2]`.
An invalid quantity keyword is likewise a hard error:
`error: <item> in <plural> has invalid value 'six' for attribute 'quantity'`
`[AOSP:ResourceParser.cpp ResourceParser::ParsePlural]` `[EXP-aapt2]`. The six legal keywords are exactly
`zero one two few many other` `[AOSP:ResourceParser.cpp ResourceParser::ParsePlural]`.

- Corpus: **new:** `error-duplicate-quantity`, **new:** `error-bad-quantity`

### K4 — Unescaped leading `@` / `?` is **not** an error — it silently becomes a reference — VERIFIED

**[CORRECTS PLAN]** The plan's row reads: *"Leading `@`/`?` — must be escaped in Android; unescape on
parse."* The first half misstates the failure mode, and the failure mode is the dangerous part.

`ParseXml` tries `TryParseItemForAttribute` on the **raw** (pre-escape) value *before* falling back to
a string `[AOSP:ResourceParser.cpp ResourceParser::ParseXml]`. So:

- `<string name="a">@string/other</string>` **compiles clean** `[EXP-aapt2]`. It is not a string at
  all — it is a *reference*. If `string/other` exists, it silently aliases. If it does not, the failure
  surfaces only at **link** time, in a different tool invocation, as
  `error: resource string/other (aka com.example.t:string/other) not found` `[EXP-aapt2]`.
- `<string name="a">?attr/x</string>` behaves identically `[EXP-aapt2]`.
- `<string name="a">\@string/other</string>` → the literal string `@string/other`, because the *raw*
  value `\@string/other` is not parseable as a reference and the escaped form is used for the string
  `[EXP-aapt2]`. This is precisely why S4 operates on raw text.

**Articulate must hard-error on an unescaped leading `@` or `?`.** It cannot resolve Android
references, and silently emitting the literal text `@string/other` into a translation catalog would
ship the reference syntax to users.

**Only `@` and `?` are affected.** `ParseString` passes a type mask of `TYPE_STRING` alone
`[AOSP:ResourceParser.cpp ResourceParser::ParseString]`, so color/integer/boolean parsing is not in play — references are
matched unconditionally, everything else is not. Verified `[EXP-aapt2]`: `#FF0000`, `42`, `true` and
`+15551234` all stay plain strings inside `<string>`. `\#` remains a valid escape (E1) but escaping
`#` is not *required* here; it matters in other resource contexts.

- Corpus: `escapes-at-question`, **new:** `error-unescaped-leading-at`,
  **new:** `error-unescaped-leading-question`, **new:** `string-literal-lookalikes`

### K5 — `translatable="false"` — VERIFIED, with a recommended refinement

Documented semantics: the attribute is parsed as a boolean (an unparseable value is a hard error,
`invalid value for 'translatable'. Must be a boolean`) and sets a flag on the value
`[AOSP:ResourceParser.cpp ResourceParser::ParseString]`. It is also accepted on `<string-array>`
`[AOSP:ResourceParser.cpp ResourceParser::ParseArrayImpl]`.

**[CONFIRMS PLAN's split.]** The plan asks: *"excluded from catalog entirely (and from Android copy?
no — copied for Android, skipped for iOS)"*. Confirmed: the string **is** compiled into the Android
resource table and **is** present in the linked APK — verified by dumping a linked APK containing
`<string name="untrans" translatable="false">Do not translate</string>`, which appears normally in
the resource table `[EXP-aapt2]`. `translatable="false"` is a *translation-tooling* hint, not a
resource-exclusion directive.

**Lint interaction — answering the research question directly.** `ExtraTranslation` (id
`ExtraTranslation`, category *Correctness: Messages*, **severity Fatal**) fires when a key exists in a
locale-specific file but not in the default locale, and it **respects `translatable="false"`**:
strings so marked are excluded from the check
`[DOC:https://googlesamples.github.io/android-custom-lint-rules/checks/ExtraTranslation.md.html]`.
Its counterpart `MissingTranslation`
`[DOC:https://googlesamples.github.io/android-custom-lint-rules/checks/MissingTranslation.md.html]`
covers the opposite direction and is the reason `translatable="false"` exists at all.

**BEST-EFFORT** on one sub-question: whether Lint flags a *translation supplied for* a key marked
non-translatable in the default locale. The published `ExtraTranslation` explanation covers
"translated here but not found in default locale"; it does not clearly document the
"translated-here-but-marked-untranslatable" case. No definitive source found. Judgment call:
Articulate should treat this as an **error** — supplying a translation for a string the author
declared untranslatable is unambiguously a mistake, and erroring costs nothing.

**[NEW] Recommended refinement to the plan's rule.** The plan says non-translatable strings are
"excluded from the catalog entirely". `.xcstrings` has a **direct equivalent**: the top-level
`"shouldTranslate": false` key on a string entry. Verified `[EXP-xcs]`: a `shouldTranslate: false`
entry compiles normally and its value **is** emitted into the built `.strings` — it is excluded from
*translation*, not from the *build*. That is exactly Android's semantics, one-for-one.

Recommend: **emit non-translatable strings with `"shouldTranslate": false` rather than dropping
them.** Dropping them breaks key parity between the two platforms for no reason, silently removes a
string the iOS app may legitimately reference, and throws away information the target format can
represent natively. This is a small change with a real correctness payoff.

- Corpus: `translatable-false` (semantics change: emit with `shouldTranslate:false`, do not drop),
  **new:** `error-bad-translatable-value`, **new:** `error-translation-for-untranslatable`

### K6 — Orphan translation key — BEST-EFFORT, plan confirmed

A key present in `values-de/` but absent from `values/` is not an AAPT2 error (both compile; the
locale-qualified value simply has no default). It **is** a Lint *Fatal* under `ExtraTranslation`
`[DOC: ExtraTranslation.md.html]`. The plan's "error (orphan translation)" is therefore correct in
spirit and correct in severity for any project running Lint — which is the default for release builds.
Confirmed as-is.

- Corpus: `error-orphan-key`

### K7 — `formatted="false"` — VERIFIED **[NEW — missing from the plan entirely]**

`<string name="x" formatted="false">` disables `VerifyJavaStringFormat` for that string
`[AOSP:ResourceParser.cpp ResourceParser::ParseString]`. Verified `[EXP-aapt2]`: `%s and %s` errors normally but
compiles clean with `formatted="false"`.

Semantically it declares *"the `%` characters in here are literal, this string is never passed to
`String.format`"*. Articulate must honor it: a `formatted="false"` string's `%` sequences must be
passed through **unconverted** — and since `.xcstrings` has no equivalent opt-out, any literal `%`
must be **escaped to `%%`** on the way out, or iOS's formatter will consume it. **[NEW]** This is a
real conversion, not a pass-through, and the plan has no rule for it.

- Corpus: **new:** `formatted-false-escapes-percent`

---

## 8. Placeholders

### P0 — Positional discipline: **VERIFIED, and it is AAPT2's default** — plan confirmed

`VerifyJavaStringFormat` returns false when `arg_count > 1 && nonpositional`
`[AOSP:util/Util.cpp VerifyJavaStringFormat]`. `aapt2 compile` treats that as a **hard error** by default:

```
error: multiple substitutions specified in non-positional format;
did you mean to add the formatted="false" attribute?
```

Verified `[EXP-aapt2]`. It downgrades to a warning under `aapt2 compile --legacy`
("Treat errors that used to be valid in AAPT as warnings") `[EXP-aapt2]`. The plan's rule
("mandatory when a string has ≥2 specifiers") therefore matches AAPT2's default exactly — confirmed
with citation.

Two refinements the plan does not capture:

- **`%<` (argument reuse) counts as non-positional** and triggers the error, with the source comment
  *"Reusing last argument, bad idea since positions can be moved around during translation"*
  `[AOSP:util/Util.cpp VerifyJavaStringFormat]`. Verified `[EXP-aapt2]`. Articulate should reject `%<` outright —
  iOS has no equivalent.
- **[NEW] The check has two holes**, both verified, both of which Articulate must close because it
  cannot rely on AAPT2 having caught them:
  1. **Styled strings skip it entirely** (M3).
  2. **A `Time.format` look-alike short-circuits it.** If any conversion character is one of
     `D F K M W Z k m w y z`, `VerifyJavaStringFormat` **returns true immediately**, abandoning the
     rest of the scan `[AOSP:util/Util.cpp VerifyJavaStringFormat]`. Verified `[EXP-aapt2]`: `%y and %s and %d` —
     three non-positional arguments — **compiles clean**.

- Corpus: `error-nonpositional-multi`, **new:** `error-arg-reuse`, **new:** `error-time-format-shortcircuit`, **new:** `error-styled-nonpositional`

### P1 — Conversion table (revised)

| Android (Java `Formatter`) | iOS emission | Status | Evidence |
|---|---|---|---|
| `%s`, `%1$s` | `%@`, `%1$@` | VERIFIED | object slot; `%@` is the only object conversion `[DOC: Apple format specifiers]` |
| `%d`, `%1$d` | `%lld`, `%1$lld` | **DECIDED (D3) — out of scope** | reconfirmed empirically below |
| `%f`, `%.Nf`, `%e`, `%E` | **unchanged** | **VERIFIED** | see P2 |
| `%g`, `%G` | **HARD ERROR** | **VERIFIED — [CORRECTS PLAN]** | see P3 |
| `%x`, `%X`, `%o` | **`%llx`, `%llX`, `%llo`** | **VERIFIED — [CORRECTS PLAN]** | see P4 |
| `%a`, `%A` | **HARD ERROR** | BEST-EFFORT | both are C99 hex-float; not exercised. Rare enough to reject rather than guess at. *(Cell corrected 2026-07-30 during the milestone-2 audit: it previously read "unchanged", contradicting this same row's own prose and `PLAN.md` §2.3, which both say hard-error. The implementation hard-errors; the table cell was the stale half.)* |
| `%%` | `%%` | VERIFIED | literal percent on both sides |
| `%n` | HARD ERROR | VERIFIED | Java: platform line separator `[DOC: Formatter]`. No C equivalent; `%n` is not a conversion in Foundation. |
| `%b`, `%B`, `%h`, `%H` | HARD ERROR | VERIFIED | Java-only: `%b`→`String.valueOf(boolean)`, `%h`→`Integer.toHexString(arg.hashCode())` `[DOC: Formatter]` |
| `%c`, `%C` | HARD ERROR | VERIFIED | Java `%c` = a Unicode character (possibly a surrogate pair); Foundation `%c` = **8-bit `unsigned char`**, `%C` = one UTF-16 unit `[DOC: Apple format specifiers]`. Not equivalent. |
| `%S` | HARD ERROR | VERIFIED **[NEW]** | Java `%S` = uppercased string. Foundation `%S` = **null-terminated UTF-16 array** — a completely different meaning, and a memory-unsafe one. Silent-corruption risk; must error. |
| `%,d` (grouping flag) | HARD ERROR | VERIFIED | see P5 |
| `%(d` (parens-for-negative flag) | HARD ERROR | VERIFIED | Java-only flag `[DOC: Formatter]`; C's `(` is not a flag |
| `%tX` (date/time) | HARD ERROR | VERIFIED | Java-only conversion family |
| `%.Ns` (precision on a string) | HARD ERROR | VERIFIED **[NEW]** | see P6 |

### P2 — `%f` / `%.Nf` / `%e` are compatible — VERIFIED (was UNVERIFIED in the plan)

Run side by side over `{1.0, 100.0, 1e-4, 1e-5, 123456789.0, 1.5, 0.25, 2.5}`, Java 17
(`String.format(Locale.US, …)`) vs C (`printf`) vs Swift (`String(format:)`): **`%.2f` and `%e` agreed
on every value, character for character.** Java's `%e` exponent is *"zero-padded to include at least
two digits"* `[DOC: Formatter]`, matching C. Default precision is 6 in both.

Two residual divergences, neither fixable by the converter, both worth recording:

- **[NEW] Rounding mode.** Java specifies **`RoundingMode.HALF_UP`** `[DOC: Formatter, 'f' and 'e']`;
  C/Foundation use the current IEEE-754 rounding mode, i.e. **half-to-even**. Verified divergence:

  | | Java 17 | C / Swift |
  |---|---|---|
  | `%.1f` of `0.25` | `0.3` | `0.2` |
  | `%.1f` of `0.35` | `0.4` | `0.3` |
  | `%.2f` of `1.005` | `1.01` | `1.00` |
  | `%.2f` of `0.125` | `0.13` | `0.12` |

  This is inherent to the platforms. Document it; do not attempt to compensate.

- **[NEW] Locale sensitivity — this one is significant for an i18n tool.** Java's `%f`, `%e`, `%g` and
  `%d` apply *"the localization algorithm"* using the `Formatter`'s locale `[DOC: Formatter]`, and
  Android's `Context.getString(int, Object...)` formats with the configuration locale. Foundation's
  `String(format:)` **without an explicit locale is locale-independent (POSIX)**. Verified:

  | | Android (`de`) | iOS default | iOS with `locale:` |
  |---|---|---|---|
  | `%.2f` of `1234.5` | `1234,50` | `1234.50` | `1.234,50` |

  So the *same converted string* renders `1.234,50` on Android and `1234.50` on iOS unless the iOS
  call site passes a locale. **This is a real, shipped-to-users behavioral divergence that the
  catalog format cannot express.** Recommend: emit a prominent note in generated-file headers and in
  the README; consider a lint in a later milestone. Not blocking for v0, but it belongs in the spec.

- Corpus: **new:** `float-passthrough`, **new:** `float-rounding-divergence-doc` (documentation-only case)

### P3 — `%g` is **NOT** compatible — VERIFIED **[CORRECTS PLAN]**

The plan groups `%g` with `%f`/`%e` as *"unchanged — asserted as 'C-compatible on both' —
UNVERIFIED"*. The assertion is **false**.

The *trigger thresholds* do match — Java switches to scientific below `10⁻⁴` or at/above `10^precision`
`[DOC: Formatter, 'g']`, and Foundation documents the same `[DOC: Apple format specifiers, %g]`. The
divergence is **trailing zeros**: C99 removes them from `%g` output unless `#` is given; Java does not,
because Java specifies *"The total number of significant digits in m is equal to the precision"*
`[DOC: Formatter, 'g']`. Verified head-to-head:

| value | Java 17 `%g` | C `%g` | Swift `%g` |
|---|---|---|---|
| `1.0` | `1.00000` | `1` | `1` |
| `100.0` | `100.000` | `100` | `100` |
| `1e-4` | `0.000100000` | `0.0001` | `0.0001` |
| `1e-5` | `1.00000e-05` | `1e-05` | `1e-05` |
| `1.5` | `1.50000` | `1.5` | `1.5` |
| `0.25` | `0.250000` | `0.25` | `0.25` |

Swift matches C, not Java, on every row. Passing `%g` through unchanged would visibly change the
rendered number in shipped translations — exactly the silent-5% failure this project exists to
prevent.

**Recommendation: hard error on `%g`/`%G`** with guidance pointing the author at `%.Nf` or `%e`.
There is no faithful mechanical rewrite (`%g` → `%.5f` is wrong for large magnitudes, `%g` → `%e` is
wrong for small ones). `%g` is vanishingly rare in user-facing translated strings; erroring costs
almost nothing and guessing costs correctness.

- Corpus: **new:** `error-percent-g`

### P4 — `%x` / `%o` need the `ll` length modifier — VERIFIED **[CORRECTS PLAN]**

The plan says *"unchanged + width/precision flags preserved — UNVERIFIED"*. The width/precision half is
right; the "unchanged" half has the **same 32-bit truncation bug D3 was created to fix**.

Apple's own table: `%x` / `%X` / `%o` are *"Unsigned **32-bit** integer (`unsigned int`)"*
`[DOC: Apple format specifiers]`. Swift's `Int` is 64-bit. Verified `[EXP-xcs]`/Swift on
Xcode 26.6:

```swift
String(format: "%d",   Int(4294967296))  //  "0"          <- D3's bug
String(format: "%x",   Int(4294967296))  //  "0"          <- the same bug, in hex
String(format: "%llx", Int(4294967296))  //  "100000000"  <- correct
```

Java's `%x` on a `Long` produces the full 64-bit value (`String.format("%x", -1L)` → sixteen `f`s,
verified on JDK 17), while on an `Integer` it produces 32 bits (`ffffffff`). Since `strings.xml` does
not encode the argument's Java type, and since Swift's `Int` is unconditionally 64-bit, **`%llx` is
the only choice that never truncates** — identical reasoning to D3, which makes this a consistency
argument as much as a correctness one.

**Recommendation: `%x`→`%llx`, `%X`→`%llX`, `%o`→`%llo`**, preserving all flags, width and precision
(`%#08x` → `%#08llx`). `#` alternate form was checked and agrees: Java `%#x` prepends `0x`, `%#o`
prepends `0` `[DOC: Formatter]`; C does the same, verified (`printf("%#o", 8)` → `010`, Java
`String.format("%#o", 8)` → `010`).

**Residual divergence, documented not fixed:** for a *negative* value that Android passed as an
`Integer`, Android renders 8 hex digits and iOS will render 16. Unavoidable without type information;
negative hex in a user-facing translated string is not a realistic case.

- Corpus: `placeholders-hex-octal` (semantics change: emit `%ll*`), **new:** `placeholders-hex-flags`

### P5 — `%,d` and `%(d`: hard error, now with the reason — VERIFIED

Confirms the plan. Java's `,` flag inserts *"the locale-specific grouping separator"* and `(` wraps
negatives in parentheses `[DOC: Formatter]`. Neither is a C/Foundation flag. Verified: Java
`String.format(Locale.GERMANY, "%,d", 1234567)` → `1.234.567`; there is no `%,d` in Foundation at all.
Recommend the error message name `NumberFormatter` / `formatted(.number)` as the iOS-side answer.

- Corpus: `error-grouping-flag`

### P6 — `%.Ns` loses its precision — VERIFIED **[NEW]**

Java's precision on `%s` is *"the maximum number of characters to be written"* `[DOC: Formatter]` —
i.e. it truncates. Verified: `String.format("%.3s", "abcdef")` → `abc` (JDK 17), and C `printf("%.3s")`
→ `abc`. But the iOS target is `%@`, and **`%@` does not honor precision** — it is an object
description, not a C string. So `%.3s` → `%@` silently drops the truncation.

**Recommendation: hard error on any precision applied to `%s`.** Width alone (`%10s` → `%10@`) is
**BEST-EFFORT** — not empirically exercised; recommend erroring on that too until verified, since a
misaligned width is a cosmetic bug but a wrong assumption here is invisible.

- Corpus: **new:** `error-string-precision`

### P7 — Specifier-multiset parity across locales — plan confirmed, with target-side justification

The plan requires the specifier multiset of every locale's translation to match the default locale's.
Research **strengthens** this: it is the only place such a check can happen, because **`xcstringstool`
performs essentially no validation**. Verified `[EXP-xcs]` — all of the following compiled **clean**,
with no warning:

| Catalog content | Result |
|---|---|
| `en: "%@ and %lld"` / `de: "nur %@"` (arity mismatch) | compiles clean |
| `en: "%1$@ %2$lld"` / `de: "%1$lld %2$@"` (type swap) | compiles clean |
| `"hi %s there"` (`%s` — invalid on iOS) | compiles clean |
| `""` as a key | compiles clean |

Every one of these is a crash or a garbage-render at runtime. Xcode will not catch them.
**Articulate is the only validation layer that exists.** This is the strongest available evidence for
the project's own thesis and should be quoted in the README.

- Corpus: `error-locale-arity-mismatch`, `error-locale-type-mismatch`

---

## 9. Target format: `.xcstrings` rules

Verified against `xcstringstool` from Xcode 26.6 `[EXP-xcs]`.

### T1 — `"state": "new"` drops the string **only when `extractionState` is absent** — VERIFIED, **CORRECTED 2026-07-30**

> ⚠️ **This rule was wrong in the first revision of this document.** It claimed `state: "new"` causes
> silent deletion *unconditionally*. An independent re-run against the same `xcstringstool` 26.6
> found the behavior is conditional. The original claim was the highest-consequence item in this
> document, which is exactly why it was re-tested. Corrected below.

Compiling one entry per state value and inspecting the emitted `.strings`:

| entry | `state: "new"` | other states (`translated`, `needs_review`, `stale`, `reviewed`, garbage) |
|---|---|---|
| with `"extractionState": "manual"` | **emitted** | all emitted |
| with no `extractionState` key | **silently dropped, no diagnostic** | `translated` / `stale` siblings in the same file survive |

The coherent model: an absent `extractionState` combined with `new` reads as *"Xcode extracted this
string from source and nobody has translated it yet"*, so it is dropped and the key falls back at
runtime. An explicit `manual` means the author owns the entry, so it is respected whatever its state.

**Articulate must emit both `"extractionState": "manual"` and `"state": "translated"`** — which the
settled decisions already require. That places generated catalogs in the protected branch on two
independent counts, and is a concrete argument against ever making either value configurable.

The corpus assertion must check the **conjunction**, not `state` alone: asserting only
`state == "translated"` would still pass if `extractionState` were ever dropped from the output,
which is the configuration that actually loses strings.

- Corpus: **new:** `xcstrings-state-translated` (assert both fields)

### T2 — Plural variations **require a numeric specifier** — VERIFIED **[NEW — the one thing Xcode does validate]**

`xcstringstool compile` emits exactly one hard error in all of the testing above:

```
error: Plural variation requires referencing the number in the string. To maintain grammatical
correctness for strings that do not reference the number of items, use separate top-level strings
for one and greater than one. (en: k)
```

Precise rule, established by isolation `[EXP-xcs]`:

| Catalog | Result |
|---|---|
| `one: "One item"` / `other: "Several items"` | **ERROR** |
| `one: "%lld item"` / `other: "Several items"` | OK — *at least one* variant suffices |
| `one: "%@ item"` / `other: "%@ items"` | **ERROR** — `%@` does not count; must be numeric |
| `one: "%1$@ has %2$lld item"` / … | OK |

**Scope of the check**, pinned down by isolation `[EXP-xcs]`:

| source `en` plural | other locale plural | Result |
|---|---|---|
| conforming | non-conforming | **OK** — other locales are exempt once the source conforms |
| non-conforming | conforming | **ERROR**, reported against `en` |
| absent | non-conforming | **ERROR**, reported against that locale |
| absent | conforming | OK |

So: the check runs against the **source language** when it has a plural for that key, and falls
through to each present locale when it does not. Articulate should enforce the source-language rule
and not bother checking translations, matching Xcode.

**This is a load-bearing conversion rule the plan is missing.** Android `<plurals>` has **no such
requirement** — this is perfectly legal `strings.xml`:

```xml
<plurals name="items">
  <item quantity="one">One item</item>
  <item quantity="other">Several items</item>
</plurals>
```

…and it produces an `.xcstrings` that **will not build**. Articulate must detect it at conversion
time and fail with the actionable message (split into two top-level strings), rather than emitting a
catalog that breaks the user's Xcode build with an error pointing at a generated file.

- Corpus: **new:** `error-plural-no-number-reference`, **new:** `plural-number-in-one-variant-only`

### T3 — Plural compilation confirms D3 — VERIFIED

`xcstringstool compile` derives `NSStringFormatValueTypeKey` **directly from the specifier**
`[EXP-xcs]`:

| catalog value | emitted `NSStringFormatValueTypeKey` |
|---|---|
| `%lld song` | `lld` |
| `%d song` | `d` |

So emitting `%d` in a plural produces a `.stringsdict` that declares a 32-bit value type and reads 32
bits off a 64-bit Swift `Int` at runtime. D3's `%d`→`%lld` rule is not merely advisable for plurals —
it is load-bearing, and this is independent empirical confirmation of a decision made on first
principles.

With multiple arguments the tool also computes the count's argument index into the format key:
`one: "%1$@ has %2$lld item"` → `NSStringLocalizedFormatKey = "%2$#@value@"` `[EXP-xcs]`. This
**requires positional arguments** to be unambiguous — a further independent justification for the
plan's positional-discipline rule.

- Corpus: **new:** `plural-lld-value-type`

### T4 — All six CLDR categories are accepted — VERIFIED

`zero one two few many other` all compile into a `.stringsdict` `[EXP-xcs]`, matching Android's
keyword set exactly `[AOSP:ResourceParser.cpp ResourceParser::ParsePlural]`
`[DOC:https://developer.android.com/guide/topics/resources/string-resource]`. **The plural quantity
mapping is identity.** No `other`-required check is enforced by Xcode (a plural with only `one`
compiled fine `[EXP-xcs]`), so Articulate should enforce it: Android's docs instruct authors to
*"always supply 'one' and 'other' strings"*, and a missing `other` is a runtime fallback failure.

- Corpus: **new:** `plural-all-categories`, **new:** `error-plural-missing-other`

### T5 — `.xcstrings` escaping is plain JSON escaping — VERIFIED

Values are JSON strings. There is no quoting convention, no apostrophe rule, no whitespace collapsing.
Leading/trailing spaces, embedded newlines and tabs round-trip verbatim through
`compile` `[EXP-xcs]`. **The conversion is therefore lossless on the escaping axis**: every value
Android's pipeline can produce is representable. Articulate's escaping job is purely (a) undo Android's
layer, (b) JSON-encode.

- Corpus: `escapes-basic` (assert the `.xcstrings` side), **new:** `xcstrings-json-escaping`

### T6 — `.xcstrings` imposes no key constraints — VERIFIED

Empty keys, `a.b-c`, `class` and keys with spaces all compile `[EXP-xcs]`. All key validation is
Android-side plus symbol-generation-side (K1).

---

## 10. Policy inputs for open decisions

> These are **recommendations with evidence**, not rulings. The project's Decision Brief process
> governs; a human decides. Full argumentation for D4 and the confirm/revise notes for D5–D8 are in
> the research handoff accompanying this document.

### D4 — inline markup & CDATA

Research materially changes the shape of this decision. Three facts:

1. **CDATA is not a markup question at all** (§5). It is not verbatim; it only suppresses XML-layer
   interpretation. It should be **decoupled from D4** and specified as an ordinary parsing rule:
   process CDATA content exactly like text, with tags and entities inert.
2. **`<xliff:g>` is not markup either** (M4). It is placeholder metadata, extremely common, and has a
   clean lossless mapping (unwrap; optionally lift `id`/`example` into the catalog `comment`). It
   must be **carved out of D4 explicitly**, or the policy will reject a large share of real input.
3. **What remains** — `<b>`, `<i>`, `<u>`, `<annotation>`, `<font>`, `<br/>` and friends — is
   genuinely unrepresentable. Verified: `.xcstrings` values are flat JSON strings (T5) with no span
   channel; and on Android itself, the text channel of a styled string is *already* lossy
   (`<br/>` yields no newline, M1). Additionally, spans **corrupt whitespace** (M2) and **disable
   AAPT2's argument-ordering check** (M3).

Recommendation: **hard error in v0 on any no-namespace tag inside `<string>`**, with `<xliff:g>` and
CDATA explicitly exempted, and a `stripMarkup` opt-in deferred to v0.1 at the earliest. Reasoning in
the handoff.

### D5 / D6 / D7 / D8

Locale-mapping evidence gathered for D5 (§11 below) confirms the plan's settled mappings and does not
change the open questions. D6, D7 and D8 are unchanged by this research; see the handoff.

---

## 11. Locale mapping — supporting evidence (lower priority, §3.1)

Verified by compiling one `strings.xml` per qualifier directory and reading back
`aapt2 dump configurations` on the linked APK `[EXP-aapt2]`.

| Directory | AAPT2's normalized config | Note |
|---|---|---|
| `values-de` | `de` | |
| `values-pt-rBR` | `pt-rBR` | |
| `values-b+sr+Latn` | `b+sr+Latn` | BCP-47 form preserved |
| `values-b+es+419` | `es-r419` | **normalized down** to the legacy form |
| `values-b+zh+Hans` / `values-b+zh+Hant` | preserved as-is | script qualifiers survive |
| `values-zh-rCN` / `values-zh-rTW` / `values-zh-rHK` | preserved as-is | the D5 question |
| `values-in` / `values-iw` / `values-ji` | `in` / `iw` / `ji` | **not** remapped by AAPT2 |
| `values-id` / `values-he` / `values-yi` | `id` / `he` / `yi` | **also accepted**, also not remapped |
| `values-fil`, `values-en-rGB`, `values-es-rES` | as written | |
| `values-de-rDE-night` | `de-rDE-night-v8` | non-locale qualifier retained, API level synthesized |

Two findings worth carrying into D5:

- **[NEW] AAPT2 performs no legacy-code remapping in either direction.** Both `values-in` and
  `values-id` are accepted and both survive verbatim. So Articulate's input may use *either* spelling
  and must handle both. The remap direction the plan settled on (`in`→`id`, `iw`→`he`, `ji`→`yi`) is
  correct for BCP-47 output and matches Java's own modern behavior: since **Java SE 17**, *"`iw` maps
  to `he`, `ji` maps to `yi`, and `in` maps to `id`"*, and `Locale.getLanguage()` *"returns the new
  forms for the obsolete ISO 639 codes"*
  `[DOC:https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html]`.
  Confirms the plan's settled mapping with a citation. Note the direction reversed in Java 17 — a
  pre-17 citation would have said the opposite, so cite the 17+ javadoc specifically.
- **[NEW] `values-b+es+419` normalizes to `es-r419`**, i.e. AAPT2 does *not* uniformly preserve
  BCP-47 spelling — it round-trips through its own config representation. Articulate should map from
  the *directory name*, not from an AAPT2-normalized form, and should accept both spellings of any
  locale that has two.

---

## 12. Corpus case index

Cases named in `PLAN.md` §2.2/§2.3, with any semantic change noted:

`escapes-basic` · `error-bare-apostrophe` · `quoted-whitespace` · `whitespace-collapse` ·
`escapes-at-question` · `xml-entities` *(changed: must assert `&quot;` toggling and `&apos;` erroring
— see X2)* · `translatable-false` *(changed: emit `shouldTranslate:false`,
do not drop)* · `error-duplicate-key` · `error-orphan-key` · `error-bad-key` ·
`error-nonpositional-multi` · `error-grouping-flag` · `placeholders-hex-octal` *(changed: emit
`%llx`/`%llo`)* · `error-locale-arity-mismatch` · `error-locale-type-mismatch`

New cases this research requires (49):

**Escapes** `escapes-hash` · `escapes-carriage-return` · `escapes-unknown-passthrough` ·
`escapes-trailing-backslash` · `escapes-trim-order` · `error-bad-unicode-escape` ·
`quoted-with-escapes`

**Quoting / whitespace** `quoted-mid-string` · `error-odd-quote-count` · `quote-reset-at-span` ·
`whitespace-edges-with-span` · `whitespace-edges-with-xliff` · `whitespace-unicode-spaces-preserved` ·
`error-invalid-xml-char-ref`

**CDATA** `cdata-not-verbatim` · `cdata-inert-markup` · `error-cdata-apostrophe`

**Markup / XLIFF** `span-double-space` · `error-styled-nonpositional` · `foreign-namespace-warn` ·
`xliff-g-unwrap` · `xliff-g-whitespace` · `xliff-g-attrs-to-comment` · `error-nested-xliff-g`

**Keys / attributes** `key-dot-dash-warn` · `key-unicode` · `key-reserved-word-ok` ·
`error-duplicate-quantity` · `error-bad-quantity` · `error-unescaped-leading-at` ·
`error-unescaped-leading-question` · `string-literal-lookalikes` · `error-bad-translatable-value` ·
`error-translation-for-untranslatable` · `formatted-false-escapes-percent` · `error-apos-entity`

**Placeholders** `float-passthrough` · `float-rounding-divergence-doc` · `error-percent-g` ·
`placeholders-hex-flags` · `error-string-precision` · `error-arg-reuse` ·
`error-time-format-shortcircuit`

**Target format** `xcstrings-state-translated` · `error-plural-no-number-reference` ·
`plural-number-in-one-variant-only` · `plural-lld-value-type` · `plural-all-categories` ·
`error-plural-missing-other` · `xcstrings-json-escaping`

---

## 13. Reproducing this research

```bash
# aapt2 (AGP 8.7.2)
curl -sSL -o aapt2.jar \
  https://dl.google.com/android/maven2/com/android/tools/build/aapt2/8.7.2-12006047/aapt2-8.7.2-12006047-osx.jar
unzip -o aapt2.jar -d aapt2bin && chmod +x aapt2bin/aapt2

aapt2bin/aapt2 compile --dir res -o out.zip
unzip -q out.zip -d flat
aapt2bin/aapt2 link -o app.apk --manifest AndroidManifest.xml flat/*.flat
aapt2bin/aapt2 dump resources app.apk        # shows text + spans + per-config values
aapt2bin/aapt2 dump configurations app.apk   # shows normalized locale qualifiers

# xcstringstool (Xcode 26.6)
$(xcode-select -p)/usr/bin/xcstringstool compile --output-directory out Localizable.xcstrings
plutil -p out/en.lproj/Localizable.stringsdict
```

**This recipe is the citation.** Any VERIFIED rule in this document can be re-run from here against
the same pinned tool versions; that reproducibility, not the source references, is what makes the
rule checkable. Re-running is cheap and expected — do it whenever a rule looks surprising, and
whenever `aapt2` or Xcode moves.

AOSP sources read from
`https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/tools/aapt2/…` on
2026-07-30, cited by **function name** rather than line number (see §0 for why). To locate one:

```bash
curl -sSL https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/tools/aapt2/ResourceUtils.cpp \
  | grep -n "StringBuilder::AppendText"
```

Functions referenced: `StringBuilder::AppendText`, `StringBuilder::ResetTextState`,
`ResourceParser::FlattenXmlSubtree`, `ResourceParser::ParseXml`, `ResourceParser::ParseString`,
`ResourceParser::ParsePlural`, `ResourceParser::ParseArrayImpl`, `ResourceTable::AddResource`,
`ResourceTable::ResolveValueCollision`, `VerifyJavaStringFormat`, `IsValidResourceEntryName`,
`IsJavaIdentifier`, `XmlPullParser::CharacterDataHandler`,
`XmlPullParser::StartCdataSectionHandler`.

### Verification history

| Date | What | Outcome |
|---|---|---|
| 2026-07-30 | Original research pass (`aapt2` 2.19, `xcstringstool` 26.6, AOSP source) | This document |
| 2026-07-30 | Independent re-run of 11 Android-side rules, byte-checked with `xxd` | All reproduced |
| 2026-07-30 | Independent re-run of 7 target-side rules | 6 reproduced; **`state: "new"` corrected** — the drop is conditional on `extractionState` being absent, not unconditional (see T1) |
| 2026-07-30 | Independent three-way run of the Java-side claims (§8 P2/P3): JDK **17.0.10**, `clang` C, Swift on Xcode 26.6 | All reproduced exactly — see below |

**No claim in this document is single-sourced any more.** The last outstanding item — the Java-side
float comparisons — was closed by running all three languages head to head:

| input | Java 17 | C | Swift |
|---|---|---|---|
| `%g` of `1.0` | `1.00000` | `1` | `1` |
| `%g` of `100.0` | `100.000` | `100` | `100` |
| `%g` of `1e-4` | `0.000100000` | `0.0001` | `0.0001` |
| `%g` of `1e-5` | `1.00000e-05` | `1e-05` | `1e-05` |
| `%g` of `1.5` | `1.50000` | `1.5` | `1.5` |
| `%g` of `0.25` | `0.250000` | `0.25` | `0.25` |
| `%g` of `123456789.0` | `1.23457e+08` | `1.23457e+08` | `1.23457e+08` |
| `%.1f` of `0.25` | `0.3` | `0.2` | `0.2` |
| `%.1f` of `0.35` | `0.4` | `0.3` | `0.3` |
| `%.2f` of `1.005` | `1.01` | `1.00` | `1.00` |
| `%.2f` of `0.125` | `0.13` | `0.12` | `0.12` |

Swift matches C and diverges from Java on **every** row where they differ. Three confirmations:
the `%g` trailing-zero divergence is real (the *threshold* agrees — note `123456789.0` matches
across all three — only trailing zeros differ, exactly as P3 states); Java's `HALF_UP` versus
C/Swift half-to-even is real on all four rounding cases; and the locale divergence is real —
Java `Locale.GERMANY` `%.2f` of `1234.5` → `1234,50`, Swift's `String(format:)` with no locale →
`1234.50` (POSIX), Swift with an explicit `de_DE` locale → `1.234,50`.

The `%g` hard-error rule (P3) is therefore correct and now empirically warranted, not inferred.
