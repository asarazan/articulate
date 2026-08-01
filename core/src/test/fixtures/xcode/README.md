# Xcode observed-behavior fixture

> **`RoundTripTest` was deleted 2026-08-01, not forgotten.** It was an
> `@Disabled` placeholder waiting on `opened.xcstrings` and an `XcstringsReader`,
> neither of which was ever built — and neither is now needed. Its claim ("we
> reproduce an Xcode-produced catalog byte-for-byte") was settled more strongly
> by the `xcstringstool sync` round-trip recorded below, which checks every
> `CanonicalFormat` constant against Apple's own tool rather than against one
> checked-in sample. The reader it required was speculative: the drift gate
> compares bytes and never parses a catalog. A permanently disabled test is a
> claim that decays silently, so it is gone rather than left to look like
> pending work.


**Mostly obsolete as of 2026-07-31.** This protocol assumed the only way to
observe Xcode's canonical serialization was a human opening a catalog in the
GUI and saving it. That turned out to be false: **`xcstringstool sync` rewrites
an `.xcstrings` in Apple's own format**, non-interactively, so the ground truth
can be obtained from a script.

## What is now verified (no human needed)

Obtained by round-tripping a deliberately non-canonical catalog through Apple's
own tool and diffing. Recipe at the bottom.

| `CanonicalFormat` value | Verified behavior |
|---|---|
| `INDENT` | two spaces per level |
| `MEMBER_SEPARATOR` | `" : "` — a spaced colon, which no standard pretty-printer emits |
| `MEMBER_ORDER` | alphabetical at every level (top-level, entry members, locales, plural categories) |
| `BLANK_LINE_IN_EMPTY_OBJECT` | `true` — an empty object renders as `{`, a blank line, then the closing brace |
| `TRAILING_NEWLINE` | **`false`** — the file ends at the final `}`, last byte `0x7d` |
| `LOWERCASE_HEX_ESCAPES` | `true` — `\u0001`, lowercase |
| non-ASCII | written as **literal UTF-8**, not escaped (`café`, `日本語`, and U+00A0 all survive verbatim) |
| shorthand escapes | `\t` `\n` `\"` `\\` `\f` all used in preference to `\uXXXX` |
| `CHARSET` / line endings | UTF-8, no BOM, LF only |

`TRAILING_NEWLINE` was provisionally `true` **and was wrong**. That single byte
would have made every generated catalog differ from what Xcode writes, so Xcode
would rewrite on open — defeating the drift gate the whole design rests on. It
is the reason this protocol existed, and it was caught without the GUI step.

## Nothing is still open — closed 2026-08-01

The last question was **what `"version"` a *newly created* Xcode String Catalog
carries**, which `xcstringstool sync` cannot answer because it *preserves*
whatever version the file already has (`1.0` stays `1.0`) rather than
normalizing.

Answered by a human: **Xcode 26.6 writes `"1.2"`** for a new catalog (the Brief
predicted `1.1`, which was already stale). **We deliberately keep `1.0`** — it
expresses everything we emit, and a real Articulate-generated catalog opened,
edited and saved in the Xcode GUI came back with `"version" : "1.0"` untouched,
along with byte-identical indentation, separators, ordering and absent trailing
newline. That round-trip is the proof this whole protocol was built to obtain.

Also observed in that round-trip: editing a source-language string makes Xcode
mark sibling locales `needs_review` — correct behavior, and exactly the drift
`verifyStrings` exists to catch.

## `RoundTripTest`

Still `@Disabled`, now for a different reason than when it was written. Its
blocker is no longer the fixture — it is that `XcstringsReader` does not exist,
because nothing before it needed one. Options, for a human to pick:

1. Write `XcstringsReader` and assert `serialize(parse(x)) == x` over a catalog
   produced by `xcstringstool sync` — the strongest form, and now fully
   available without any GUI step.
2. Retire the test in favour of a direct byte-comparison against a checked-in
   Apple-produced catalog, which needs no reader.

Option 2 proves the same property for less machinery; option 1 additionally
gives `verifyStrings` the structural diff described in `PLAN.md` §5.

## Reproducing the verification

```bash
XT="$(xcode-select -p)/usr/bin/xcstringstool"

# 1. A Swift file referencing a key the catalog lacks, to force a rewrite.
#    (sync no-ops when nothing changes.)
printf 'import Foundation\nlet z = String(localized: "forces_rewrite_key")\n' > S.swift
"$XT" extract --modern-localizable-strings S.swift -o .

# 2. Sync. The .stringsdata table name derives from the catalog filename, so the
#    catalog must be named Localizable.xcstrings for the default table to match.
"$XT" sync Localizable.xcstrings --stringsdata *.stringsdata --skip-marking-strings-stale
```

Write `Localizable.xcstrings` with deliberately *wrong* formatting — compact
colons, four-space indent, keys out of order — so every normalization Apple
applies is visible in the diff.

Per `PLAN.md` §9 flag F3, re-run this on any Xcode upgrade that touches catalog
format. It is now cheap enough to be routine rather than a project milestone.
