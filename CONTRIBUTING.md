# Contributing

Before anything else: this repository is **proprietary**. See [`LICENSE.txt`](LICENSE.txt) — no
licence is granted, and contributing does not change that. If you have been given access to this
source, the terms you were given it under govern what you may do with it. Do not redistribute it.

## Getting set up

The build only works properly under WSL/Linux. See
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the full toolchain, and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before making a structural change.

```bash
source ~/.local/tools/env.sh     # JDK + Maven for this project
mvn clean test                   # unit + integration tests
mvn clean package                # the shaded plugin jar
```

The build compiles with `maven.compiler.release = 25`, so the jar runs on Java 25 or newer. Do not
raise it without a reason: it silently raises the runtime requirement for every server operator.

## The rules that are not negotiable

These are architectural constraints, not preferences. A change that breaks one of them will be
rejected even if the tests pass.

1. **`xray-core` has no Minecraft dependency.** No `org.bukkit` type may appear in the core. The
   analytical engine is expressed against `Vector3`/`BlockPos` and the outbound ports so that it is
   testable in a plain JVM and portable across server platforms.
2. **Nothing blocking runs on the server thread.** No JDBC call, no file I/O, no expensive
   computation in a listener or a command body. Analysis and persistence happen on workers; only the
   final Minecraft API call is scheduled back with `runSync`.
3. **Uncertainty never becomes evidence.** `UNKNOWN` neither counts for nor against a player. When
   data is missing or unprovable, say so and drop the observation rather than guessing it.
4. **Never ship a configuration key, message or UI action that does nothing.** If you add one, wire
   it. If you remove the code behind one, remove the key. A setting that silently does nothing is
   worse than a missing setting, because it teaches operators that the configuration is unreliable.
5. **Never report success for something that did not happen.** Especially in the moderator interface:
   do not tell a moderator an action was carried out when it was not.

## Tests

Run them. `mvn clean package` must be green before anything is committed.

- Unit tests live beside the code they test and must not need a Minecraft server.
- Anything touching SQL goes in `xray-persistence` against the real embedded SQLite database, not
  against a fake. Fakes that do not reproduce the real query's ordering have hidden real bugs here
  before.
- When you fix a false positive, add a scenario test that reproduces the original mining and fails
  before the fix. Pick the sample size at which the bug actually reaches a decision band, or the test
  passes for the wrong reason.
- When you add a configuration key, the audit is part of the job: check that the code reads it.

## Mathematics and documentation

`docs/MATHEMATICAL_MODEL.md` and `docs/STATISTICAL_MODEL.md` describe what the code implements. If
you change the model, change the documents in the same commit. **Do not write mathematics the code
does not perform**, and do not describe an approximation as exact. Where an approximation exists,
state its direction: does it make the system more or less likely to flag someone?

## Style

[`.editorconfig`](.editorconfig) is authoritative and most editors honour it. Beyond formatting:

- Java 4-space indent, 100-column soft limit, LF line endings.
- Prefer composition over inheritance, records for value types, `final` fields.
- Comments explain *why*: the statistical reasoning, a non-obvious Minecraft behaviour, a
  concurrency decision, a performance trade-off. Do not comment what the code already says.
- No god classes, no static mutable state, no magic numbers. Thresholds live in configuration with
  a default that is justified somewhere.

## Commits

- One logical change per commit, with a message that says what changed and why.
- Do not commit build output, a database file, or anything under `target/` — `.gitignore` covers it.
- Do not commit a `server/` or `run/` directory containing a test server.

## Security

If you find something that could be abused — SQL injection, a permission bypass, an item-duplication
route in the moderator interface, a way to cause unbounded memory growth — read
[`SECURITY.md`](SECURITY.md) and report it privately rather than opening a public issue.
