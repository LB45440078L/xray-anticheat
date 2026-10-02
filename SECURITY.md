# Security Policy

This plugin handles behavioural data about identifiable players and can remove them from a server.
A flaw in it is therefore not only a software bug: it can be a false accusation, a data leak, or a
way for a cheater to escape detection. Treat reports accordingly.

## Reporting a vulnerability

**Do not open a public issue.** Report privately to the maintainer of this repository — the address
or channel you were given access to this source under. If you have no private channel, open an issue
containing only a request for a private contact route, with no technical detail.

Please include:

- what the vulnerability is, and its impact;
- the smallest reproduction you can manage;
- the version or commit you tested;
- whether it is already being exploited, as far as you know.

You do not need a working exploit to report something. A credible description of the mechanism is
enough.

## What counts

In scope:

- **False-positive sources.** Any input that makes a legitimate player accumulate suspicion they
  should not: a classification path that mis-reads exposure, a way to make the statistical model
  count one event repeatedly, an arithmetic overflow or precision loss that skews a verdict.
- **Data exposure.** Any path by which stored records, evidence explanations or UUIDs are disclosed
  without the corresponding permission; log output that leaks more than it should.
- **Permission bypass.** Any command, tab completion, or moderator-interface action reachable
  without its permission — including a stale menu left open across a permission change.
- **Injection.** SQL injection through any route, including configuration values and player names.
- **Resource exhaustion.** Unbounded memory or disk growth reachable from player or moderator
  action: a buffer without a bound, a cache without expiry, a query without a limit.
- **Detection evasion by design flaw.** A straightforward trick that defeats the whole system rather
  than one signal, rather than merely being unusual behaviour.

Out of scope:

- A statistical false positive that is a consequence of the model's documented behaviour. Report it
  as a false positive, not as a vulnerability — see `docs/MODERATOR_GUIDE.md`, and include the
  mining scenario if you can.
- Anything requiring an operator to have already granted a full-permission account, or to have run
  the server in an unsupported configuration.
- Vulnerabilities in dependencies (HikariCP, the JDBC drivers, Paper). Report those upstream; tell us
  only if a fix requires a change here.
- Anything in the `ALERT_ONLY` / default configuration that merely produces noise.

## Handling expectations

- Acknowledgement of a private report as soon as is practical.
- A fix, a workaround, or an explanation of why it is not one — rather than silence.
- Credit in `CHANGELOG.md` on request, or anonymity if you prefer.
- No bug bounty is offered. This is a single-maintainer project.

## Supported versions

Only the current development line is supported. There is no back-porting of security fixes to older
releases during development. If you are running a published release, check `CHANGELOG.md` for
whether a fix has landed since.

## For operators

- Run with the shipped defaults until you have compared the output against what you know about your
  players. The defaults gather evidence and plan ban waves but do not enforce on their own.
- Keep the database backed up (see `docs/ADMIN_GUIDE.md`). It is behavioural evidence, and it is
  also the audit trail for any enforcement you take.
- Grant the moderation permissions narrowly. `xray.ban` and `xray.banwave` are the ones that remove
  people; they are not needed to look at evidence.
- Review `docs/PRIVACY.md` before collecting data at scale, particularly if your players include
  children, and remember that the plugin's output is evidence for a human to weigh, not a verdict.
