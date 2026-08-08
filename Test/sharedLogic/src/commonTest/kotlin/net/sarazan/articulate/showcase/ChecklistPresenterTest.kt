package net.sarazan.articulate.showcase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecklistPresenterTest {

    // -- add: success path --------------------------------------------------

    @Test
    fun addAppendsATaskAndUpdatesState() {
        val presenter = ChecklistPresenter()

        presenter.add("Buy milk")

        val state = presenter.state
        assertEquals(1, state.tasks.size)
        assertEquals("Buy milk", state.tasks[0].name)
        assertFalse(state.tasks[0].done)
        assertEquals(1, state.remaining)
        assertEquals("Buy milk", state.lastAdded)
        assertNull(state.lastError)
    }

    @Test
    fun addTrimsWhitespaceBeforeStoringAndComparing() {
        val presenter = ChecklistPresenter()

        presenter.add("  Buy milk  ")

        assertEquals("Buy milk", presenter.state.tasks[0].name)
    }

    @Test
    fun addAssignsDistinctIncreasingIds() {
        val presenter = ChecklistPresenter()

        presenter.add("First")
        presenter.add("Second")

        val ids = presenter.state.tasks.map { it.id }
        assertEquals(2, ids.toSet().size, "ids must be distinct")
        assertTrue(ids[0] < ids[1], "ids must increase")
    }

    // -- add: TaskError.Empty ------------------------------------------------

    @Test
    fun addBlankNameProducesEmptyError() {
        val presenter = ChecklistPresenter()

        presenter.add("")

        val state = presenter.state
        assertTrue(state.tasks.isEmpty())
        assertIs<TaskError.Empty>(state.lastError)
    }

    @Test
    fun addWhitespaceOnlyNameProducesEmptyError() {
        val presenter = ChecklistPresenter()

        presenter.add("   ")

        val state = presenter.state
        assertTrue(state.tasks.isEmpty())
        assertIs<TaskError.Empty>(state.lastError)
    }

    // -- add: TaskError.TooLong, boundary at 40/41 ---------------------------

    @Test
    fun addNameAtExactly40CharsSucceeds() {
        val presenter = ChecklistPresenter()
        val name = "a".repeat(40)

        presenter.add(name)

        val state = presenter.state
        assertEquals(1, state.tasks.size)
        assertEquals(name, state.tasks[0].name)
        assertNull(state.lastError)
    }

    @Test
    fun addNameAt41CharsProducesTooLongError() {
        val presenter = ChecklistPresenter()
        val name = "a".repeat(41)

        presenter.add(name)

        val state = presenter.state
        assertTrue(state.tasks.isEmpty())
        val error = assertIs<TaskError.TooLong>(state.lastError)
        assertEquals(40, error.max)
    }

    // -- add: TaskError.Duplicate ---------------------------------------------

    @Test
    fun addDuplicateNameProducesDuplicateError() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")

        presenter.add("Buy milk")

        val state = presenter.state
        assertEquals(1, state.tasks.size, "the duplicate must not be added")
        val error = assertIs<TaskError.Duplicate>(state.lastError)
        assertEquals("Buy milk", error.name)
    }

    @Test
    fun aFailedAddLeavesLastAddedUntouched() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")

        presenter.add("Buy milk") // duplicate, fails

        assertEquals("Buy milk", presenter.state.lastAdded)
    }

    // -- toggle ---------------------------------------------------------------

    @Test
    fun toggleFlipsDoneAndUpdatesRemaining() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")
        val id = presenter.state.tasks[0].id

        presenter.toggle(id)

        val state = presenter.state
        assertTrue(state.tasks[0].done)
        assertEquals(0, state.remaining)

        presenter.toggle(id)

        assertFalse(presenter.state.tasks[0].done)
        assertEquals(1, presenter.state.remaining)
    }

    @Test
    fun toggleUnknownIdIsANoOp() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")
        val before = presenter.state

        presenter.toggle(999L)

        assertEquals(before, presenter.state)
    }

    // -- delete -----------------------------------------------------------------

    @Test
    fun deleteRemovesTheTaskAndUpdatesRemaining() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")
        presenter.add("Walk dog")
        val id = presenter.state.tasks[0].id

        presenter.delete(id)

        val state = presenter.state
        assertEquals(1, state.tasks.size)
        assertEquals("Walk dog", state.tasks[0].name)
        assertEquals(1, state.remaining)
    }

    @Test
    fun deleteUnknownIdIsANoOp() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")
        val before = presenter.state

        presenter.delete(999L)

        assertEquals(before, presenter.state)
    }

    // -- subscribe / cancel -------------------------------------------------

    @Test
    fun subscribeReplaysCurrentStateImmediately() {
        val presenter = ChecklistPresenter()
        presenter.add("Buy milk")
        val received = mutableListOf<ChecklistState>()

        presenter.subscribe { received += it }

        assertEquals(1, received.size)
        assertEquals(presenter.state, received[0])
    }

    @Test
    fun subscribeReceivesSubsequentChanges() {
        val presenter = ChecklistPresenter()
        val received = mutableListOf<ChecklistState>()
        presenter.subscribe { received += it }

        presenter.add("Buy milk")

        assertEquals(2, received.size, "initial replay + one update")
        assertEquals(1, received.last().tasks.size)
    }

    @Test
    fun cancelStopsFurtherNotifications() {
        val presenter = ChecklistPresenter()
        val received = mutableListOf<ChecklistState>()
        val subscription = presenter.subscribe { received += it }
        val countAfterSubscribe = received.size

        subscription.cancel()
        presenter.add("Buy milk")

        assertEquals(countAfterSubscribe, received.size, "no notification after cancel")
    }

    @Test
    fun multipleSubscribersAreAllNotified() {
        val presenter = ChecklistPresenter()
        val a = mutableListOf<ChecklistState>()
        val b = mutableListOf<ChecklistState>()
        presenter.subscribe { a += it }
        presenter.subscribe { b += it }

        presenter.add("Buy milk")

        assertEquals(2, a.size)
        assertEquals(2, b.size)
    }
}
