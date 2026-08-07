import Foundation
import SwiftUI
import SharedLogic

/// The showcase "Checklist" screen (PLAN.md §15.1). Every user-visible
/// string is pulled from the "Shared" String Catalog (`Shared.xcstrings`)
/// via `String(localized:table:)` -- including view-local copy the shared
/// presenter never sees (empty state, buttons, placeholders), which is the
/// live anti-Compose-Resources argument the showcase exists to make.
///
/// `SharedLogic.Task` is spelled out with its module prefix everywhere
/// because the bridged Kotlin type collides by name with Swift's own
/// `Task` (structured concurrency); this file never uses the concurrency
/// `Task` type, so `DispatchQueue` is used for the toast auto-dismiss
/// instead of `Task.sleep`, sidestepping the ambiguity entirely.
struct ContentView: View {
    @State private var store = ChecklistStore()
    @State private var newTaskName: String = ""
    @State private var pendingDelete: SharedLogic.Task?
    @State private var toastText: String?
    @State private var toastWorkItem: DispatchWorkItem?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header

                if store.state.tasks.isEmpty {
                    Spacer()
                } else {
                    taskList
                }

                if let lastError = store.state.lastError {
                    errorBanner(for: lastError)
                }

                addBar

                Text(String(localized: "brand_tagline", table: "Shared"))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .padding(.bottom, 8)
            }
            .navigationTitle(String(localized: "screen_title", table: "Shared"))
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .top) {
                if let toastText {
                    Text(toastText)
                        .font(.subheadline)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .allowsHitTesting(false)
                }
            }
            .animation(.default, value: toastText)
            .confirmationDialog(
                deleteConfirmText,
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { isPresented in
                        if !isPresented { pendingDelete = nil }
                    }
                ),
                titleVisibility: .visible
            ) {
                Button(String(localized: "delete_yes", table: "Shared"), role: .destructive) {
                    if let task = pendingDelete {
                        store.delete(id: task.id)
                    }
                    pendingDelete = nil
                }
                Button(String(localized: "delete_no", table: "Shared"), role: .cancel) {
                    pendingDelete = nil
                }
            }
        }
        .onChange(of: store.state.lastAdded) { _, newValue in
            guard let newValue else { return }
            showToast(String(format: String(localized: "task_added", table: "Shared"), newValue))
        }
    }

    // MARK: - Header

    /// `tasks_remaining`'s catalog comment is explicit: the count is always
    /// greater than zero when shown; when it would be zero (no tasks, or
    /// every task done), the empty-state copy is shown in its place instead
    /// of "0 tasks remaining".
    private var header: some View {
        Group {
            if store.state.remaining > 0 {
                Text(remainingText(count: store.state.remaining))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.top, 12)
                    .padding(.bottom, 4)
            } else {
                VStack(spacing: 4) {
                    Text(String(localized: "empty_state_title", table: "Shared"))
                        .font(.headline)
                    Text(String(localized: "empty_state_body", table: "Shared"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 32)
                .padding(.horizontal)
            }
        }
    }

    /// Builds the "%1$lld task(s) remaining" text via the API that actually
    /// exercises the catalog's plural variations -- see the build report for
    /// how variation selection was verified against the compiled catalog.
    private func remainingText(count: Int32) -> String {
        let format = String(localized: "tasks_remaining", table: "Shared")
        return String.localizedStringWithFormat(format, Int(count))
    }

    // MARK: - Task list

    private var taskList: some View {
        List {
            ForEach(store.state.tasks, id: \.id) { (task: SharedLogic.Task) in
                HStack(spacing: 12) {
                    Button {
                        store.toggle(id: task.id)
                    } label: {
                        Image(systemName: task.done ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(task.done ? .green : .secondary)
                            .imageScale(.large)
                    }
                    .buttonStyle(.plain)

                    Text(task.name)
                        .strikethrough(task.done)
                        .foregroundStyle(task.done ? .secondary : .primary)

                    Spacer()
                }
                .contentShape(Rectangle())
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        pendingDelete = task
                    } label: {
                        Label(String(localized: "delete_yes", table: "Shared"), systemImage: "trash")
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    // MARK: - Add bar

    private var addBar: some View {
        HStack(spacing: 8) {
            TextField(String(localized: "add_placeholder", table: "Shared"), text: $newTaskName)
                .textFieldStyle(.roundedBorder)
                .onSubmit(submitAdd)

            Button(String(localized: "add_button", table: "Shared"), action: submitAdd)
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }

    private func submitAdd() {
        let name = newTaskName
        store.add(name: name)
        // The presenter call above is synchronous (single-threaded, no
        // coroutines/Flow at this boundary per PLAN.md §15.3), so `store
        // .state` already reflects this exact attempt's outcome here.
        if store.state.lastError == nil {
            newTaskName = ""
        }
    }

    // MARK: - Error display

    /// Exhaustive-by-hand mapping over the bridged `TaskError` -- see
    /// TaskErrorLocalization.swift for the honest limitation this records.
    private func errorBanner(for error: TaskError) -> some View {
        Text(localizedMessage(for: error))
            .font(.footnote)
            .foregroundStyle(.red)
            .padding(.horizontal)
            .padding(.bottom, 4)
            .multilineTextAlignment(.center)
    }

    // MARK: - Delete confirmation

    private var deleteConfirmText: String {
        guard let pendingDelete else { return "" }
        let format = String(localized: "delete_confirm", table: "Shared")
        return String(format: format, pendingDelete.name)
    }

    // MARK: - Toast

    private func showToast(_ text: String) {
        toastWorkItem?.cancel()
        toastText = text
        let workItem = DispatchWorkItem {
            toastText = nil
        }
        toastWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: workItem)
    }
}

#Preview {
    ContentView()
}
