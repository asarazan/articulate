# Articulate

Author your app's copy once, as Android `strings.xml`. Get a real Xcode String Catalog out.

Articulate is a Gradle plugin that converts Android string resources into `.xcstrings`, so a
dual-native Android + iOS product can keep one source of truth for user-facing text without either
platform giving up its native localization story. Android reads `R.string` as it always has. iOS
reads a committed `.xcstrings` that Xcode treats as its own. No runtime, no SDK, no lookup layer on
either side.

> **Status: v0, unpublished.** The conversion is complete and heavily verified; the plugin is not on
> the Gradle Plugin Portal yet. See [Maturity](#maturity) for what that means in practice.

## Why this rather than a spreadsheet and a script

The gap between the two formats is small enough to look trivial and large enough to lose data in
silence. `values-zh-rCN` is `zh-Hans`. `%1$s` is `%1$@`, but `%d` is not `%d` — Android's is any
integer width while iOS's is exactly 32-bit, so a value over 2³¹ truncates with no crash and no
warning, in every locale at once. Android collapses runs of whitespace; Xcode does not. Quantity
strings and variations agree on `one`/`other` and disagree past that.

Articulate's rules for all of it come from running `aapt2` and `xcstringstool` and reading what they
actually produced. Vendor documentation has been contradicted by a real tool run **five times** so
far, each one recorded with its evidence in [`docs/CONVERSIONS.md`](docs/CONVERSIONS.md).

Where a conversion would be lossy and no honest answer exists, Articulate **fails the build** rather
than guessing. Inline `<b>` markup, `<string-array>`, `%.2s` precision on a string — all hard errors
naming the file, the key, and the fix. Copy that is 95% right in a language you cannot read is worse
than a build that stopped.

## Install

```kotlin
// i18n/build.gradle.kts — a module of its own, no other plugins needed
plugins {
    id("net.sarazan.articulate") version "0.1.0"
}

articulate {
    sourceLanguage = "en"
    ios {
        catalog = file("../ios/App/Shared.xcstrings")
    }
}
```

Put your strings at `src/main/strings/values*/strings.xml` — the same layout and the same file
format you already use in `app/src/main/res`. Pointing `stringsDir` at an existing `res/` directory
also works and is a legitimate adoption path.

For the Android side, apply the companion plugin to your app module:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("net.sarazan.articulate.android") version "0.1.0"
}

dependencies {
    implementation(project(":i18n"))
}
```

That wires the generated resources into AGP's variant pipeline, so `R.string.your_key` resolves with
no extra step. The Android tree is generated into `build/` and is disposable; the `.xcstrings` is
**committed**, because Xcode expects to find it in your repo.

## Tasks

| Task | Does |
|---|---|
| `generateStrings` | Both of the below |
| `generateXcstrings` | Regenerates the committed `.xcstrings` — commit the result |
| `generateAndroidRes` | Regenerates the disposable Android `values-*/` tree in `build/` |
| `verifyStrings` | Fails if the committed catalog has drifted from source. **Run this in CI** |

`verifyStrings` is the point of the whole design. Because the serializer is byte-deterministic, drift
is detectable by comparison alone — which matters because `.xcstrings` is JSON, JSON has no comment
syntax, and there is therefore nowhere to put a "DO NOT EDIT" header. The gate is the header.

## Configuration

```kotlin
articulate {
    stringsDir = file("src/main/strings")   // default
    sourceLanguage = "en"                   // default
    warningsAsErrors = false                // default
    localeOverrides = mapOf("zh-rSG" to "zh-Hans-SG")
    ios {
        catalog = file("../ios/App/Shared.xcstrings")   // required
        table = "Shared"                                // default
    }
}
```

## Requirements

- Gradle 8.7+ (tested to 9.5)
- AGP 8.5.2+ for the Android plugin (tested to 9.1)
- JDK 17+

Configuration-cache compatible. Kotlin Multiplatform is **not** required — see
[`sample/`](sample/), whose README says exactly where KMP is and isn't load-bearing.

## Shared code and string keys

Articulate generates no Kotlin and no keys object. Shared code that needs to name a string should
return a **sealed type** describing a domain outcome, which each platform maps to its own localized
string at its own edge — native, exhaustiveness-checked, no reflection, no generated lookup table.
[`sample/shared`](sample/shared) demonstrates it and `PLAN.md` §14 records why generating the type
instead would mean inferring domain structure from a flat key namespace.

## Maturity

Honest accounting, since this is a v0:

- **Well covered.** Escaping, placeholders, plurals, locale mapping, byte-canonical serialization,
  and the drift gate. 248 tests, a golden corpus differentially tested against `aapt2` and
  `xcstringstool`, and a discipline that every regression test is proven red before it is accepted
  green.
- **Known gaps.** `markupPolicy` accepts `STRIP`/`VERBATIM` in the DSL but only `ERROR` is
  implemented — setting either fails the build loudly rather than silently misbehaving. The Swift
  key-parity lint (M6) is not built. SwiftPM standalone builds have a real limitation, documented in
  [`docs/swiftpm.md`](docs/swiftpm.md).
- **Not yet published.** Claiming the Plugin Portal namespace is pending, so the coordinates above
  do not resolve yet.

## Documentation

- [`docs/CONVERSIONS.md`](docs/CONVERSIONS.md) — the authoritative conversion spec, rule by rule,
  with the evidence for each. Where it disagrees with `PLAN.md`, it wins.
- [`PLAN.md`](PLAN.md) — the spec of record: milestones, decisions and their reasoning.
- [`docs/swiftpm.md`](docs/swiftpm.md) — what works and what does not under `swift build`.
- [`sample/`](sample/) — a runnable composite build; open it directly in Android Studio.
- [`AGENTS.md`](AGENTS.md) — how to work in this repo, and the rules that exist because they caught
  something real.

## License

Apache 2.0.
