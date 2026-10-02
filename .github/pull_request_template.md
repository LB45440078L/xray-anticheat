## What this changes

<!-- One or two sentences. What behaviour is different after this, and why. -->

## Why

<!-- The problem it solves. If it fixes a reported bug, link the issue. -->

## Checks

- [ ] `mvn clean verify` passes (all 120 tests)
- [ ] `bash tools/ci/verify_jar.sh` passes
- [ ] No dependency gained or lost its `provided` scope
- [ ] If a version property in `pom.xml` changed for HikariCP, sqlite-jdbc, mariadb-java-client or
      postgresql, `libraries:` in `xray-paper/src/main/resources/plugin.yml` was updated to match.
      Version drift between those two files compiles perfectly and breaks only at runtime.
- [ ] Documentation updated for anything user-visible (README, `docs/`, `CHANGELOG.md`)

## Notes for the reviewer

<!-- Anything you are unsure about, or that you deliberately did not do. Say so here rather
     than leaving it to be discovered later. -->

## Did you run it, or only build it?

<!--
  Be honest — "compiled and tested" is a fine answer, and a much more useful one than an
  implied claim of runtime verification. Nothing in this project has yet been loaded by a
  real Paper server, so a change to listeners, the interface or storage has NOT been
  exercised at runtime unless you say you ran it on a server.
-->
