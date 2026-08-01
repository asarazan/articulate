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

## The rules that have actually caught bugs here

Each of these exists because it caught something real. They are not style preferences.

**Prefer an oracle to an argument.** Real `aapt2` (`/private/tmp/claude-501/aapt2bin/aapt2`) and `xcstringstool` (`$(xcode-select -p)/usr/bin/xcstringstool`) are the reference implementations. When behavior is unclear, *compile the input and observe* rather than reasoning from documentation. This is the single biggest quality lever in the project — it turns "did I implement this right?" from judgment into experiment.

**Observation beats documentation.** Vendor docs have been contradicted by a real tool run **four times** here: Android's whitespace-collapsing rule, `addGeneratedSourceDirectory`'s output ownership, that ownership being AGP-version-dependent, and `xcstringstool`'s `state: "new"` behavior. Treat "the docs say X" as a hypothesis.

**Hexdump anything involving whitespace or non-ASCII.** Terminal rendering hides the difference between one space and three, and between U+0020 and U+2008. Both distinctions are load-bearing in this spec.

**Write no test that cannot fail.** This repo has shipped assertions satisfied by their own fixture name (`no_comment`), by their own directory path (`error-string-precision`), by a no-op input (a Turkish-locale test using an already-lowercase string), and by a single-item case where "lists all" and "lists the first" are indistinguishable. When a new test passes on the first run, **prove it can fail**: mutate the production code, watch it break, revert. `CorpusTest` has a guard against the path-substring class — if it fires, the assertion is too weak; do not work around it.

**A green build that skipped its hardest tests is not green.** The entire golden corpus was once invisible to Gradle's up-to-date checking, so editing a fixture left `:core:test` UP-TO-DATE and the change never ran. Silence is not success.

**Never hand-type byte-exact expected output.** Generate fixtures from verified program output, inspect with `xxd`/`cat -e`, then delete the generator. Hand-transcribing nested JSON or control characters is how expectation bugs get committed.

**Control bytes need binary-safe tooling.** The Edit tool normalizes control characters passed through its interface. Use `python3` with byte-level `replace`, assert the match count, verify with a hexdump. A raw `0x0C` form feed once shipped in an escape table, invisible in review.

**Rebuild and re-run the full suite after every fix, not after batching several.** A fix that breaks a neighbour must be caught while the cause is unambiguous.

**Mark verification status in specs.** VERIFIED / BEST-EFFORT / PROVISIONAL, with the evidence. Making an unknown *visible* is what got `TRAILING_NEWLINE` checked — it was provisionally wrong, and one byte would have defeated the whole drift gate.

## Delegating work

**The auditor must never be the implementer.** Separation matters more than model tier — a producer cannot see the shape of what it failed to write. Every high-value finding in this project has been an **absence**: a missing input declaration, twelve untested specifier families, an assertion that asserts nothing. Audit for negative space, not for errors.

**Route by whether a mistake fails loudly.** Implementing against a spec whose questions are answered → cheaper model. Deciding what is *true*, adjudicating two defensible readings, or anything where being wrong is silent and consequential → stronger model. Configuration-cache correctness is the archetype of wrong-is-silent.

**Give the implementer the oracle**, not just the spec. See above.

**Commit a pre-audit checkpoint** before auditing, so the audit's effect is a readable diff rather than blended into the implementation.

**Agents must run no `git` command at all** — not `add`/`commit`/`push`, and not `checkout`/`restore`/`reset`/`stash`/`clean`/`diff`/`status`/`log`. The tree they work in is usually uncommitted, so a reflexive `git checkout -- file` can destroy work that exists nowhere else. To revert a mutation test: `cp` to a backup first, `cp` back. To inspect: Read and Glob.

**Demand honest reports.** "These twelve cases are unwritten" is far more useful than a completeness claim an audit then contradicts. Ask explicitly what was *not* verified.

## Conventions

- `core` has **zero Gradle API** on its classpath. `plugin` depends on `core`, never the reverse.
- Error messages name the **file**, the **key**, and the **fix** — including the literal command to run.
- Gates that find nothing to check must **fail loudly**, not pass. A gate verifying nothing manufactures false confidence.
- Corpus mechanics (`PLAN.md` §2.1): directory-per-case; `expected.xcstrings` for success, `expected-error.txt` for errors, optional `expected-warnings.txt` — **absent means zero diagnostics are asserted**.
- Never edit the Notion Decision Brief, Decision Log, or Market Audit. Flag proposed changes instead.
