# Transaction boundary state checks

Date: 2026-07-30

## Bounded claim

`jdbc.core/atomic-apply` maintains one coherent connection-local state for one
single-owner, reentrant transaction call chain. Within the fourteen finite
scenarios in `transaction-state.smt2`, the current algorithm:

- performs one bookkeeping exit after every successful outer `BEGIN`;
- preserves the body throwable when outer cleanup also fails;
- issues `RELEASE SAVEPOINT` after a successful nested
  `ROLLBACK TO SAVEPOINT`;
- records nested and outer cleanup failures with their phase and SQL;
- marks a caught nested cleanup failure pending before returning control to the
  encompassing outer transaction;
- rejects user fetch, execute, last-id, set-rollback, and nested-transaction
  operations while cleanup is pending;
- still permits internal rollback/release control while the connection is not
  poisoned;
- clears pending state after a successful encompassing rollback; and
- clears pending state, marks the connection poisoned, and rejects all user and
  internal SQL operations when encompassing rollback fails.

The two-stage fail-closed invariant is:

```text
caught nested cleanup failure
  => intermediate pending=true and poisoned=false
  => user operations rejected
  => internal encompassing rollback remains allowed

encompassing rollback success
  => final pending=false, poisoned=false, usable=true

encompassing rollback failure
  => final pending=false, poisoned=true, usable=false
```

`transaction-cleanup-errors` and the connection's `:close` function remain
usable while pending or poisoned. The library does not auto-close and does not
claim that logical depth zero proves the database boundary idle.

Concurrent use of one connection is outside the contract and is neither
serialized nor modeled.

## Runtime implementation anchors

- `clj/jdbc/core.clj:51-89` constructs pending/poisoned state and implements the
  public operation guard.
- `clj/jdbc/core.clj:148-181` guards fetch, execute, and last-id operations;
  `clj/jdbc/core.clj:230-235` guards set-rollback.
- `clj/jdbc/core.clj:237-290` retains cleanup context, grants the private
  internal-control capability, records nested pending versus outer poison, and
  clears pending after successful encompassing rollback.
- `clj/jdbc/core.clj:298-367` implements the branch order and single
  bookkeeping exit without replacing the primary body/completion throwable.
- `clj-test/jdbc/core_test.clj:66-181` is the mixed real-SQLite pending-window
  oracle. It executes real `BEGIN`, `SAVEPOINT`, body SQL, and
  `ROLLBACK TO SAVEPOINT`, injects only the first savepoint `RELEASE` failure,
  and distinguishes attempted from completed user SQL.
- `clj-test/jdbc/core_test.clj:183-237` covers a failed nested
  `ROLLBACK TO SAVEPOINT`, the pending fetch gate, primary completion throwable,
  and successful encompassing rollback.
- `clj-test/jdbc/core_test.clj:289-754` covers the remaining begin, body,
  completion, rollback-only, nested recovery, ordered multi-cleanup, poison, and
  subsequent-use branches.
- `clj-test/jdbc/core_test.clj:759-812` is the mixed real-SQLite outer-cleanup
  oracle: real `BEGIN` and body SQL reach SQLite, only outer `ROLLBACK` is
  injected to fail, and subsequent operations are rejected.

The forward `declare pgfn` remains separate Jolt v0.5.11 compatibility work.

## Model construction

The SMT model has three implementation arms:

- `impl_old` represents the original transaction algorithm before the
  bookkeeping, cleanup-ordering, recording, and poison corrections.
- `impl_prior_guard` uses the corrected transaction algorithm but deliberately
  allows user operations in a caught-inner pending window. It is the
  discriminating control for the subsequently confirmed runtime bug.
- `impl_fixed` represents the current algorithm, including the pending user
  gate and private internal-control capability.

The model remains split into independently auditable layers:

1. `transaction-state.smt2:28-99` maps each scenario to begin/body/commit/
   rollback results, nested rollback/release results, and whether the nested
   error is caught.
2. `transaction-state.smt2:101-250` derives the selected implementation's
   bookkeeping, throwable, cleanup observation/recording, caught-inner
   intermediate state, user/internal gate decisions, outer completion,
   physical result, final pending state, poison, and final eligibility.
3. `transaction-state.smt2:252-408` defines an independent scenario-table
   reference relation. It does not select an expected implementation arm.
4. `transaction-state.smt2:410-470` asks whether any implementation transition
   differs from the reference or violates a caught-window, internal-recovery,
   pending-to-poison, physical-state, or final-operation invariant.

`intermediate_pending_cleanup` is observed after the caught inner exception and
before the outer body returns. `final_pending_cleanup` is observed after the
encompassing rollback attempt. They are intentionally different predicates:
successful rollback resolves pending, while failed rollback converts it to
poison and also clears pending.

Physical states are `physical_idle`, `physical_open`, and `physical_unknown`.
After successful `BEGIN`, the boundary is open. Successful outer commit or
rollback returns it to idle. Failed outer rollback leaves it unknown; the model
does not guess whether the database partially applied the command.

## Solver invocation and controls

The checked-in query is submitted as the complete file:

```text
chiasmus_lint({solver: "z3", input: <complete transaction-state.smt2>})
chiasmus_verify({solver: "z3", input: <complete transaction-state.smt2>})
```

Chiasmus adds solver commands, so the file contains no `check-sat`,
`get-model`, or `get-unsat-core`.

Every control uses the same declarations, definitions, and `violation`
predicate. Only the final checked-in assertions are replaced:

| Control | Final implementation/scenario assertion | Queried predicate | Result |
|---|---|---|---|
| old algorithm | `impl_old`, scenario unconstrained | `violation` | SAT |
| prior gate, RELEASE | `impl_prior_guard`, `sc_caught_nested_cleanup_outer_rollback_success` | `violation` | SAT |
| prior gate, ROLLBACK TO | `impl_prior_guard`, `sc_caught_nested_completion_cleanup_outer_rollback_success` | `violation` | SAT |
| fixed counterexample | `impl_fixed`, scenario unconstrained | `violation` | UNSAT |
| independent reference | implementation/scenario unconstrained | `reference_invalid` | UNSAT |
| normal user non-vacuity | `impl_fixed`, `sc_outer_success` | named positive normal-user outcome | SAT |
| RELEASE recovery non-vacuity | `impl_fixed`, `sc_caught_nested_cleanup_outer_rollback_success` | named positive internal-recovery outcome | SAT |
| ROLLBACK TO recovery non-vacuity | `impl_fixed`, `sc_caught_nested_completion_cleanup_outer_rollback_success` | named positive internal-recovery outcome | SAT |
| failed encompassing recovery | `impl_fixed`, `sc_caught_nested_cleanup_outer_rollback_failure` | named pending-to-poison outcome | SAT |
| direct poisoned boundary | `impl_fixed`, `sc_outer_body_rollback_failure` | named safe rejected outcome | SAT |

The checked-in assertions are:

```smt2
(assert (! (= impl impl_fixed) :named implementation_under_test))
(assert (! violation :named violation_query))
```

For the old control, replace `impl_fixed` with `impl_old`. For each prior
control, replace it with `impl_prior_guard`, add the named scenario assertion
shown in the table, and retain `violation_query`. For the reference control,
replace both checked-in assertions with:

```smt2
(assert (! reference_invalid :named reference_query))
```

Each non-vacuity control replaces the checked-in assertions with
`implementation_under_test`, `scenario_under_test`, and the named positive
conjunction described in the table. These controls do not assert `violation`.

Chiasmus lint reported no fixes or errors. The old witness selected
`sc_outer_body_rollback_failure`: outer cleanup failed, the body throwable was
not primary, physical state was unknown, and the connection remained
unpoisoned and usable. The two pinned prior witnesses both had:

```text
caught_cleanup_window=true
intermediate_pending_cleanup=true
intermediate_user_operation_allowed=true
intermediate_internal_control_allowed=true
```

The fixed arms for those same RELEASE and ROLLBACK TO scenarios instead had
user-operation allowed=false and internal-control allowed=true. Successful
encompassing recovery ended pending=false, poison=false, and usable=true.
Failed encompassing recovery ended pending=false, poison=true, and
usable=false.

The fixed UNSAT core includes the finite scenario definitions, branch-derived
intermediate/final transitions, caught-window user and internal gates,
independent reference-table definitions, `violation_definition`, and the fixed
query. The reference query is separately UNSAT, and all five positive
non-vacuity controls are SAT.

## Runtime evidence

The isolated SQLite suite passed 138/138 checks with Chez Scheme 10.4.1 under:

- canonical Jolt `v0.5.11-21-g5ced70c5`; and
- released Jolt `v0.4.15`.

Canonical command:

```sh
env HOME=/tmp/db-jolt-final.6V0dnV/home \
    XDG_CACHE_HOME=/tmp/db-jolt-final.6V0dnV/cache \
    XDG_CONFIG_HOME=/tmp/db-jolt-final.6V0dnV/config \
    GITLIBS=/tmp/db-jolt-final.6V0dnV/gitlibs \
    JOLT_CACHE_DIR=/tmp/db-jolt-final.6V0dnV/jolt-cache \
    TMPDIR=/tmp/db-jolt-final.6V0dnV/tmp \
    JOLT_CHEZ=/home/chuck/.local/chez-10.4.1/bin/chez \
    /home/chuck/ai-src/worktrees/jolt-upstream-rebase-v0.5.11/bin/joltc \
    -M:test
```

Released command:

```sh
env HOME=/tmp/db-jolt-transaction-review.Z869rR/home-v0415 \
    XDG_CACHE_HOME=/tmp/db-jolt-transaction-review.Z869rR/cache-v0415 \
    TMPDIR=/tmp/db-jolt-transaction-review.Z869rR/tmp-v0415 \
    JOLT_CHEZ=/home/chuck/.local/chez-10.4.1/bin/chez \
    /home/chuck/.local/bin/joltc \
    -M:test
```

PostgreSQL execution remains conditional on `JOLT_TEST_PG_URI` and was not run
in this slice. Command traces exercise shared transaction branching, but actual
PostgreSQL integration is still required before claiming backend coverage.

## Remaining semantic limits

This is a bounded decision model plus runtime oracle, not a proof of SQLite or
PostgreSQL engine semantics. It covers one outer boundary with at most one
nested boundary and one caught-inner observation window. The five user
operations are collapsed into one eligibility predicate; their individual
runtime implementations are covered by the SQLite regression rather than
separate solver states.

The model omits arbitrary nesting, concurrent connection sharing, process
failure, automatic database rollback, and driver-level transaction-status
inspection. The private dynamic internal-control capability is modeled under
the documented single-owner synchronous call-chain assumption. The real
SQLite pending-window oracle injects `RELEASE`; the `ROLLBACK TO` companion is
a command-trace fault injection. PostgreSQL was not executed.

Future verified recovery could bind `sqlite3_get_autocommit` and
`PQtransactionStatus`, retry rollback only when the driver reports an open or
failed transaction, and clear poison only after status is verified idle. Until
then, an unresolved outer cleanup failure remains close-required.
