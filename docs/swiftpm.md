# Articulate and Swift Package Manager

**Short version: if your iOS app is an Xcode project, this page does not affect you. If you consume
the catalog from a Swift package built with `swift build`, read on — the failure is silent.**

D8 (`PLAN.md` §8) ruled that SwiftPM support is docs-only for v0. This page is that deliverable, and
it **corrects the ruling's own table** in one row, because the ruling was reasoned and this page was
tested.

## What was actually observed

Verified 2026-08-01 on macOS 26.0, Swift 6.3.3, Xcode 26.6, with a minimal package: one target, one
`Shared.xcstrings` (`en` + `de`) declared via `.process("Resources")`, and
`String(localized: "hello", table: "Shared", bundle: .module)`.

| | Result |
|---|---|
| `swift build` succeeds | **Yes** — no error, no warning |
| Catalog reaches the bundle | **Yes**, as a raw `Shared.xcstrings` |
| Catalog is *compiled* | **No** — no `.lproj`, no `.strings`, no `.stringsdict` |
| Runtime lookup resolves | **No** — returns the key, `hello`, not `Hello` |

The control that makes this a finding rather than a broken test harness: pre-compiling the same
catalog with `xcstringstool` and leaving the `.lproj` output beside it in `Resources/` changed the
result to `Hello` on the very next run, with nothing else altered. The lookup path works. The
catalog is what never gets processed.

```
Demo_Demo.bundle/Shared.xcstrings          <- swift build:  LOOKUP RESULT: [hello]
Demo_Demo.bundle/en.lproj/Shared.strings   <- plus these:   LOOKUP RESULT: [Hello]
Demo_Demo.bundle/de.lproj/Shared.strings
```

## The correction to D8

D8's table says runtime lookup of a bundled catalog is *"probably fine — not verified end to end."*
**It is not fine, and the diagnosis was also wrong in a way worth recording.**

D8 attributed the breakage to `xcstringstool` living in `Xcode.app/Contents/Developer/usr/bin/`
rather than the Swift toolchain — true (verified: it is not on `PATH` and not in the toolchain
directory; only `xcrun --find` locates it). But that is not the operative cause. **This test ran on a
machine with Xcode installed and `xcstringstool` present and working**, and `swift build` still did
not compile the catalog. SwiftPM's resource pipeline simply has no rule for `.xcstrings`; it treats
the file as an opaque resource and copies it. Making the tool reachable would not fix it.

The practical difference matters: "unreachable tool" implies the problem disappears on a developer's
Xcode-equipped Mac. It does not.

**This failure is silent, which puts it in the class this project otherwise refuses.** The build is
green, the resource ships, and the app displays raw keys — in every locale at once, including the
source language, where `hello` looks like a typo rather than a missing translation.

## What to do about it

**If your iOS app is an Xcode project** (the overwhelmingly common dual-native shape, with packages
as libraries inside it), nothing here applies to the app build: Xcode drives catalog handling
itself. *This remains the one claim on this page not verified end to end* — testing it needs a real
Xcode project build, which nothing in this repo does. Treat it as the most likely thing to be wrong
if someone reports trouble.

**If you build a package standalone** — `swift build`, `swift test`, package CI — you have three
options:

1. **Keep the catalog out of the package.** Let the app target own `Shared.xcstrings` and have the
   package take strings as parameters. Cleanest, and it matches the sealed-type advice in the README:
   a package returning domain outcomes rather than copy has nothing to localize.
2. **Commit the compiled output alongside the catalog.** Run
   `xcstringstool compile --output-directory Sources/<Target>/Resources Sources/<Target>/Resources/Shared.xcstrings`
   and commit the resulting `*.lproj/*.strings`. Verified to work above. The cost is a second
   generated artifact in version control that nothing currently checks for drift — Articulate's
   `verifyStrings` gate covers the `.xcstrings`, not this.
3. **Accept it in test-only contexts**, where a key-shaped string is merely ugly rather than shipped.

## What Articulate does not do here

Articulate does not generate `.strings`/`.stringsdict`, and does not invoke `xcstringstool` as part
of the build. Its output is the `.xcstrings` catalog. Option 2 above is a manual step you own, not a
task the plugin provides — that would mean depending on an Xcode-only binary from a Gradle build,
which would break every Linux CI that runs `generateStrings` today.

## If you reopen D8

The row that changed is in the table above. What is still unverified:

- Xcode-driven app builds that depend on a local package (the assumption in *What to do about it*).
- Whether SwiftPM on **Linux** behaves the same way. Only macOS was tested. There is no reason to
  expect it compiles catalogs there when it does not here, but that is an inference.
- Whether a future SwiftPM adds an `.xcstrings` rule, which would make this page obsolete rather
  than wrong.
