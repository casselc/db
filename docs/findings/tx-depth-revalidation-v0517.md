# Transaction-depth / double-transition revalidation (slice 1)

Date: 2026-08-02
Branch: `agent/db-v0517-transaction-depth`
Worktree: `/home/chuck/ai-src/worktrees/db-v0517-transaction-depth`

## Baseline and environment

- db `main` @ `41da91e` ("test(sqlite): align byte expectations with Jolt"), clean tree.
- Launcher: `/home/chuck/.local/bin/jolt`, **jolt v0.5.17** (released; `jolt version`).
- Chez: resolved by the launcher (no `JOLT_CHEZ` override was needed).
- libsqlite3: 3.45.1 (`/usr/lib/x86_64-linux-gnu/libsqlite3.so.0.8.6`), x86_64-linux.
- Hermetic env per run (`HOME`/`XDG_CACHE_HOME`/`XDG_CONFIG_HOME`/`GITLIBS`/
  `JOLT_CACHE_DIR`/`TMPDIR` under `/tmp/db-*`, `JOLT_PWD` set to the worktree).
- One local build at a time; no concurrent jolt-sim work touched
  (`jolt-sim` main @ `eb7bce4` clean; active TCP/bencode worktree
  `jolt-sim-tcp-bencode-example` @ `ac8e0d8` untouched, read-only lane).

The prior audit-grade record (`docs/proofs/transaction-state.md`, 2026-07-30)
was treated as historical: its runtime evidence is v0.5.11-era
(`v0.5.11-21-g5ced70c5` and released `v0.4.15`, 138 checks). All results below
were re-derived live.

## Commands

Baseline (run twice: main checkout and this worktree, identical result):

```sh
env HOME=$T/home XDG_CACHE_HOME=$T/cache XDG_CONFIG_HOME=$T/config \
    GITLIBS=$T/gitlibs JOLT_CACHE_DIR=$T/jolt-cache TMPDIR=$T/tmp \
    JOLT_PWD=$PWD /home/chuck/.local/bin/jolt -M:test
```

Probe suite (this branch only; not wired into the `:test` alias):

```sh
env ... /home/chuck/.local/bin/jolt -A:test -m jdbc.tx-depth-probe-test
```

Note: `jolt -M:test -m <ns>` does **not** override the alias `:main-opts`; the
alias's `-m jdbc.core-test` consumes the rest as program args. `-A:test` (paths
and deps without alias main-opts) plus `-m` is the working form.

## Test counts

- Baseline suite: **201/201 checks passed** (sqlite only; `JOLT_TEST_PG_URI`
  unset, so the PostgreSQL section did not run).
- Probe suite: **36 checks, 4 failures** — all four failures are intended
  characterizations of reproduced behavior (below). Full output preserved in
  `docs/findings/artifacts/tx-depth-probes-v0517-run1.txt`.

## Verdict on the reported bug

**No double-completion-transition or depth-bookkeeping defect reproduces** on
any completion path. The exact-trace matrix (P3/S1–S6) shows exactly one
`BEGIN` per outer boundary, one `SAVEPOINT` per nested boundary, and exactly
one completion transition per begun boundary (success, body-throw,
rollback-only, nested success, caught nested failure, injected COMMIT failure).
Reentrant depth samples ascend 1→2→3 and return to 0 (P4). The `1bb5b59`
fail-closed bookkeeping holds under v0.5.17.

**A real transaction-depth divergence does reproduce — at the begin boundary,
fail-open (P1).** When the driver reports failure *after* `BEGIN` physically
completed (e.g. a finalize/step error surfaced post-transition), `atomic-apply`
propagates before any bookkeeping or cleanup:

- logical state reports ready (`:depth 0`, not pending, not poisoned);
- the connection is **physically still inside a transaction**
  (next `BEGIN` fails: "sqlite step failed: cannot start a transaction within
  a transaction");
- the next `atomic-apply` therefore fails, and the library offers no
  fail-closed rejection or recovery path — contrast the completion paths,
  which poison the connection (fail closed) under the same uncertainty.

The nested-savepoint analogue (P2) is benign: the savepoint leaks inside the
outer transaction but the outer `COMMIT` clears it and the connection ends
both logically ready and physically idle.

Smallest failing characterization: `probe-p1-begin-divergence!` in
`clj-test/jdbc/tx_depth_probe_test.clj` (2 failing checks), preserved failing
by design; do not wire into the default `:test` alias without deciding the
fix contract first.

This is the same uncertainty class the existing proof record deferred ("Future
verified recovery could bind `sqlite3_get_autocommit`"), but the probe shows
the two ends of the boundary are handled **asymmetrically**: completion
failure poisons (fail closed), begin failure reports ready (fail open).

## Incidental characterization: multi-statement SQL truncation (P5)

`db.sqlite/query` passes `ffi/null` as `sqlite3_prepare_v2`'s `pzTail`, so only
the first statement of a compound SQL string runs; later statements are
silently dropped (`"create table pz(x); insert into pz values (1)"` creates
the table and drops the insert). The library never claimed multi-statement
semantics, but the silence is a hazard for a JDBC-shaped API: options are
documenting the single-statement contract, rejecting SQL with a non-empty
tail, or preparing the tail chain. Decision deferred; checks kept as
tripwires.

## Incidental observation (jolt reader, not db)

An unmatched top-level `)` in a source file silently truncates loading at
that point: `require`/`load-file` succeed, later forms never intern, exit 0.
Cost one debugging cycle here; worth a jolt-upstream ticket, not fixed in this
lane.

## Decisions

- Probes live in `clj-test/jdbc/tx_depth_probe_test.clj`, run via
  `-A:test -m jdbc.tx-depth-probe-test`; intentionally failing checks encode
  the reproduced defect, so the file stays out of the default `:test` alias.
- `agent/db-sqlite-blob-hegel` @ `53db5cf` (unmerged, main + `clj/deps.edn`
  source-only dep root) was **not** folded in; slice 1 stays minimal on
  `main` @ `41da91e`. Reconsider when the hegel gate runs.
- No library code changed in slice 1 (research/characterization only).

## Remaining uncertainty / nonclaims

- PostgreSQL was not executed (no `JOLT_TEST_PG_URI`); all results are
  sqlite-only. `db.pg` begin-path behavior under the same trigger is unprobed.
- The P1 trigger was injected at the Clojure driver-call boundary (faithful
  to "driver reported failure after transition"); real-world frequency of
  post-success BEGIN failure was not measured and is not claimed.
- The hegel property gate (`jolt hegel-test`) was not run in this slice.
- The v0.5.17 FFI refresh (scoped byte-array loans, ranged transfers, atomic
  native-error capture vs. `read-blob`'s errcode race) is slice 2 scope, not
  started.
- The old SMT model was not re-run; its v0.5.11-era claims were superseded by
  the executable matrix above rather than re-proved.
