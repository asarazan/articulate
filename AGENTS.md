# Working in this repository

Articulate converts Android `strings.xml` into Xcode String Catalogs. `core` is pure Kotlin (zero Gradle API); `plugin` is a thin Gradle shell over it.

**`PLAN.md` is the spec of record. `docs/CONVERSIONS.md` is the authoritative conversion spec** — where they disagree, `CONVERSIONS.md` wins, and it says so itself. Read the relevant section before implementing; do not re-derive decisions already ruled there.

## Commands

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ARTICULATE_REQUIRE_ANDROID_SDK=true   # what CI sets; makes a missing SDK fail rather than skip
./gradlew test
```

Gradle writes to `~/.gradle`, so it needs the sandbox disabled. Android and Gradle-floor tests are slow — that is expected, not a hang.

## If you are an agent working in this repo, this applies to you

**Work in your own tree, never the human's.** A worktree or a container. That is the primary defense; everything below is a backstop for when it fails.

**Read git freely — you are expected to.** `log`, `blame`, `show`, `diff`, `status` destroy nothing, and this repo's commit messages are load-bearing evidence (see "Merges preserve commit messages" below). A DECIDED claim in `PLAN.md` records what was *believed*; the commit that implemented it records what was *tested*. Consult the history before building on either.

**Never run these, in any tree:**

- **`checkout` / `restore` / `reset` / `clean` / `stash`.** Every recorded near-miss in this repo used one of these — three agents have now breached the old rule, all harmless by luck, which is precisely why the harmful and harmless cases cannot be told apart while you are typing them. They destroy uncommitted work *silently*: nothing tells you what was lost. To revert a mutation test, `cp` the file to a backup first and `cp` it back.
- **`git add -A` / `add .`.** Stage explicit paths only — see "read what you staged" below.
- **Any force-push, and any push to `main`.**

**You may commit and push your own `feature/<slug>` branch and open a PR.** A human reviews and merges. Opening a PR is not certifying yourself into history — merging is, and `main`'s protection plus the required `CI` gate is what prevents that. An automated pass must never be the reason work is lost.

**Whoever does commit: read what you staged, every time.** `git add -A` after a build sweeps up whatever the build dropped. A 373MB JVM heap dump reached a commit that way and was stopped only by GitHub's file-size hook — not by anything in this repo. List the staged paths and size-check anything binary *before* committing, not after a rejected push. This applies to the orchestrator too; it is easy to enforce on agents and forget for yourself.

## The rules that have actually caught bugs here

Each of these exists because it caught something real. They are not style preferences.

**Prefer an oracle to an argument.** Real `aapt2` (`$ANDROID_HOME/build-tools/36.0.0/aapt2`) and `xcstringstool` (`$(xcode-select -p)/usr/bin/xcstringstool`) are the reference implementations. When behavior is unclear, *compile the input and observe* rather than reasoning from documentation. This is the single biggest quality lever in the project — it turns "did I implement this right?" from judgment into experiment.

**Check the oracle exists before trusting a run, and never point it at a temp directory.** This line pointed `aapt2` at `/private/tmp/claude-501/aapt2bin/aapt2` until 2026-08-09, when it was found **absent** — a sandbox temp path macOS wipes, so the instruction was guaranteed to rot. An oracle that silently is not there is worse than none: it routes you straight back to reasoning from documentation, the exact failure this rule exists to prevent. If the binary is missing, say so and stop; do not substitute judgment. Two further constraints on where it can be consulted: `aapt2` ships with the SDK build-tools and works on Linux, but `xcstringstool` lives inside `Xcode.app` and is **unreachable from any Linux container** — a task needing it is macOS-only by construction. Note also that the evidence in `docs/CONVERSIONS.md` was gathered against **aapt2 2.19** and the binary above is **2.20**, so a fresh disagreement may be a genuine vendor change rather than a defect here.

**Observation beats documentation — and that includes *our own* documentation.** Vendor docs have been contradicted by a real tool run **six times** here: Android's whitespace-collapsing rule, `addGeneratedSourceDirectory`'s output ownership, that ownership being AGP-version-dependent, `xcstringstool`'s `state: "new"` behavior, `<item type="string">` being a real string resource we silently dropped, and SwiftPM shipping an `.xcstrings` uncompiled so lookups return the key. Treat "the docs say X" as a hypothesis. **Twice now a settled decision in `PLAN.md` was corrected by the first person to actually run it** — writing D8's deliverable disproved D8's own table. A decision marked DECIDED records what was believed, not what was tested; check the evidence tag before building on it.

**Hexdump anything involving whitespace or non-ASCII.** Terminal rendering hides the difference between one space and three, and between U+0020 and U+2008. Both distinctions are load-bearing in this spec.

**Write no test that cannot fail.** This repo has shipped assertions satisfied by their own fixture name (`no_comment`), by their own directory path (`error-string-precision`), by a no-op input (a Turkish-locale test using an already-lowercase string), and by a single-item case where "lists all" and "lists the first" are indistinguishable. When a new test passes on the first run, **prove it can fail**: mutate the production code, watch it break, revert. `CorpusTest` has a guard against the path-substring class — if it fires, the assertion is too weak; do not work around it.

**Ask what shape of bug your harness cannot express.** The most expensive defect in this project — a release-blocking classloader failure in the most common consumer layout — was invisible to 236 passing tests, because every functional test used `GradleRunner.withPluginClasspath()`, which injects one classpath for the whole fixture. The failing shape was *structurally unreachable* through the test infrastructure. Comprehensive coverage of a configuration no user has is worth nothing. Periodically ask: what does my harness make impossible to observe, and does a real user live there?

The same shape recurred within a day: `sample/` shipped with no Gradle wrapper, so it could not be opened in an IDE or built by hand — invisible because the test drives it through TestKit, which needs neither. **Where automation and a human take different paths to the same artifact, only the automation path is under test.** Walk the human path yourself at least once.

Then a third time, same week: a module applying only `net.sarazan.articulate` registered no source sets, so Android Studio's default view showed `:i18n` as an empty module — while every build passed, because a build reads the disk and an IDE reads the Gradle model. **Three for three, the defect sat in the gap between what the harness drives and what a human touches.** When you cannot test the last mile yourself, say so and hand that step to the human explicitly — do not let a green suite imply coverage of a surface it never reached.

**Prove a regression test red before accepting it green.** For any test written to pin a specific bug: back up the fix, confirm the test fails without it, restore, confirm it passes. A test that passes both ways proves nothing, and it is indistinguishable from a real one once committed.

**Do not explain a failure you have not diagnosed.** A first sample build failed and was described as "transient resolution failures, re-run it" — the 373MB heap dump it left behind said `OutOfMemoryError`. The evidence was on disk and the explanation was invented to move past it. Wrong diagnoses are worse than "I don't know yet": this one would have shipped a sample whose first impression is a crash, documented as normal. Read the artifacts a failure leaves — dumps, reports, exit codes — before narrating a cause.

**A green build that skipped its hardest tests is not green.** The entire golden corpus was once invisible to Gradle's up-to-date checking, so editing a fixture left `:core:test` UP-TO-DATE and the change never ran. Silence is not success.

**Never hand-type byte-exact expected output.** Generate fixtures from verified program output, inspect with `xxd`/`cat -e`, then delete the generator. Hand-transcribing nested JSON or control characters is how expectation bugs get committed.

**Control bytes need binary-safe tooling.** The Edit tool normalizes control characters passed through its interface. Use `python3` with byte-level `replace`, assert the match count, verify with a hexdump. A raw `0x0C` form feed once shipped in an escape table, invisible in review.

**Rebuild and re-run the full suite after every fix, not after batching several.** A fix that breaks a neighbour must be caught while the cause is unambiguous.

**When the IDE misbehaves, read `idea.log` before touching anything.** Studio is not un-inspectable: it logs to `~/Library/Logs/Google/AndroidStudio*/idea.log`, and "read the artifacts a failure leaves" applies to IDEs exactly as to builds. Six remedies were once run against an editor whose log had, from the first minute, a full stack naming the crashing component (the Compose Preview scanner throwing inside the K2 analysis session — one `@Preview` annotation poisoning analysis for every module in the project). Grep for `SEVERE`, then extract the **whole stack block above it** — the summary lines alone name the victim, not the culprit. Theories are what you write *after* the stack trace.

**Mark verification status in specs.** VERIFIED / BEST-EFFORT / PROVISIONAL, with the evidence. Making an unknown *visible* is what got `TRAILING_NEWLINE` checked — it was provisionally wrong, and one byte would have defeated the whole drift gate.

## Branching and worktrees (adopted 2026-08-06)

Trunk is `main`, **protected**: nothing lands except through a PR with the `CI` gate green. Feature work happens on `feature/<slug>` branches, **one worktree per feature** so several can be in flight:

```bash
git worktree add ../articulate-wt/<slug> -b feature/<slug>
```

- **Merges preserve commit messages** — merge commit or rebase-merge, **never squash**. Commit messages in this repo are load-bearing records (they carry the evidence trail specs cite); squashing destroys them.
- **Give every agent its own tree** — a worktree locally, or a container. Agents commit their own `feature/<slug>` branch and open the PR themselves; the orchestrator no longer commits on their behalf. The denylist above still applies everywhere.
- **Cloud agents in disposable containers need nothing beyond that denylist** — nothing on that disk exists anywhere else, and the worst case is discarding the container. State the branch-and-PR expectation in the launching prompt rather than expecting an agent to work out where it is running; an agent that was told nothing should behave as if it is in your tree.
- Delete the worktree and branch after merge; a stale worktree is uncommitted-work risk.
- **While any agent owns a working tree, the human's IDE is a consumer of that tree**: `includeBuild` means a Studio sync compiles whatever half-written state exists at that moment. Announce hot trees before inviting a sync; prefer giving agents their own worktree for anything long-running.

## Delegating work

**The auditor must never be the implementer.** Separation matters more than model tier — a producer cannot see the shape of what it failed to write. Every high-value finding in this project has been an **absence**: a missing input declaration, twelve untested specifier families, an assertion that asserts nothing. Audit for negative space, not for errors.

**Route by whether a mistake fails loudly.** Implementing against a spec whose questions are answered → cheaper model. Deciding what is *true*, adjudicating two defensible readings, or anything where being wrong is silent and consequential → stronger model. Configuration-cache correctness is the archetype of wrong-is-silent.

**Give the implementer the oracle**, not just the spec. See above.

**Commit a pre-audit checkpoint** before auditing, so the audit's effect is a readable diff rather than blended into the implementation.

**Demand honest reports.** "These twelve cases are unwritten" is far more useful than a completeness claim an audit then contradicts. Ask explicitly what was *not* verified.

**Never bundle a tightly-specified task with an exploratory one in the same agent.** A precise fix and a loosely-sketched build have different failure modes, and combining them makes progress illegible from outside — you cannot tell whether a long run means the hard part is hard or the fuzzy part is drifting. Split them, even when they touch the same area. The cost is one extra handoff; the benefit is that elapsed time means something.

**Prototype an unproven mechanism yourself before specifying it.** When a design rests on framework behavior nobody here has run, build the minimal case first and spec from the result. Specifying a mechanism you have only reasoned about produces confident text that may be wrong — and downstream nobody can tell which sentences were verified. Mark what the prototype could *not* establish, so the implementer knows where the real risk is.

**A prototype needs a control.** Confirming the new approach works proves little on its own — the flag may be ignored, the check may not be running. Also verify that the *old* approach still fails under the same conditions. That is what turns "it worked" into evidence.

## Conventions

- `core` has **zero Gradle API** on its classpath. `plugin` depends on `core`, never the reverse.
- Error messages name the **file**, the **key**, and the **fix** — including the literal command to run.
- Gates that find nothing to check must **fail loudly**, not pass. A gate verifying nothing manufactures false confidence.
- Corpus mechanics (`PLAN.md` §2.1): directory-per-case; `expected.xcstrings` for success, `expected-error.txt` for errors, optional `expected-warnings.txt` — **absent means zero diagnostics are asserted**.
- Never edit the Notion Decision Brief, Decision Log, or Market Audit. Flag proposed changes instead.
