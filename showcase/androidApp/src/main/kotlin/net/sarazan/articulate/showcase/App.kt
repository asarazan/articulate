package net.sarazan.articulate.showcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// Plain androidx Jetpack Compose Checklist UI (PLAN.md §15.0/§15.1/§15.4). Folded in from
// the wizard's former shared-UI module (a multiplatform Compose wrapper consumed only by
// Android) -- the value prop is to be as close to platform native as possible, and that
// module undermined it for a demo whose UI was never actually shared. Every user-visible
// string below is resolved via stringResource/pluralStringResource against :i18n's
// generated R class; that's the entire demo.
@Composable
// @Preview REMOVED 2026-08-07: Studio AI-261's Compose Preview finder crashes
// on this file with "Invalid PSI Element ... different providers" thrown from
// inside KaBaseSessionProvider.beforeEnteringAnalysis -- which poisons K2
// analysis for the ENTIRE project (every module red, stdlib included) on
// every sync. Full stack in the session log / upstream report. Re-add when
// Studio fixes the preview scanner; the app itself is unaffected.
fun App() {
    MaterialTheme {
        val presenter = remember { ChecklistPresenter() }
        val checklistState by presenter.observeAsState()
        ChecklistScreen(
            state = checklistState,
            onAdd = presenter::add,
            onToggle = presenter::toggle,
            onDelete = presenter::delete,
        )
    }
}

/**
 * Bridges [ChecklistPresenter.subscribe] into Compose state honestly and minimally
 * (PLAN.md §15.0: no ViewModel/DI/nav libraries -- a remembered presenter plus a
 * DisposableEffect is the whole bridge). Subscribes when this enters composition,
 * cancels the subscription when it leaves.
 */
@Composable
private fun ChecklistPresenter.observeAsState(): State<ChecklistState> {
    val state = remember(this) { mutableStateOf(this.state) }
    DisposableEffect(this) {
        val subscription = subscribe { state.value = it }
        onDispose { subscription.cancel() }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistScreen(
    state: ChecklistState,
    onAdd: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var inputText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Task?>(null) }

    // Resolved here (composable scope, stringResource is @Composable) then captured by
    // value in the LaunchedEffect below, which runs outside composition.
    val addedMessage = state.lastAdded?.let { stringResource(R.string.task_added, it) }
    val errorMessage = state.lastError?.let { resolveError(it) }

    LaunchedEffect(state.lastAdded) {
        if (addedMessage != null) {
            inputText = ""
            snackbarHostState.showSnackbar(addedMessage)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().safeContentPadding(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = pluralStringResource(R.plurals.tasks_remaining, state.remaining, state.remaining),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.add_placeholder)) },
                    singleLine = true,
                )
                Button(
                    onClick = { onAdd(inputText) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.add_button))
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (state.tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty_state_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(text = stringResource(R.string.empty_state_body))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { onToggle(task.id) },
                            onDeleteRequest = { pendingDelete = task },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm, task.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(task.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.delete_no))
                }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onToggle: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Text(
            text = task.name,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
        )
        // Reuses the frozen delete_yes string ("Delete") rather than inventing a new
        // content-description key -- the i18n inventory is frozen (PLAN.md §15.2).
        TextButton(onClick = onDeleteRequest) {
            Text(stringResource(R.string.delete_yes))
        }
    }
}

/**
 * Resolves a [TaskError] to its display string. Exhaustive `when` with no `else` branch --
 * this is §14's sealed-type MVP pattern, live: adding a new TaskError case to
 * :sharedLogic's Checklist.kt will fail this `when` to compile, forcing this call site
 * (and the SwiftUI edge's equivalent) to handle it explicitly rather than silently
 * falling through.
 */
@Composable
private fun resolveError(error: TaskError): String = when (error) {
    is TaskError.Empty -> stringResource(R.string.error_task_empty)
    is TaskError.TooLong -> stringResource(R.string.error_task_too_long, error.max)
    is TaskError.Duplicate -> stringResource(R.string.error_task_duplicate, error.name)
}
