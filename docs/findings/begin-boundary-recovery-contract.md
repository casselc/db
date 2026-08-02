# Begin-boundary recovery contract (slice 2)

Date: 2026-08-02
Branch: `agent/db-v0517-begin-boundary-recovery`, from `8f9fd7f`.
Scope: SQLite depth-0 `BEGIN` uncertainty only. No unreleased FFI APIs, no
transaction-machinery redesign, no multi-statement/PostgreSQL/busy-timeout
work. Nested savepoint failure remains governed by P2 evidence (outer
completion clears the boundary) and is unchanged.

Governing rule (Codex, adopted): **prove clean or poison.** A best-effort
rollback is only a recovery *attempt*; reuse is safe only after a final
`sqlite3_get_autocommit != 0` observation. Every `usable` outcome below is
backed by such an observation; every outcome without one poisons the
connection (`:poisoned? true`, close-required), with the original begin
exception preserved as the primary throwable whenever one exists.

## Contract table (sqlite vendor, logical depth 0)

| # | pre-probe ac | BEGIN | post-probe ac | recovery attempt | final proof | logical state after | primary throwable |
|---|---|---|---|---|---|---|---|
| R0 | ≠0 | succeeds | — | none | normal bookkeeping | depth 1, normal path | — |
| R1 | ≠0 | fails | ≠0 | none | ac≠0 observed: BEGIN never took effect | usable, depth 0 | original begin error |
| R2 | ≠0 | fails | =0 | `ROLLBACK` succeeds, re-probe ac≠0 | ac≠0 observed after counter-rollback | usable, depth 0 | original begin error |
| R3 | ≠0 | fails | =0 | `ROLLBACK` succeeds, re-probe ac=0 | cannot prove clean | poisoned | original begin error; still-in-transaction recorded |
| R4 | ≠0 | fails | =0 | `ROLLBACK` errors | cannot prove clean | poisoned | original begin error; rollback error recorded |
| R5 | ≠0 | fails | probe throws | none (outcome of any counter-command is unverifiable) | cannot prove clean | poisoned | original begin error; probe error recorded |
| R6 | =0 | not issued | — | none: a pre-existing transaction contains unknown work; a counter-rollback could destroy it | physical divergence observed | poisoned | ex-info divergence error (begin precondition) |
| R7 | pre-probe throws | not issued | — | none | cannot prove anything | poisoned | probe error, recorded |

Notes:

- R6 is the legacy-divergence tripwire: physical transaction open while
  logical depth is zero can never arise within the library contract, so the
  connection fails closed *before* issuing `BEGIN` rather than emitting a
  confusing driver error. The unknown transaction is left untouched;
  `sqlite3_close_v2` disposes of it at close.
- The pre-probe is required: without it, R2 (our BEGIN half-opened an empty
  transaction — safe to roll back) is indistinguishable from R6 (doomed
  BEGIN inside someone else's transaction — rollback would be destructive).
- R3 is defensive completeness: SQLite documents `ROLLBACK` as returning the
  connection to autocommit mode on success; the re-probe converts that
  documentation into an observation rather than an assumption.
- All three probes are the same plain `sqlite3_get_autocommit`
  `[:pointer] :int` nonblocking call — an in-memory flag read, released
  v0.5.17 surface only.
- `record-cleanup-error!` with poison already exists; phases added:
  `:begin-pre-probe`, `:begin-precondition`, `:begin-post-probe`,
  `:begin-rollback`, `:begin-rollback-verify`.
- **Attempt-start context clear** (added after independent review): the
  verified path clears `:cleanup-errors` *before* the pre-probe. The other
  paths clear only after a successful BEGIN, so a failed begin previously
  left a resolved prior attempt's retained errors in place — and
  `poisoned-transaction-exception`'s `first`-error cause could be that stale
  error instead of the error that actually poisoned the connection
  (reviewer's discriminating probe: `:later-cause-old true`). With the
  attempt-start clear, a begin-recovery poison's cause is always the fresh
  error. The asymmetry (sqlite clears at attempt start, other paths after
  BEGIN success) is deliberate slice scoping: PostgreSQL behavior is
  unchanged.
