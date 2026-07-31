# Xcode observed-behavior fixture

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
| `LOWERCASE_HEX_ESCAPES` | `true` — ``, lowercase |
| non-ASCII | written as **literal UTF-8**, not escaped (`café`, `日本語`, and U+00A0 all survive verbatim) |
| shorthand escapes | `\t` `\n` `\"` `\\` `\f` all used in preference to `\uXXXX` |
| `CHARSET` / line endings | UTF-8, no BOM, LF only |

`TRAILING_NEWLINE` was provisionally `true` **and was wrong**. That single byte
would have made every generated catalog differ from what Xcode writes, so Xcode
would rewrite on open — defeating the drift gate the whole design rests on. It
is the reason this protocol existed, and it was caught without the GUI step.

## What is still open — one small question

**What `"version"` does a *newly created* Xcode String Catalog carry?**

`xcstringstool sync` **preserves** whatever version the file already has (`1.0`
stays `1.0`, `1.1` stays `1.1`) rather than normalizing, so it cannot answer
this. Xcode 26 is documented to have moved `1.0` → `1.1`, and we currently emit
`1.0`.

To settle it: in Xcode, **File › New › File › String Catalog**, save it, and
report the `"version"` line. That is the entire remaining ask — no hand-writing,
no diffing.

Note this is lower-stakes than it first appeared: since `sync` preserves rather
than rewrites the version, an existing catalog is not churned by tooling. The
risk is confined to what the Xcode GUI does on first open of a file whose
version differs from its native one.

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
