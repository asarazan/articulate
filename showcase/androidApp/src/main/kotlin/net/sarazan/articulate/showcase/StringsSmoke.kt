package net.sarazan.articulate.showcase

// Compile-time-only proof that :i18n's generated Android resources are actually
// wired into this module end to end (PLAN.md §15.6 lane A) -- not consumed by
// the UI yet, that's lane B. If either reference below fails to resolve,
// net.sarazan.articulate.android's res.srcDir wiring (or the R class it feeds)
// is broken.
//
// Note: tasks_remaining is a <plurals> resource, so it generates under
// R.plurals, not R.string -- R.string.tasks_remaining does not exist.
private val stringsSmoke = intArrayOf(
    R.string.task_added,
    R.plurals.tasks_remaining,
)
