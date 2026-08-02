# SQLite ownership / BLOB / blocking-FFI gap refresh against v0.5.17

Date: 2026-08-02
Branch: `agent/db-v0517-transaction-depth`
Scope: re-check `clj/db/sqlite.clj` (main @ `41da91e`) against the **released**
Jolt v0.5.17 FFI surface. Supersedes v0.5.11-era assumptions in prior audits.

## Evidence levels

- `table`: source reads of released v0.5.17 (`jolt-v0517-reference` worktree,
  tag `v0.5.17` = `da59e49d`): `stdlib/jolt/ffi.clj` facade and
  `host/chez/java/ffi.ss` host primitives.
- `probed`: compiled and executed under the released v0.5.17 launcher
  (`/home/chuck/.local/bin/jolt`, `jolt version` = v0.5.17).
- `runtime`: db suites executed under the same launcher (201/201 baseline;
  36-check probe suite, see `tx-depth-revalidation-v0517.md`).

## v0.5.17 FFI surface (released)

`defcfn`/`foreign-fn` accept exactly one option: `:blocking` (collect-safe
emission). `foreign-callable`/`export!` accept `:collect-safe`. Host
primitives: `alloc` `free` `read` `write` `sizeof` `null` `null?`
`load-library` `ptr->string` `string->ptr` `read-bytes` `write-bytes`
`read-array` `write-array` (`table`, ffi.ss:57–120). `read-array`/`write-array`
are **element-wise Scheme loops**, not bulk copies.

**Not in released v0.5.17** (`table`): scoped byte-array pointer loans, ranged
byte transfers, atomic native-error capture, exact scalar widths. Those exist
only as post-tag commits on fork development lines
(`jolt-upstream-rebase-v0.5.17-candidate` = `v0.5.17-7-gf06f77f0`;
`git log v0.5.17..HEAD` lists `88b91d7b` native-error capture, `dc6d3637`
ranged transfers, `f06f77f0` byte-array loans, `0506c6a3` exact widths).
db's CI pins the latest **released** joltc, so these must not be adopted yet;
recommendations below are written for released v0.5.17 with a forward note.

## Ownership gaps — refreshed verdict: sound, one open item

- Handle lifecycle (`SqliteHandle` `closed?` atom, `live-ptr!`, idempotent
  fail-closed `close`) needs no v0.5.17 API; runtime evidence includes
  close-once / use-after-close oracles (201/201).
- Statement ownership (`run-prepared` finalizes exactly once on bind/step/
  row-read/finalize failure paths) likewise; finalize-once oracles pass.
- Open/prepare error paths close the native handle / finalize the statement
  exactly once; oracles pass.
- **Open item (from slice 1):** the begin-boundary fail-open divergence
  (`tx-depth-revalidation-v0517.md`, P1). The corrective direction is a pure
  addition and v0.5.17-compatible: bind `sqlite3_get_autocommit`
  (`[:pointer] :int`, nonblocking) and, on begin failure, verify physical
  state before deciding between cleanup and a fail-closed marker. No new FFI
  machinery is required.

## BLOB gaps — refreshed verdict: correct at v0.5.17; perf note only

- `bind-blob!` (byte-array → native buffer via `ffi/write-array` →
  `sqlite3_bind_blob64` with `SQLITE_TRANSIENT` → immediate free) is
  byte-exact and ownership-sound, including the zero-length-vs-NULL
  distinction (runtime: generated blob properties in the 201/201 suite).
  Perf note (`table`): `ffi/write-array` is an element-wise loop at v0.5.17.
  Forward note, corrected after independent review: the post-release features
  do **not** provide a bulk or zero-copy path. `dc6d3637`'s ranged transfers
  (`read-array!`, four-arg `write-array`) remain per-byte Scheme loops, and
  `f06f77f0`'s `with-byte-array-pointer` is explicitly a copy-in/copy-back
  bridge: it lends a pointer to a private native-octet *snapshot* of the
  array and copies back on scope exit. Adopting it in `bind-blob!` would
  replace the current explicit alloc/copy/free with an equivalent copy-in
  plus a wasted copy-back (the bind is input-only), so it can regress
  performance; any future migration requires a benchmark, and none is
  recommended now.
- `read-blob`'s null-pointer → immediate `sqlite3_errcode` check remains
  **necessary and must not be "modernized"**: the post-release atomic
  native-error capture targets Chez `errno`; SQLite does not report BLOB
  conversion failure via `errno` — `sqlite3_errcode` is a separate API call.
  (`table`: sqlite3 semantics; feature scope read from the fork commit.)
- `ffi/read-bytes`/`write-bytes` are string-oriented (UTF-8 with latin1
  fallback); db correctly uses `read-array`/`write-array` for binary
  (`table`, ffi.ss:68–99). No change.

## Blocking-FFI gaps — refreshed verdict: constraint revalidated, two additions

- **`:blocking` + `:string` is still rejected at v0.5.17** (`probed`):
  `(ffi/defcfn c-strlen "strlen" [:string] :int :blocking)` fails at
  expansion with "Exception in foreign-procedure: string argument not
  allowed with __collect_safe procedure". The `:pointer` +
  `ffi/string->ptr` pattern in `open`/`query` remains required. Not a gap.
- `sqlite3_step`/`sqlite3_finalize`/`sqlite3_open`/`sqlite3_close_v2` as
  `:blocking` stays correct (VFS/lock-wait paths); bind/column accessors
  correctly stay nonblocking.
- **Addition candidates** (all v0.5.17-compatible, plain `defcfn`s):
  `sqlite3_get_autocommit` (begin-failure recovery, above);
  `sqlite3_busy_timeout` (opt-in bounded sleep/retry under lock contention —
  corrected after independent review: the driver installs no busy handler,
  so SQLite's default NULL handler makes a contended `sqlite3_step` return
  `SQLITE_BUSY` **immediately**, not park; runtime probe below. The knob
  *adds* a bounded wait policy where there is currently instant failure —
  it does not cap an existing unbounded wait);
  `sqlite3_extended_errcode` (richer failure classification for the
  transaction poison paths). None are blocking-FFI *defects*; they close
  observability/behavior gaps.

Contention probe (`runtime`, released v0.5.17 launcher, x86_64-linux): two
handles on one file database, `BEGIN IMMEDIATE` on the first, then an
`INSERT` on the second returned `{:err "sqlite step failed: database is
locked", :rc 5}` in 0 ms — `SQLITE_BUSY` is immediate, confirming no busy
handler is installed.

## Nonclaims

- No PostgreSQL execution (`JOLT_TEST_PG_URI` unset); `db.pg` was not part of
  this refresh.
- Post-release fork FFI features were read as source only; none were
  compiled or adopted, and no recommendation depends on them.
- The `probed` blocking/string result covers the Linux x86_64 launcher only;
  macOS/Windows lanes were not executed.
