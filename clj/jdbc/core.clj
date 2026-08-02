(ns jdbc.core
  "clojure.jdbc's API (https://github.com/yogthos/clojure.jdbc) for jolt,
  over native database drivers (bound through jolt.ffi) instead of java.sql:

  - SQLite via db.sqlite (the system libsqlite3)
  - PostgreSQL via db.pg (the system libpq)

  Connections are plain maps carrying the driver handle plus a :close fn, so
  `with-open` works. Queries are strings or sqlvecs ([sql & params], JDBC ?
  placeholders — rewritten to $N for postgres). Rows come back as vectors of
  keyword-keyed maps.

  A connection is single-owner and reentrant. Nested operations on that owner
  are supported, but concurrent operations or close on the same connection are
  outside the contract.

      (require '[jdbc.core :as jdbc])
      (with-open [conn (jdbc/connection \"sqlite::memory:\")]
        (jdbc/execute! conn \"create table p (id integer primary key, name text)\")
        (jdbc/insert! conn :p {:name \"ada\"})
        (jdbc/fetch conn [\"select * from p where name = ?\" \"ada\"]))"
  (:require [clojure.string :as str]
            [db.sqlite :as sqlite]))

;;; dbspec

(defn- parse-uri-spec [s]
  (cond
    (str/starts-with? s "postgres")  {:vendor "postgresql" :uri s}
    (str/starts-with? s "sqlite:")   {:vendor "sqlite" :name (subs s 7)}
    ;; bare path = sqlite file
    :else                            {:vendor "sqlite" :name s}))

(defn- pg-uri [{:keys [uri name host port user password]}]
  (or uri
      (str "postgres://"
           (when user (str user (when password (str ":" password)) "@"))
           (or host "127.0.0.1")
           (when port (str ":" port))
           "/" name)))

(defn- normalize-spec [spec]
  (cond
    (string? spec) (parse-uri-spec spec)
    (map? spec)    (let [vendor (or (:vendor spec) (:subprotocol spec))
                         spec   (assoc spec :vendor (case vendor
                                                      ("postgresql" "postgres" "pgsql") "postgresql"
                                                      ("sqlite" "sqlite3") "sqlite"
                                                      (throw (ex-info (str "unknown vendor: " vendor) {:spec spec}))))]
                     (if (:subname spec) (assoc spec :name (:subname spec)) spec))
    :else (throw (ex-info "dbspec must be a string or a map" {:spec spec}))))

;;; connection

(defn- new-transaction-state []
  (atom {:depth 0
         :rollback? false
         :cleanup-errors []
         :pending-cleanup? false
         :poisoned? false}))

(defn- cleanup-context [errors]
  (mapv #(select-keys % [:phase :sql]) errors))

(def ^:dynamic ^:private *transaction-cleanup-command?* false)

(defn- poisoned-transaction-exception [state]
  (let [errors (:cleanup-errors @state)]
    (ex-info "connection transaction state is indeterminate; close connection"
             {:jdbc/transaction-poisoned true
              :jdbc/close-required true
              :jdbc/transaction-cleanup (cleanup-context errors)}
             (:error (first errors)))))

(defn- pending-cleanup-operation-exception [state]
  (let [errors (:cleanup-errors @state)]
    (ex-info
      "transaction cleanup is pending; operation rejected until encompassing rollback"
      {:jdbc/transaction-cleanup-pending true
       :jdbc/transaction-cleanup (cleanup-context errors)}
      (:error (first errors)))))

(defn- ensure-transaction-usable! [conn]
  (when-let [state (:tx-state conn)]
    (let [snapshot @state]
      (cond
        (:poisoned? snapshot)
        (throw (poisoned-transaction-exception state))

        (and (:pending-cleanup? snapshot)
             (not *transaction-cleanup-command?*))
        (throw (pending-cleanup-operation-exception state)))))
  conn)

(declare pgfn)

(defn connection
  "Open a connection. spec is a uri string (\"sqlite:path\", a bare sqlite
  path, or \"postgres://user:pass@host:port/db\") or a dbspec map with
  :vendor (or :subprotocol) + :name/:subname [:host :port :user :password].
  The returned conn map has a :close fn — use with-open. A connection supports
  one logical owner, including reentrant/nested use; do not overlap operations
  or close from multiple threads."
  [spec]
  (let [{:keys [vendor] :as spec} (normalize-spec spec)]
    (case vendor
      "sqlite"
      (let [h (sqlite/open (:name spec))]
        (try
          (sqlite/query h "PRAGMA foreign_keys=1;" [])
          {:vendor   :sqlite
           :handle   h
           :tx-state (new-transaction-state)
           :close    (fn [] (sqlite/close h))}
          (catch Throwable primary
            ;; No connection owner escapes when initialization fails. Jolt has
            ;; no suppressed-exception API, so cleanup is attempted without
            ;; replacing the original initialization throwable.
            (try
              (sqlite/close h)
              (catch Throwable _ nil))
            (throw primary))))
      "postgresql"
      ;; db.pg (and libpq) load lazily — only when a postgres connection is made,
      ;; so a sqlite-only app never needs libpq present.
      (do (require '[db.pg])
          (let [h ((pgfn "connect") (pg-uri spec))]
            {:vendor   :postgresql
             :handle   h
             :tx-state (new-transaction-state)
             :close    (fn [] ((pgfn "close") h))})))))

;;; queries

(defn- sqlvec [q]
  (cond
    (string? q) [q []]
    (vector? q) [(first q) (vec (rest q))]
    :else (throw (ex-info "query must be a string or sqlvec" {:q q}))))

(defn- pg-placeholders
  "JDBC ? placeholders -> postgres $1..$N (skipping ? inside '...' literals)."
  [sql]
  (loop [out "" i 0 n 1 in-str false]
    (if (= i (count sql))
      out
      (let [c (subs sql i (inc i))]
        (cond
          (= c "'") (recur (str out c) (inc i) n (not in-str))
          (and (= c "?") (not in-str)) (recur (str out "$" n) (inc i) (inc n) in-str)
          :else (recur (str out c) (inc i) n in-str))))))

(defn- sqlite-eval [conn sql params]
  (sqlite/query (:handle conn) sql params))

;; db.pg is required lazily (only for a postgres connection), so resolve its fns
;; at runtime — a compile-time db.pg/foo reference would be read as a host class.
(defn- pgfn [n] (deref (resolve (symbol "db.pg" n))))

(defn- pg-eval [conn sql params]
  ((pgfn "exec") (:handle conn) (pg-placeholders sql) params))

(defn fetch
  "Run a query (string or sqlvec), return a vector of keyword-keyed row maps."
  ([conn q] (fetch conn q {}))
  ([conn q opts]
   (ensure-transaction-usable! conn)
   (let [[sql params] (sqlvec q)
         rows (case (:vendor conn)
                :sqlite     (sqlite-eval conn sql params)
                :postgresql ((pgfn "all") (:handle conn) (pg-placeholders sql) params))]
     (if-let [n (:max-rows opts)] (vec (take n rows)) rows))))

(defn fetch-one
  "Run a query, return the first row map (or nil)."
  ([conn q] (fetch-one conn q {}))
  ([conn q opts] (first (fetch conn q (merge {:max-rows 1} opts)))))

(defn execute!
  "Execute a statement (string or sqlvec). Returns rows affected."
  ([conn q] (execute! conn q {}))
  ([conn q opts]
   (ensure-transaction-usable! conn)
   (let [[sql params] (sqlvec q)]
     (case (:vendor conn)
       :sqlite     (do (sqlite-eval conn sql params)
                       (sqlite/changes (:handle conn)))
       :postgresql (do (pg-eval conn sql params) nil)))))

(defn last-insert-id
  "Driver-specific id of the last inserted row (sqlite: last_insert_rowid)."
  [conn]
  (ensure-transaction-usable! conn)
  (case (:vendor conn)
    :sqlite     (sqlite/last-insert-rowid (:handle conn))
    :postgresql (:id (first ((pgfn "all") (:handle conn) "select lastval() as id" [])))))

;;; insert! / update! / delete! — the clojure.jdbc convenience surface

(defn- entity-str [entities x] (entities (if (keyword? x) (name x) (str x))))

(defn insert!
  "Insert one row map. Returns the generated id (sqlite) / nil (postgres —
  use \"... returning *\" with execute!/fetch for the row)."
  ([conn table row] (insert! conn table row {}))
  ([conn table row opts]
   (let [entities (get opts :entities identity)
         cols (vec (keys row))
         sql (str "INSERT INTO " (entity-str entities table)
                  " (" (str/join ", " (map #(entity-str entities %) cols)) ")"
                  " VALUES (" (str/join ", " (repeat (count cols) "?")) ")")]
     (execute! conn (into [sql] (map #(get row %) cols)) opts)
     (last-insert-id conn))))

(defn insert-multi!
  "Insert a sequence of row maps; returns a vector of generated ids."
  ([conn table rows] (insert-multi! conn table rows {}))
  ([conn table rows opts]
   (mapv #(insert! conn table % opts) rows)))

(defn update!
  "(update! conn :person {:zip 94540} [\"zip = ?\" 94546])"
  ([conn table set-map where-clause] (update! conn table set-map where-clause {}))
  ([conn table set-map where-clause opts]
   (let [entities (get opts :entities identity)
         cols (vec (keys set-map))
         [where & wparams] where-clause
         sql (str "UPDATE " (entity-str entities table)
                  " SET " (str/join ", " (map #(str (entity-str entities %) " = ?") cols))
                  (when-not (str/blank? (or where "")) (str " WHERE " where)))]
     (execute! conn (into (into [sql] (map #(get set-map %) cols)) wparams) opts))))

(defn delete!
  "(delete! conn :person [\"zip = ?\" 94546])"
  ([conn table where-clause] (delete! conn table where-clause {}))
  ([conn table where-clause opts]
   (let [entities (get opts :entities identity)
         [where & params] where-clause
         sql (str "DELETE FROM " (entity-str entities table)
                  (when-not (str/blank? (or where "")) (str " WHERE " where)))]
     (execute! conn (into [sql] params) opts))))

;;; transactions: BEGIN at depth 0, SAVEPOINTs when nested (both drivers).

(defn set-rollback!
  "Mark the current transaction to roll back at the end of the atomic block."
  [conn]
  (ensure-transaction-usable! conn)
  (swap! (:tx-state conn) assoc :rollback? true)
  conn)

(defn transaction-cleanup-errors
  "Cleanup failures retained from the most recent outer transaction attempt.
  Each entry contains :phase, :sql, and the original :error. A new outer
  transaction clears the entries (on the sqlite verified-begin path, before
  BEGIN is issued; on the other paths, after BEGIN succeeds). If an outer
  cleanup failure leaves the connection poisoned, this accessor and :close
  remain usable but query and transaction operations fail closed."
  [conn]
  (:cleanup-errors @(:tx-state conn)))

(defn- command-error [conn sql]
  (try
    ;; Public operations fail closed while nested cleanup is pending. Only the
    ;; transaction machinery may issue the rollback/release that resolves it.
    (binding [*transaction-cleanup-command?* true]
      (execute! conn sql))
    nil
    (catch Throwable t t)))

(defn- record-cleanup-error! [state phase sql error poison?]
  (swap! state
         (fn [s]
           (let [next-state
                 (-> s
                     (assoc :rollback? true)
                     (update :cleanup-errors conj
                             {:phase phase :sql sql :error error}))]
             (if poison?
               (assoc next-state
                      :pending-cleanup? false
                      :poisoned? true)
               (assoc next-state :pending-cleanup? true)))))
  error)

(defn- cleanup-boundary!
  "Best-effort cleanup for one begun boundary. Nested rollback leaves its
  savepoint active, so a successful ROLLBACK TO must be followed by RELEASE."
  [conn state depth rollback release rollback-phase]
  (if-let [error (command-error conn rollback)]
    (do
      (record-cleanup-error! state rollback-phase rollback error (zero? depth))
      false)
    (if (pos? depth)
      (if-let [error (command-error conn release)]
        (do
          (record-cleanup-error! state :release-after-rollback release error false)
          false)
        true)
      (do
        ;; A real encompassing rollback resolves any indeterminate nested
        ;; boundary. Historical cleanup context remains available to the caller.
        (swap! state assoc
               :pending-cleanup? false
               :poisoned? false)
        true))))

(defn- pending-cleanup-exception [errors]
  (let [first-error (first errors)]
    (ex-info "transaction cleanup failed"
             {:jdbc/transaction-cleanup (cleanup-context errors)}
             (:error first-error))))

(defn- verified-sqlite-begin!
  "Depth-0 BEGIN for the sqlite vendor with physical-state verification,
  implementing the prove-clean-or-poison contract in
  docs/findings/begin-boundary-recovery-contract.md. Reuse is safe only
  after a final sqlite3_get_autocommit != 0 observation. Throws the original
  begin error as primary whenever one exists; poisons the connection
  whenever physical state cannot be proven clean; never issues a
  counter-rollback into a transaction it cannot prove this BEGIN opened."
  [conn state]
  ;; A new outer attempt discards retained context from prior attempts up
  ;; front (the unverified paths clear only after a successful BEGIN), so
  ;; anything recorded during this begin — including a poison cause — is
  ;; always fresh, never a stale error from a resolved attempt.
  (swap! state assoc :cleanup-errors [])
  (let [handle (:handle conn)
        probe (fn [] (try {:ac (sqlite/get-autocommit handle)}
                          (catch Throwable t {:error t})))
        pre (probe)]
    (if-let [pre-error (:error pre)]
      ;; R7: nothing about the physical boundary can be proven.
      (do
        (record-cleanup-error! state :begin-pre-probe "sqlite3_get_autocommit" pre-error true)
        (throw pre-error))
      (if (zero? (:ac pre))
        ;; R6: physical transaction open while logical depth is zero. The
        ;; pre-existing transaction holds unknown work; leave it untouched
        ;; and fail closed rather than issue a doomed BEGIN.
        (let [divergence (ex-info "connection is physically inside a transaction while logical depth is zero; close connection"
                                  {:jdbc/transaction-poisoned true
                                   :jdbc/close-required true})]
          (record-cleanup-error! state :begin-precondition "BEGIN" divergence true)
          (throw divergence))
        (let [begin-outcome (try {:value (execute! conn "BEGIN")}
                                 (catch Throwable t {:error t}))]
          (when-let [begin-error (:error begin-outcome)]
            (let [post (probe)]
              (cond
                ;; R5: begin failed and the post-probe is unavailable.
                (:error post)
                (do
                  (record-cleanup-error! state :begin-post-probe "sqlite3_get_autocommit" (:error post) true)
                  (throw begin-error))

                ;; R1: BEGIN never took effect — proven clean by ac != 0.
                (not (zero? (:ac post)))
                (throw begin-error)

                ;; Our BEGIN half-opened an empty transaction; the
                ;; counter-rollback is only an attempt — reuse requires a
                ;; final ac != 0 observation.
                :else
                (if-let [rollback-error (command-error conn "ROLLBACK")]
                  ;; R4: counter-rollback failed.
                  (do
                    (record-cleanup-error! state :begin-rollback "ROLLBACK" rollback-error true)
                    (throw begin-error))
                  (let [verify (probe)]
                    (cond
                      (:error verify)
                      (do
                        (record-cleanup-error! state :begin-rollback-verify "sqlite3_get_autocommit" (:error verify) true)
                        (throw begin-error))

                      ;; R2: proven clean by final ac != 0.
                      (not (zero? (:ac verify)))
                      (throw begin-error)

                      ;; R3: rollback reported success but the boundary is
                      ;; still open — cannot prove clean.
                      :else
                      (let [still-open (ex-info "transaction boundary still open after counter-rollback"
                                                {:jdbc/begin-recovery true})]
                        (record-cleanup-error! state :begin-rollback-verify "sqlite3_get_autocommit" still-open true)
                        (throw begin-error)))))))))))))

(defn atomic-apply
  "Run (func conn) in a transaction; nested calls use savepoints.

  Transaction bookkeeping is reentrant for one owning call chain. Concurrent
  use of the same connection is outside this function's contract."
  ([conn func] (atomic-apply conn func {}))
  ([conn func opts]
   (ensure-transaction-usable! conn)
   (let [state (:tx-state conn)
         depth (:depth @state)
         sp (str "jdbc_sp_" depth)
         begin (if (zero? depth) "BEGIN" (str "SAVEPOINT " sp))
         commit (if (zero? depth) "COMMIT" (str "RELEASE SAVEPOINT " sp))
         rollback (if (zero? depth) "ROLLBACK" (str "ROLLBACK TO SAVEPOINT " sp))
         release (str "RELEASE SAVEPOINT " sp)]
      (if (and (zero? depth) (= :sqlite (:vendor conn)))
        ;; Verified begin: a BEGIN that reports failure is reconciled against
        ;; sqlite3_get_autocommit (prove clean or poison), so logical depth
        ;; zero never reports ready while a physical transaction may be open.
        (verified-sqlite-begin! conn state)
        (execute! conn begin))
     (swap! state
            (fn [s]
              (cond-> (assoc s :depth (inc (:depth s)))
                (zero? depth) (assoc :cleanup-errors []
                                     :pending-cleanup? false))))
     (let [body-outcome
           (try
             {:value (func conn)}
             (catch Throwable t {:error t}))]
       (try
         (if-let [body-error (:error body-outcome)]
           (do
             (cleanup-boundary! conn state depth rollback release :rollback)
             ;; Cleanup context is retained on conn; preserve the exact body
             ;; throwable as the primary exception.
             (throw body-error))
           (let [ret (:value body-outcome)
                 snapshot @state
                 pending (seq (:cleanup-errors snapshot))]
             (cond
               ;; A deeper boundary failed cleanup and its body error was caught
               ;; by user code. Do not commit through an indeterminate boundary.
               pending
               (do
                 (cleanup-boundary! conn state depth rollback release
                                    :rollback-after-cleanup-failure)
                 (throw (pending-cleanup-exception
                          (:cleanup-errors @state))))

               ;; set-rollback! is transaction-wide. Nested boundaries still
               ;; release normally; the outer boundary performs the rollback.
               (and (zero? depth) (:rollback? snapshot))
               (if-let [error (command-error conn rollback)]
                 (do
                   (record-cleanup-error! state :rollback-only rollback error true)
                   (throw error))
                 ret)

               :else
               (if-let [completion-error (command-error conn commit)]
                 (do
                   ;; A failed COMMIT/RELEASE may leave the database boundary
                   ;; active. Attempt recovery without re-entering bookkeeping;
                   ;; the completion exception remains primary.
                   (cleanup-boundary! conn state depth rollback release
                                      :rollback-after-completion-failure)
                   (throw completion-error))
                 ret))))
         (finally
           ;; Exactly one bookkeeping exit follows each successful BEGIN.
           (swap! state
                  (fn [s]
                    (cond-> (update s :depth dec)
                      (zero? depth) (assoc :rollback? false))))))))))

(defmacro atomic
  "(atomic conn body...) — body runs in a transaction bound to conn."
  [conn & body]
  (if (map? (first body))
    `(atomic-apply ~conn (fn [c#] (let [~conn c#] ~@(next body))) ~(first body))
    `(atomic-apply ~conn (fn [c#] (let [~conn c#] ~@body)))))
