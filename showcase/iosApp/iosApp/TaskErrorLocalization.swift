import Foundation
import SharedLogic

/// Resolves a bridged `TaskError` (PLAN.md §15.3's sealed contract) to
/// user-facing copy, entirely at this edge -- the presenter never sees a
/// string (PLAN.md §14).
///
/// HONEST LIMITATION: Kotlin sealed classes/interfaces exported to
/// Objective-C/Swift become a plain protocol (`TaskError`) implemented by
/// unrelated classes (`TaskErrorEmpty`, `TaskErrorTooLong`,
/// `TaskErrorDuplicate`) -- there is no Swift `enum` and therefore no
/// compiler-enforced exhaustiveness the way a Kotlin `when` gets on the
/// Kotlin side. Tools like SKIE can synthesize a real Swift enum with
/// exhaustive `switch` support, but PLAN.md §15.0 forbids adding such
/// tooling here (no SKIE, no DI, no nav libs). So this is an if/else chain,
/// exhaustive **by hand** against the three cases that exist today, with a
/// `fatalError` default: if a fourth `TaskError` case is ever added on the
/// Kotlin side, this file will not fail to compile -- it will fail at
/// runtime, loudly, the first time that case is produced, naming the
/// offending type so the gap is obvious rather than silently mis-copied.
func localizedMessage(for error: TaskError) -> String {
    if error is TaskErrorEmpty {
        return String(localized: "error_task_empty", table: "Shared")
    } else if let tooLong = error as? TaskErrorTooLong {
        let format = String(localized: "error_task_too_long", table: "Shared")
        return String(format: format, Int(tooLong.max))
    } else if let duplicate = error as? TaskErrorDuplicate {
        let format = String(localized: "error_task_duplicate", table: "Shared")
        return String(format: format, duplicate.name)
    } else {
        fatalError(
            "Unhandled SharedLogic.TaskError case: \(type(of: error)). " +
            "TaskErrorLocalization.swift's if/else chain is hand-maintained " +
            "(no exhaustiveness checking across the Kotlin/Swift bridge) and " +
            "was not updated for this new case."
        )
    }
}
