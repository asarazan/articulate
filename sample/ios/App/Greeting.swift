import Foundation

/// PLAN.md §14: a stub, not a SwiftUI app -- demonstrating pitch #3
/// (`Text(.Shared.key)` via generated symbols) needs a real Xcode project,
/// which this sample deliberately does not include. This is the minimum
/// needed to show the other half of the story `AndroidValidationDemo.kt`
/// (in `:androidApp`) shows on Android: on iOS, resolving a string authored
/// once in `:i18n` is a single call that takes the key directly -- no `Int`
/// resource ID, no generated lookup table.
enum Greeting {
    /// `Shared.xcstrings` (this directory) is the committed catalog
    /// Articulate's `generateXcstrings` task produces from the same
    /// `:i18n/src/main/strings` tree `:androidApp` reads via `R.string`.
    static func hello() -> String {
        String(localized: "hello", table: "Shared", bundle: .main)
    }

    /// The Android-edge awkwardness `AndroidValidationDemo.describe` stops
    /// short of resolving (PLAN.md §14, point 5) does not exist on this
    /// side: `EmailValidator.errorKeyFor` (in `:shared`'s commonMain) hands
    /// back a bare key string, and `String(localized:table:)` accepts a key
    /// string directly -- no int resource ID, no generated key -> id table.
    static func errorMessage(forKey key: String) -> String {
        String(localized: String.LocalizationValue(key), table: "Shared", bundle: .main)
    }
}
