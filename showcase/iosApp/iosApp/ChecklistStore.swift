import Foundation
import Observation
import SharedLogic

/// Hand-rolled bridge from `ChecklistPresenter`'s callback-based
/// `subscribe(onChange:)` to SwiftUI's observation system (PLAN.md §15.0:
/// no SKIE, no DI, no nav libs -- the foreground stays localization, not KMP
/// plumbing). `@Observable` (not `ObservableObject`/`@Published`) so views
/// that only read part of `state` aren't invalidated by unrelated changes.
///
/// The presenter is single-threaded per its own doc comment, and this store
/// is only ever touched from the main actor (SwiftUI view bodies and their
/// action closures), so no additional synchronization is added here.
@Observable
final class ChecklistStore {
    private(set) var state: ChecklistState
    private let presenter: ChecklistPresenter
    private var subscription: Subscription?

    init(presenter: ChecklistPresenter = ChecklistPresenter()) {
        self.presenter = presenter
        self.state = presenter.state
        self.subscription = presenter.subscribe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        subscription?.cancel()
    }

    func add(name: String) {
        presenter.add(name: name)
    }

    func toggle(id: Int64) {
        presenter.toggle(id: id)
    }

    func delete(id: Int64) {
        presenter.delete(id: id)
    }
}
