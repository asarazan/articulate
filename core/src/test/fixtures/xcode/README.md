# Xcode observed-behavior fixture

Pending input from the hub pre-flight checklist — not this repo's decision to
make. Nothing in milestones 1–3 blocks on this arriving.

## What to produce

1. **`handwritten.xcstrings`** — a minimal catalog you write by hand: one plain
   string, one plural (at least `one`/`other`), one entry with a comment, two
   locales. It doesn't need to be elaborate; it exists to be diffed against
   itself after Xcode touches it.
2. **`opened.xcstrings`** — the exact same file, opened in Xcode and saved
   (Cmd+S), nothing else changed. This is the ground truth: whatever changed
   between `handwritten` and `opened` is Xcode's own normalization, and our
   serializer must reproduce it so opening our generated file is a no-op.
3. **`xcode-version.txt`** — one line, e.g. `Xcode 26.0 (17A123)` (Xcode ›
   About Xcode).

## What happens once these land

1. `RoundTripTest` (`core/src/test/kotlin/.../serialize/RoundTripTest.kt`,
   currently `@Disabled`) gets un-disabled and implemented: parse
   `opened.xcstrings`, re-serialize it, assert the result is byte-identical to
   `opened.xcstrings`. This also means writing `XcstringsReader`, which does
   not exist yet — building it against guessed format details before this
   test can run it would be exactly the kind of unverified assumption this
   project is structured to avoid.
2. Diff `handwritten.xcstrings` against `opened.xcstrings` by hand; every
   difference becomes a `CanonicalFormat` value or a new formatting rule.
   Known unknowns already flagged as PROVISIONAL in `CanonicalFormat.kt`:
   `VERSION` (highest priority — `1.0` vs `1.1`), indent width, the `" : "`
   member separator, the empty-object blank-line convention, and hex-escape
   letter case.
3. Once `CanonicalFormat` is corrected, regenerate the milestone-2 golden
   corpus (`regenerateCorpus` task, once it exists) and review the diff.
4. Transcribe the final normalization rules into this README or into
   `CanonicalFormat`'s KDoc, so the spec of record lives in one place.

Per `PLAN.md` §9 flag F3, this protocol should be re-run on every Xcode
upgrade that touches catalog format — treat it as recurring maintenance, not
a one-time event.
