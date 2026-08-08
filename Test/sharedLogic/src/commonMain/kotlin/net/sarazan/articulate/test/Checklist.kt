package net.sarazan.articulate.test

/**
 * The FROZEN shared contract (PLAN.md §15.3). No coroutines/Flow in the
 * Swift-facing API; [ChecklistPresenter] returns domain outcomes only --
 * never a string, never a resource key (PLAN.md §14) -- so both androidApp
 * (Jetpack Compose, exhaustive `when`) and the SwiftUI app (exhaustive
 * `switch`/if-chain) resolve copy from :i18n / the .xcstrings catalog at
 * their own edge, never from shared code.
 */
sealed interface TaskError {
    data object Empty : TaskError
    data class TooLong(val max: Int) : TaskError
    data class Duplicate(val name: String) : TaskError
}

data class Task(val id: Long, val name: String, val done: Boolean)

data class ChecklistState(
    val tasks: List<Task>,
    val remaining: Int,
    val lastAdded: String?,
    val lastError: TaskError?,
)

/** Cancel handle returned by [ChecklistPresenter.subscribe]. */
interface Subscription {
    fun cancel()
}

/**
 * Hand-rolled callback bridge (PLAN.md §15.0: no SKIE, no DI, no nav libs --
 * the foreground must stay localization, not KMP plumbing). Single-threaded;
 * the showcase never touches this from more than one thread, so no
 * synchronization is attempted here.
 *
 * Design decisions not pinned by §15.3's contract, recorded here since the
 * frozen block only gives signatures:
 * - `add`'s name is trimmed before validation/storage; whitespace-only names
 *   count as [TaskError.Empty].
 * - Duplicate detection compares the trimmed name against existing task
 *   names, case-sensitively.
 * - A failed `add` sets `lastError` and leaves `lastAdded` untouched (nothing
 *   new was added). A successful `add` sets `lastAdded` and clears
 *   `lastError`. `toggle`/`delete` never touch either -- they are not add
 *   attempts and have no outcome to report through this pair.
 * - `subscribe` replays the current snapshot to the new listener immediately
 *   (so a SwiftUI observer attached after the fact isn't left blank), then
 *   again on every subsequent change.
 * - `toggle`/`delete` on an unknown id are no-ops -- §15.3 gives no error
 *   variant for "not found", so silently ignoring is the only outcome the
 *   contract can express.
 */
class ChecklistPresenter {

    private val tasks = mutableListOf<Task>()
    private var nextId = 1L
    private var lastAdded: String? = null
    private var lastError: TaskError? = null
    private val listeners = mutableListOf<(ChecklistState) -> Unit>()

    val state: ChecklistState
        get() = ChecklistState(
            tasks = tasks.toList(),
            remaining = tasks.count { !it.done },
            lastAdded = lastAdded,
            lastError = lastError,
        )

    fun subscribe(onChange: (ChecklistState) -> Unit): Subscription {
        listeners += onChange
        onChange(state)
        return object : Subscription {
            override fun cancel() {
                listeners -= onChange
            }
        }
    }

    fun add(name: String) {
        val trimmed = name.trim()
        val error: TaskError? = when {
            trimmed.isEmpty() -> TaskError.Empty
            trimmed.length > MAX_NAME_LENGTH -> TaskError.TooLong(MAX_NAME_LENGTH)
            tasks.any { it.name == trimmed } -> TaskError.Duplicate(trimmed)
            else -> null
        }
        if (error != null) {
            lastError = error
            notifyListeners()
            return
        }
        tasks += Task(id = nextId++, name = trimmed, done = false)
        lastAdded = trimmed
        lastError = null
        notifyListeners()
    }

    fun toggle(id: Long) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val task = tasks[index]
        tasks[index] = task.copy(done = !task.done)
        notifyListeners()
    }

    fun delete(id: Long) {
        val removed = tasks.removeAll { it.id == id }
        if (!removed) return
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = state
        listeners.forEach { it(snapshot) }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
