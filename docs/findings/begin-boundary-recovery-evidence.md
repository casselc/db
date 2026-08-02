# Begin-boundary recovery — slice evidence and policy decision

Date: 2026-08-02
Branch: `agent/db-v0517-begin-boundary-recovery`, from `8f9fd7f`.
Contract: `docs/findings/begin-boundary-recovery-contract.md` (R0–R7).
Preserves checkpoint-1 characterization artifacts and nonclaims; the slice-1
branch (`agent/db-v0517-transaction-depth`) and its worktree are untouched.

## Policy decision (adopted)

**Prove clean or poison** (Codex's contract, adopted verbatim): a best-effort
rollback is only a recovery *attempt*; reuse is safe only after a final
`sqlite3_get_autocommit != 0` observation. Every failure branch without that
observation poisons the connection, and the original begin exception is
primary whenever one exists. Retained cleanup context is cleared at attempt
start in the verified path, so a poison cause is never a stale error
(post-review addition; see below).

## Implementation anchors

- `clj/db/sqlite.clj`: `sqlite3_get_autocommit` bound `[:pointer] :int`,
  nonblocking (in-memory flag read); public `get-autocommit` over `live-ptr!`.
- `clj/jdbc/core.clj`: `verified-sqlite-begin!` implements R0–R7;
  `atomic-apply` uses it only for `(:vendor :sqlite, depth 0)`; all other
  paths (nested savepoint, postgres) are byte-for-byte unchanged behavior.
- `clj-test/jdbc/tx_depth_probe_test.clj`: P0 driver probes; P1/C1–C6
  contract checks (C1/C2 are the converted slice-1 failures); legacy-path
  buggy control witness; P2–P4 regression tripwires; P5 remains two
  intended out-of-scope failures.

## Commands and counts (hermetic env, released jolt v0.5.17, x86_64-linux)

```sh
env ... jolt -M:test                                # baseline suite
env ... jolt -A:test -m jdbc.tx-depth-probe-test    # probe suite
```

- Baseline, two consecutive final runs: **201/201** both times
  (`artifacts/begin-recovery-baseline-final-run{1,2}.txt`).
- Probe suite, two consecutive final runs: **69 checks, 67 ok, 2 failures**
  both times; the 2 failures are the documented P5 multi-statement
  tripwires, out of scope for this slice
  (`artifacts/begin-recovery-probes-final-run{1,2}.txt`).
- Reviewer-executed: `jolt -M:hegel-test` PASS, 48 cases, 0 failures
  (executed by the independent reviewer inside this worktree during the
  final review; recorded here as reviewer evidence, not re-run by me).

## Independent review record

- Review 1 (`codex review --uncommitted`, 600 s timeout, killed mid-run;
  partial output preserved at the session tmp path): surfaced the
  stale-cause scenario via its own discriminating probe
  (`:later-cause-old true`). Verified valid, fixed (attempt-start
  `:cleanup-errors` clear) with C6 regression checks. One false failure
  during repair was my own check-ordering bug in C6 (asserted post-poison
  state against a seed-time expectation), fixed and re-validated.
- Review 2 (same command, completed): no findings; verdict — "The SQLite
  BEGIN recovery path consistently proves the connection clean or poisons
  it while preserving the primary error."

## Remaining ambiguity / nonclaims

- PostgreSQL begin path unchanged and unprobed; `db.pg` has no autocommit
  analogue wired (PG would need `PQtransactionStatus`).
- Nested savepoint begin failure keeps slice-1 behavior (P2-verified benign;
  no savepoint-level verification added).
- R3 (rollback reports success but final ac=0) and R7 are implemented and
  poison correctly, but no deterministic injection could reach R3's
  still-open-after-successful-rollback branch — it is defensive coverage,
  not exercised by an executed test (the injected-rollback-failure R4 path
  is exercised instead). Flagged as the one uncovered contract row.
- P5 multi-statement truncation unchanged (two tripwires still fail by
  design); busy-timeout and extended-errcode additions untouched.
- All evidence is Linux x86_64; macOS/Windows lanes unexecuted.
- The slice-1 probe suite stays outside the default `:test` alias; wiring
  it into CI is an integration-session decision.
