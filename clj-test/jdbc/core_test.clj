(ns jdbc.core-test
  (:require [jdbc.core :as jdbc]
            [db.sqlite :as sqlite]
            [jolt.ffi :as ffi]))

(def failures (atom 0))
(def checks (atom 0))

(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn thrown [f]
  (try
    (f)
    nil
    (catch Throwable t t)))

(defn capture [f]
  (try
    {:value (f)}
    (catch Throwable t {:error t})))

(defn transaction-state [conn]
  (select-keys @(:tx-state conn)
               [:depth :rollback? :pending-cleanup? :poisoned?]))

(defn transaction-ready? [conn]
  (= {:depth 0
      :rollback? false
      :pending-cleanup? false
      :poisoned? false}
     (transaction-state conn)))

(defn poisoned-error? [t]
  (true? (:jdbc/transaction-poisoned (ex-data t))))

(defn pending-cleanup-error? [t]
  (true? (:jdbc/transaction-cleanup-pending (ex-data t))))

(defn check-poisoned-operation! [label conn cleanup-error f]
  (let [caught (thrown f)]
    (check (str label ": fails closed") true (poisoned-error? caught))
    (check (str label ": requires close")
           true (:jdbc/close-required (ex-data caught)))
    (check (str label ": retains cleanup cause")
           true (identical? cleanup-error (ex-cause caught)))))

(defn check-subsequent-transaction! [label conn]
  (let [calls (atom [])
        ret (with-redefs
              [jdbc/execute!
               (fn [_conn sql & _opts]
                 (swap! calls conj sql)
                 nil)]
              (jdbc/atomic-apply conn (fn [_] 42)))]
    (check (str label ": next transaction returns") 42 ret)
    (check (str label ": next transaction completes")
           ["BEGIN" "COMMIT"] @calls)
    (check (str label ": connection remains ready")
           true (transaction-ready? conn))
    (check (str label ": prior cleanup context clears")
           [] (jdbc/transaction-cleanup-errors conn))))

(defn capture-pending-operations [conn later-atomic-body-ran?]
  (let [fetch-outcome
        (capture #(jdbc/fetch conn "select * from pending_probe"))
        execute-outcome
        (capture
          #(jdbc/execute! conn "insert into pending_probe values (2)"))
        last-id-outcome
        (capture #(jdbc/last-insert-id conn))
        rollback-outcome
        (capture #(jdbc/set-rollback! conn))
        atomic-outcome
        (capture
          #(jdbc/atomic-apply
             conn
             (fn [nested]
               (reset! later-atomic-body-ran? true)
               (jdbc/execute!
                 nested
                 "insert into pending_probe values (3)"))))]
    {:fetch fetch-outcome
     :execute execute-outcome
     :last-id last-id-outcome
     :set-rollback rollback-outcome
     :atomic atomic-outcome}))

(defn check-pending-cleanup-window! []
  (println "mixed real-sqlite nested cleanup window")
  (with-open [pending-conn (jdbc/connection "sqlite::memory:")]
    (jdbc/execute! pending-conn
                   "create table pending_probe (x integer)")
    (let [events (atom [])
          real-execute jdbc/execute!
          release-attempts (atom 0)
          body-error (ex-info "nested body failed" {:kind :body})
          release-error (ex-info "savepoint release failed" {:kind :cleanup})
          caught-inner (atom nil)
          state-during-window (atom nil)
          context-during-window (atom nil)
          operation-outcomes (atom nil)
          later-atomic-body-ran? (atom false)
          outer-error
          (with-redefs
            [jdbc/execute!
             (fn [c sql & opts]
               (swap! events conj [:attempt sql])
               (if (and (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                        (= 1 (swap! release-attempts inc)))
                 (throw release-error)
                 (let [ret (apply real-execute c sql opts)]
                   (swap! events conj [:completed sql])
                   ret)))]
            (thrown
              #(jdbc/atomic-apply
                 pending-conn
                 (fn [outer]
                   (try
                     (jdbc/atomic-apply
                       outer
                       (fn [inner]
                         (jdbc/execute!
                           inner
                           "insert into pending_probe values (1)")
                         (throw body-error)))
                     (catch Throwable t
                       (reset! caught-inner t)
                       (reset! state-during-window @(:tx-state outer))
                       (reset! context-during-window
                               (jdbc/transaction-cleanup-errors outer))
                       (reset!
                         operation-outcomes
                         (capture-pending-operations
                           outer
                           later-atomic-body-ran?))))
                   :must-not-commit))))]
      (check "pending window preserves inner body throwable"
             true (identical? body-error @caught-inner))
      (check "pending window is explicit before outer recovery"
             true (:pending-cleanup? @state-during-window))
      (check "pending window retains cleanup context"
             [:release-after-rollback
              "RELEASE SAVEPOINT jdbc_sp_1"
              true]
             (let [context (first @context-during-window)]
               [(:phase context)
                (:sql context)
                (identical? release-error (:error context))]))
      (doseq [operation [:fetch :execute :last-id :set-rollback :atomic]]
        (check (str "pending window blocks " (name operation))
               true
               (pending-cleanup-error?
                 (:error (get @operation-outcomes operation)))))
      (check "pending window blocks later atomic body"
             false @later-atomic-body-ran?)
      (check "pending window reaches only internal outer rollback"
             [[:attempt "BEGIN"]
              [:completed "BEGIN"]
              [:attempt "SAVEPOINT jdbc_sp_1"]
              [:completed "SAVEPOINT jdbc_sp_1"]
              [:attempt "insert into pending_probe values (1)"]
              [:completed "insert into pending_probe values (1)"]
              [:attempt "ROLLBACK TO SAVEPOINT jdbc_sp_1"]
              [:completed "ROLLBACK TO SAVEPOINT jdbc_sp_1"]
              [:attempt "RELEASE SAVEPOINT jdbc_sp_1"]
              [:attempt "insert into pending_probe values (2)"]
              [:attempt "ROLLBACK"]
              [:completed "ROLLBACK"]]
             @events)
      (check "pending window outer error reports cleanup"
             "transaction cleanup failed" (.getMessage outer-error))
      (check "pending window outer error retains cleanup cause"
             true (identical? release-error (ex-cause outer-error)))
      (check "pending window outer rollback restores readiness"
             true (transaction-ready? pending-conn))
      (check "pending window outer rollback removes real rows"
             [] (jdbc/fetch pending-conn
                            "select * from pending_probe")))))

(defn check-pending-rollback-failure-window! []
  (println "nested rollback-to cleanup window")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [calls (atom [])
          completion-error
          (ex-info "nested completion failed" {:kind :completion})
          cleanup-error
          (ex-info "nested rollback-to failed" {:kind :cleanup})
          caught-inner (atom nil)
          state-during-window (atom nil)
          blocked-fetch (atom nil)
          outer-error
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (cond
                 (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                 (throw completion-error)

                 (= sql "ROLLBACK TO SAVEPOINT jdbc_sp_1")
                 (throw cleanup-error)

                 :else nil))]
            (thrown
              #(jdbc/atomic-apply
                 conn
                 (fn [outer]
                   (try
                     (jdbc/atomic-apply outer (fn [_] :inner-ok))
                     (catch Throwable t
                       (reset! caught-inner t)
                       (reset! state-during-window @(:tx-state outer))
                       (reset! blocked-fetch
                               (thrown #(jdbc/fetch outer "select 1")))))
                   :must-not-commit))))]
      (check "rollback-to window preserves completion throwable"
             true (identical? completion-error @caught-inner))
      (check "rollback-to window is explicit before outer recovery"
             true (:pending-cleanup? @state-during-window))
      (check "rollback-to window blocks fetch"
             true (pending-cleanup-error? @blocked-fetch))
      (check "rollback-to window retains cleanup cause"
             true (identical? cleanup-error (ex-cause @blocked-fetch)))
      (check "rollback-to window reaches encompassing rollback"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "ROLLBACK"]
             @calls)
      (check "rollback-to window outer error retains cleanup cause"
             true (identical? cleanup-error (ex-cause outer-error)))
      (check "rollback-to window outer rollback restores readiness"
             true (transaction-ready? conn)))))

(defn- native-destructor-spy [real-destroy calls destroyed?]
  (fn [ptr]
    (swap! calls inc)
    (if (compare-and-set! destroyed? false true)
      (real-destroy ptr)
      ;; Report repeat attempts without passing a retired pointer to SQLite.
      0)))

(defn- cleanup-native-once! [real-destroy destroyed? ptr]
  (when (and (some? ptr)
             (not (ffi/null? ptr))
             (compare-and-set! destroyed? false true))
    (real-destroy ptr)))

(defn- capturing-prepare [real-prepare stmt-ref]
  (fn [db sql n pp tail]
    (let [rc (real-prepare db sql n pp tail)]
      (reset! stmt-ref (ffi/read pp :pointer))
      rc)))

(defn check-sqlite-ownership! []
  (println "sqlite handle ownership and cleanup")

  (let [h (sqlite/open ":memory:")
        real-close-v2 sqlite/sqlite3-close-v2
        close-calls (atom 0)
        destroyed? (atom false)]
    (try
      (with-redefs [sqlite/sqlite3-close-v2
                    (native-destructor-spy real-close-v2 close-calls destroyed?)]
        (sqlite/close h)
        (sqlite/close h))
      (finally
        (cleanup-native-once! real-close-v2 destroyed? (:ptr h))))
    (check "close is idempotent" 1 @close-calls))

  (let [h (sqlite/open ":memory:")]
    (sqlite/close h)
    (let [query-error (thrown #(sqlite/query h "select 1" []))
          changes-error (thrown #(sqlite/changes h))
          rowid-error (thrown #(sqlite/last-insert-rowid h))]
      (check "use after close: query rejected" true (:db.sqlite/closed (ex-data query-error)))
      (check "use after close: changes rejected" true (:db.sqlite/closed (ex-data changes-error)))
      (check "use after close: last-insert-rowid rejected" true (:db.sqlite/closed (ex-data rowid-error)))
      (check "closing an already-closed handle does not throw" nil (thrown #(sqlite/close h)))))

  (let [primary (ex-info "sqlite initialization failed" {:kind :primary})
        cleanup (ex-info "sqlite initialization cleanup failed" {:kind :cleanup})
        real-open sqlite/open
        real-close sqlite/close
        opened (atom nil)
        close-calls (atom 0)
        destroyed? (atom false)]
    (try
      (with-redefs [sqlite/open
                    (fn [path]
                      (let [h (real-open path)]
                        (reset! opened h)
                        h))
                    sqlite/query
                    (fn [_handle _sql _params] (throw primary))
                    sqlite/close
                    (fn [handle]
                      (swap! close-calls inc)
                      (when (compare-and-set! destroyed? false true)
                        (real-close handle))
                      (throw cleanup))]
        (let [err (thrown #(jdbc/connection "sqlite::memory:"))]
          (check "connection initialization preserves its primary throwable"
                 true
                 (identical? primary err))))
      (finally
        (when-let [handle @opened]
          (when (compare-and-set! destroyed? false true)
            (real-close handle)))))
    (check "connection initialization failure closes its handle exactly once"
           1
           @close-calls))

  (let [h (sqlite/open ":memory:")
        real-close-v2 sqlite/sqlite3-close-v2
        close-calls (atom 0)]
    (with-redefs [sqlite/sqlite3-close-v2
                  (fn [_ptr] (swap! close-calls inc) 21)]
      (let [err (thrown #(sqlite/close h))]
        (check "close failure is surfaced" 21 (:rc (ex-data err)))
        (check "failed close still leaves the handle fail-closed"
               true
               (:db.sqlite/closed
                 (ex-data (thrown #(sqlite/changes h)))))
        (check "failed close is not retried through an idempotent close"
               nil
               (thrown #(sqlite/close h)))))
    (check "failed close calls close_v2 exactly once" 1 @close-calls)
    ;; The injected close did not touch SQLite. Retire the raw pointer directly;
    ;; the logical handle deliberately remains closed.
    (real-close-v2 (:ptr h)))

  (let [source (sqlite/open ":memory:")
        db (:ptr source)
        real-close-v2 sqlite/sqlite3-close-v2
        close-calls (atom 0)
        destroyed? (atom false)]
    ;; Transfer the raw pointer to the forced open-failure path so the original
    ;; handle cannot close it a second time.
    (reset! (:closed? source) true)
    (try
      (with-redefs [sqlite/sqlite3-open
                    (fn [_path pp]
                      (ffi/write pp :pointer 0 db)
                      14)
                    sqlite/sqlite3-close-v2
                    (native-destructor-spy real-close-v2 close-calls destroyed?)]
        (let [err (thrown #(sqlite/open "forced-open-failure.db"))]
          (check "open failure with a non-null handle throws" 14 (:rc (ex-data err)))
          (check "open failure closes the non-null handle exactly once" 1 @close-calls)))
      (finally
        (cleanup-native-once! real-close-v2 destroyed? db))))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (fn [db sql n pp tail]
                      (let [rc ((capturing-prepare real-prepare stmt-ref)
                                db sql n pp tail)]
                        (if (= 0 rc) 1 rc)))
                    sqlite/sqlite3-finalize
                    (native-destructor-spy real-finalize finalize-calls destroyed?)]
        (let [err (thrown #(sqlite/query h "select ?" [42]))]
          (check "prepare failure with a non-null statement throws"
                 1
                 (:rc (ex-data err)))
          (check "prepare failure finalizes the non-null statement exactly once"
                 1
                 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        real-step sqlite/sqlite3-step
        step-calls (atom 0)
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-bind-text
                    (fn [_stmt _i _v _n _d] 1)
                    sqlite/sqlite3-step
                    (fn [stmt] (swap! step-calls inc) (real-step stmt))
                    sqlite/sqlite3-finalize
                    (native-destructor-spy real-finalize finalize-calls destroyed?)]
        (let [err (thrown #(sqlite/query h "select ?" ["x"]))]
          (check "bad bind rc is checked and throws" true (some? err))
          (check "bad bind rc prevents step" 0 @step-calls)
          (check "bad bind rc still finalizes exactly once" 1 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-bind-int64
                    (fn [_stmt _i _v] 1)
                    sqlite/sqlite3-finalize
                    (native-destructor-spy real-finalize finalize-calls destroyed?)]
        (let [err (thrown #(sqlite/query h "select ?" [42]))]
          (check "bind failure throws" true (some? err))
          (check "bind failure still finalizes the statement" 1 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-step (fn [_stmt] 1)
                    sqlite/sqlite3-finalize
                    (native-destructor-spy real-finalize finalize-calls destroyed?)]
        (let [err (thrown #(sqlite/query h "select 1" []))]
          (check "step failure throws" true (some? err))
          (check "step failure still finalizes the statement" 1 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)
        safe-finalize (native-destructor-spy real-finalize finalize-calls destroyed?)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-finalize
                    (fn [stmt]
                      (safe-finalize stmt)
                      1)]
        (let [err (thrown #(sqlite/query h "select 1" []))]
          (check "finalize-only failure throws" true (some? err))
          (check "finalize-only failure surfaces the finalize rc" 1 (:rc (ex-data err)))
          (check "finalize-only failure destroys the statement exactly once"
                 1
                 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        primary (ex-info "primary bind failure" {:kind :primary})
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)
        safe-finalize (native-destructor-spy real-finalize finalize-calls destroyed?)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-bind-int64
                    (fn [_stmt _i _v] (throw primary))
                    sqlite/sqlite3-finalize
                    (fn [stmt]
                      (safe-finalize stmt)
                      1)]
        (let [err (thrown #(sqlite/query h "select ?" [42]))]
          (check "finalize failure does not replace the primary query throwable"
                 true
                 (identical? primary err))
          (check "primary failure still finalizes exactly once"
                 1
                 @finalize-calls)))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (sqlite/close h))

  (let [h (sqlite/open ":memory:")
        real-prepare sqlite/sqlite3-prepare
        real-finalize sqlite/sqlite3-finalize
        finalize-calls (atom 0)
        destroyed? (atom false)
        stmt-ref (atom ffi/null)]
    (try
      (with-redefs [sqlite/sqlite3-prepare
                    (capturing-prepare real-prepare stmt-ref)
                    sqlite/sqlite3-finalize
                    (native-destructor-spy real-finalize finalize-calls destroyed?)]
        (check "ordinary query still returns rows"
               [{:x 1}]
               (sqlite/query h "select 1 as x" [])))
      (finally
        (cleanup-native-once! real-finalize destroyed? @stmt-ref)))
    (check "successful query finalizes exactly once" 1 @finalize-calls)
    (sqlite/close h)))

(defn -main [& _]
  (println "jdbc.core over sqlite (:memory:)")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (check "execute! ddl" 0
           (jdbc/execute! conn "create table person (id integer primary key, name text, zip integer)"))
    (check "insert! returns the id" 1 (jdbc/insert! conn :person {:name "ada" :zip 94546}))
    (check "insert-multi! ids" [2 3]
           (jdbc/insert-multi! conn :person [{:name "grace" :zip 94546}
                                             {:name "alan" :zip 10001}]))
    (check "fetch sqlvec with params" [{:id 1 :name "ada" :zip 94546}]
           (jdbc/fetch conn ["select * from person where name = ?" "ada"]))
    (check "fetch-one" {:id 3 :name "alan" :zip 10001}
           (jdbc/fetch-one conn ["select * from person where zip = ?" 10001]))
    (check "fetch plain string" 3
           (count (jdbc/fetch conn "select * from person")))
    (check "update! rows affected" 2
           (jdbc/update! conn :person {:zip 94540} ["zip = ?" 94546]))
    (check "update applied" 2
           (count (jdbc/fetch conn ["select * from person where zip = ?" 94540])))
    (check "delete! rows affected" 1
           (jdbc/delete! conn :person ["name = ?" "alan"]))

    (println "transactions")
    (jdbc/atomic conn
      (jdbc/insert! conn :person {:name "tx" :zip 1}))
    (check "atomic commits" 1
           (count (jdbc/fetch conn ["select * from person where name = ?" "tx"])))
    (check "atomic rolls back on throw" :threw
           (try (jdbc/atomic conn
                  (jdbc/insert! conn :person {:name "boom" :zip 2})
                  (throw (ex-info "no" {})))
                (catch Throwable _ :threw)))
    (check "rollback discarded the insert" 0
           (count (jdbc/fetch conn ["select * from person where name = ?" "boom"])))
    (jdbc/atomic conn
      (jdbc/insert! conn :person {:name "outer" :zip 3})
      (try (jdbc/atomic conn
             (jdbc/insert! conn :person {:name "inner" :zip 4})
             (throw (ex-info "inner-only" {})))
           (catch Throwable _ nil)))
    (check "nested savepoint: outer survives" 1
           (count (jdbc/fetch conn ["select * from person where name = ?" "outer"])))
    (check "nested savepoint: inner rolled back" 0
           (count (jdbc/fetch conn ["select * from person where name = ?" "inner"])))
    (jdbc/atomic conn
      (jdbc/insert! conn :person {:name "marked" :zip 5})
      (jdbc/set-rollback! conn))
    (check "set-rollback! discards the block" 0
           (count (jdbc/fetch conn ["select * from person where name = ?" "marked"])))

    (println "transaction failure paths")
    (let [calls (atom [])
          body-called? (atom false)
          begin-error (ex-info "begin failed" {:phase :begin})
          caught
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (when (= sql "BEGIN") (throw begin-error))
               nil)]
            (thrown
              #(jdbc/atomic-apply
                 conn
                 (fn [_]
                   (reset! body-called? true)
                   :must-not-run))))]
      (check "begin failure remains primary" true
             (identical? begin-error caught))
      (check "begin failure issues no cleanup" ["BEGIN"] @calls)
      (check "begin failure skips the body" false @body-called?)
      (check "begin failure leaves connection ready"
             true (transaction-ready? conn)))

    (let [calls (atom [])
          ret
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               nil)]
            (jdbc/atomic-apply
              conn
              (fn [outer]
                (jdbc/atomic-apply outer (fn [_] :inner-ok))
                :outer-ok)))]
      (check "nested success returns outer result" :outer-ok ret)
      (check "nested success command order"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "COMMIT"]
             @calls)
      (check "nested success leaves connection ready"
             true (transaction-ready? conn)))

    (let [calls (atom [])
          ret
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               nil)]
            (jdbc/atomic-apply
              conn
              (fn [outer]
                (jdbc/atomic-apply
                  outer
                  (fn [inner]
                    (jdbc/set-rollback! inner)
                    :inner-ok))
                :outer-ok)))]
      (check "nested set-rollback returns outer result" :outer-ok ret)
      (check "nested set-rollback is transaction-wide"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK"]
             @calls)
      (check "nested set-rollback leaves connection ready"
             true (transaction-ready? conn)))

    (let [calls (atom [])
          commit-error (ex-info "commit failed" {:phase :commit})
          caught
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (when (= sql "COMMIT") (throw commit-error))
               nil)]
            (thrown #(jdbc/atomic-apply conn (fn [_] :body-ok))))]
      (check "commit failure remains primary" true
             (identical? commit-error caught))
      (check "commit failure attempts one recovery rollback"
             ["BEGIN" "COMMIT" "ROLLBACK"] @calls)
      (check "commit failure leaves transaction idle"
             true (transaction-ready? conn))
      (check "successful recovery adds no cleanup error"
             [] (jdbc/transaction-cleanup-errors conn)))
    (check-subsequent-transaction! "after commit failure" conn)

    (with-open [failed-conn (jdbc/connection "sqlite::memory:")]
      (let [calls (atom [])
            commit-error (ex-info "commit failed" {:phase :commit})
            cleanup-error (ex-info "recovery rollback failed" {:phase :cleanup})
            caught
            (with-redefs
              [jdbc/execute!
               (fn [_conn sql & _opts]
                 (swap! calls conj sql)
                 (cond
                   (= sql "COMMIT") (throw commit-error)
                   (= sql "ROLLBACK") (throw cleanup-error)
                   :else nil))]
              (thrown
                #(jdbc/atomic-apply failed-conn (fn [_] :body-ok))))
            context (first (jdbc/transaction-cleanup-errors failed-conn))]
        (check "commit failure remains primary when recovery fails"
               true (identical? commit-error caught))
        (check "failed commit recovery command order"
               ["BEGIN" "COMMIT" "ROLLBACK"] @calls)
        (check "failed commit recovery poisons the connection"
               {:depth 0
                :rollback? false
                :pending-cleanup? false
                :poisoned? true}
               (transaction-state failed-conn))
        (check "failed commit recovery context"
               [:rollback-after-completion-failure "ROLLBACK" true]
               [(:phase context)
                (:sql context)
                (identical? cleanup-error (:error context))])
        (check-poisoned-operation!
          "after failed commit recovery" failed-conn cleanup-error
          #(jdbc/atomic-apply failed-conn (fn [_] :must-not-run)))))

    (with-open [failed-conn (jdbc/connection "sqlite::memory:")]
      (let [calls (atom [])
            rollback-error (ex-info "rollback-only failed" {:phase :rollback-only})
            caught
            (with-redefs
              [jdbc/execute!
               (fn [_conn sql & _opts]
                 (swap! calls conj sql)
                 (when (= sql "ROLLBACK") (throw rollback-error))
                 nil)]
              (thrown
                #(jdbc/atomic-apply
                   failed-conn
                   (fn [c]
                     (jdbc/set-rollback! c)
                     :body-ok))))
            context (first (jdbc/transaction-cleanup-errors failed-conn))]
        (check "rollback-only failure remains primary" true
               (identical? rollback-error caught))
        (check "rollback-only failure is not attempted twice"
               ["BEGIN" "ROLLBACK"] @calls)
        (check "rollback-only failure poisons the connection"
               {:depth 0
                :rollback? false
                :pending-cleanup? false
                :poisoned? true}
               (transaction-state failed-conn))
        (check "rollback-only cleanup context"
               [:rollback-only "ROLLBACK" true]
               [(:phase context)
                (:sql context)
                (identical? rollback-error (:error context))])
        (check-poisoned-operation!
          "poisoned fetch" failed-conn rollback-error
          #(jdbc/fetch failed-conn "select 1"))
        (check-poisoned-operation!
          "poisoned execute" failed-conn rollback-error
          #(jdbc/execute! failed-conn "select 1"))
        (check-poisoned-operation!
          "poisoned last-id" failed-conn rollback-error
          #(jdbc/last-insert-id failed-conn))
        (check-poisoned-operation!
          "poisoned transaction" failed-conn rollback-error
          #(jdbc/atomic-apply failed-conn (fn [_] :must-not-run)))))

    (with-open [failed-conn (jdbc/connection "sqlite::memory:")]
      (let [calls (atom [])
            body-error (ex-info "body failed" {:kind :body})
            cleanup-error (ex-info "rollback failed" {:kind :cleanup})
            caught
            (with-redefs
              [jdbc/execute!
               (fn [_conn sql & _opts]
                 (swap! calls conj sql)
                 (when (= sql "ROLLBACK") (throw cleanup-error))
                 nil)]
              (thrown
                #(jdbc/atomic-apply failed-conn (fn [_] (throw body-error)))))
            context (first (jdbc/transaction-cleanup-errors failed-conn))]
        (check "body exception identity survives cleanup failure"
               true (identical? body-error caught))
        (check "body exception data survives cleanup failure"
               {:kind :body} (ex-data caught))
        (check "body cleanup failure is attempted once"
               ["BEGIN" "ROLLBACK"] @calls)
        (check "body cleanup failure poisons the connection"
               {:depth 0
                :rollback? false
                :pending-cleanup? false
                :poisoned? true}
               (transaction-state failed-conn))
        (check "body cleanup failure context is retained"
               [:rollback "ROLLBACK" true]
               [(:phase context)
                (:sql context)
                (identical? cleanup-error (:error context))])
        (check-poisoned-operation!
          "after body cleanup failure" failed-conn cleanup-error
          #(jdbc/atomic-apply failed-conn (fn [_] :must-not-run)))))

    (let [calls (atom [])
          inner-error (ex-info "inner body failed" {:kind :inner})
          ret
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               nil)]
            (jdbc/atomic-apply
              conn
              (fn [outer]
                (try
                  (jdbc/atomic-apply
                    outer
                    (fn [_] (throw inner-error)))
                  (catch Throwable t
                    (when-not (identical? inner-error t)
                      (throw t))))
                :outer-ok)))]
      (check "nested rollback lets outer body continue" :outer-ok ret)
      (check "nested rollback releases its savepoint"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "COMMIT"]
             @calls)
      (check "nested rollback leaves transaction idle"
             true (transaction-ready? conn)))

    (let [calls (atom [])
          release-attempts (atom 0)
          completion-error (ex-info "nested completion failed" {:kind :completion})
          caught-inner (atom nil)
          ret
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (when (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                 (when (= 1 (swap! release-attempts inc))
                   (throw completion-error)))
               nil)]
            (jdbc/atomic-apply
              conn
              (fn [outer]
                (try
                  (jdbc/atomic-apply outer (fn [_] :inner-ok))
                  (catch Throwable t
                    (reset! caught-inner t)))
                :outer-ok)))]
      (check "nested completion failure remains primary"
             true (identical? completion-error @caught-inner))
      (check "nested completion recovery lets outer continue"
             :outer-ok ret)
      (check "nested completion recovery command order"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "COMMIT"]
             @calls)
      (check "successful nested completion recovery adds no context"
             [] (jdbc/transaction-cleanup-errors conn))
      (check "successful nested completion recovery leaves connection ready"
             true (transaction-ready? conn)))

    (let [calls (atom [])
          completion-error (ex-info "nested completion failed" {:kind :completion})
          cleanup-error (ex-info "nested recovery failed" {:kind :cleanup})
          caught-inner (atom nil)
          caught
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (cond
                 (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                 (throw completion-error)

                 (= sql "ROLLBACK TO SAVEPOINT jdbc_sp_1")
                 (throw cleanup-error)

                 :else nil))]
            (thrown
              #(jdbc/atomic-apply
                 conn
                 (fn [outer]
                   (try
                     (jdbc/atomic-apply outer (fn [_] :inner-ok))
                     (catch Throwable t
                       (reset! caught-inner t)))
                   :must-not-commit))))
          context (first (jdbc/transaction-cleanup-errors conn))]
      (check "failed nested recovery preserves completion error at inner boundary"
             true (identical? completion-error @caught-inner))
      (check "caught failed nested recovery becomes cleanup exception"
             "transaction cleanup failed" (.getMessage caught))
      (check "caught failed nested recovery retains cleanup cause"
             true (identical? cleanup-error (ex-cause caught)))
      (check "caught failed nested recovery context"
             [{:phase :rollback-after-completion-failure
               :sql "ROLLBACK TO SAVEPOINT jdbc_sp_1"}]
             (:jdbc/transaction-cleanup (ex-data caught)))
      (check "caught failed nested recovery command order"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "ROLLBACK"]
             @calls)
      (check "caught failed nested recovery context is retained"
             [:rollback-after-completion-failure
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              true]
             [(:phase context)
              (:sql context)
              (identical? cleanup-error (:error context))])
      (check "successful encompassing rollback clears poison"
             true (transaction-ready? conn)))
    (check-subsequent-transaction! "after failed nested recovery" conn)

    (let [calls (atom [])
          body-error (ex-info "nested body failed" {:kind :nested-body})
          release-error (ex-info "savepoint release failed" {:kind :cleanup})
          caught
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (when (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                 (throw release-error))
               nil)]
            (thrown
              #(jdbc/atomic-apply
                 conn
                 (fn [outer]
                   (jdbc/atomic-apply
                     outer
                     (fn [_] (throw body-error)))))))
          context (first (jdbc/transaction-cleanup-errors conn))]
      (check "nested body exception survives release cleanup failure"
             true (identical? body-error caught))
      (check "nested cleanup failure rolls back the outer transaction"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK"]
             @calls)
      (check "nested cleanup failure leaves transaction idle"
             true (transaction-ready? conn))
      (check "nested release cleanup context is retained"
             [:release-after-rollback
              "RELEASE SAVEPOINT jdbc_sp_1"
              true]
             [(:phase context)
              (:sql context)
              (identical? release-error (:error context))]))
    (check-subsequent-transaction! "after nested cleanup failure" conn)

    (let [calls (atom [])
          body-error (ex-info "caught nested body failed" {:kind :nested-body})
          release-error (ex-info "caught savepoint release failed" {:kind :cleanup})
          caught
          (with-redefs
            [jdbc/execute!
             (fn [_conn sql & _opts]
               (swap! calls conj sql)
               (when (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                 (throw release-error))
               nil)]
            (thrown
              #(jdbc/atomic-apply
                 conn
                 (fn [outer]
                   (try
                     (jdbc/atomic-apply
                       outer
                       (fn [_] (throw body-error)))
                     (catch Throwable t
                       (when-not (identical? body-error t)
                         (throw t))))
                   :must-not-commit))))]
      (check "caught nested body cannot hide cleanup failure"
             "transaction cleanup failed" (.getMessage caught))
      (check "caught nested cleanup failure retains cleanup cause"
             true (identical? release-error (ex-cause caught)))
      (check "caught nested cleanup failure exposes exact context"
             [{:phase :release-after-rollback
               :sql "RELEASE SAVEPOINT jdbc_sp_1"}]
             (:jdbc/transaction-cleanup (ex-data caught)))
      (check "caught nested cleanup failure is fail-closed"
             ["BEGIN"
              "SAVEPOINT jdbc_sp_1"
              "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1"
              "ROLLBACK"]
             @calls)
      (check "caught nested cleanup failure leaves transaction idle"
             true (transaction-ready? conn)))
    (check-subsequent-transaction! "after caught nested cleanup failure" conn)

    (with-open [failed-conn (jdbc/connection "sqlite::memory:")]
      (let [calls (atom [])
            body-error (ex-info "nested body failed" {:kind :body})
            nested-error (ex-info "nested release failed" {:kind :nested-cleanup})
            outer-error (ex-info "outer rollback failed" {:kind :outer-cleanup})
            caught
            (with-redefs
              [jdbc/execute!
               (fn [_conn sql & _opts]
                 (swap! calls conj sql)
                 (cond
                   (= sql "RELEASE SAVEPOINT jdbc_sp_1") (throw nested-error)
                   (= sql "ROLLBACK") (throw outer-error)
                   :else nil))]
              (thrown
                #(jdbc/atomic-apply
                   failed-conn
                   (fn [outer]
                     (try
                       (jdbc/atomic-apply
                         outer
                         (fn [_] (throw body-error)))
                       (catch Throwable t
                         (when-not (identical? body-error t)
                           (throw t))))
                     :must-not-commit))))
            errors (jdbc/transaction-cleanup-errors failed-conn)]
        (check "multiple cleanup failures retain first cause"
               true (identical? nested-error (ex-cause caught)))
        (check "multiple cleanup failure exception context is ordered"
               [{:phase :release-after-rollback
                 :sql "RELEASE SAVEPOINT jdbc_sp_1"}
                {:phase :rollback-after-cleanup-failure
                 :sql "ROLLBACK"}]
               (:jdbc/transaction-cleanup (ex-data caught)))
        (check "multiple cleanup failure command order"
               ["BEGIN"
                "SAVEPOINT jdbc_sp_1"
                "ROLLBACK TO SAVEPOINT jdbc_sp_1"
                "RELEASE SAVEPOINT jdbc_sp_1"
                "ROLLBACK"]
               @calls)
        (check "failed encompassing rollback poisons connection"
               {:depth 0
                :rollback? false
                :pending-cleanup? false
                :poisoned? true}
               (transaction-state failed-conn))
        (check "multiple cleanup errors retain throwable order"
               [true true]
               [(identical? nested-error (:error (first errors)))
                (identical? outer-error (:error (second errors)))])
        (check-poisoned-operation!
          "after failed encompassing rollback" failed-conn nested-error
          #(jdbc/fetch failed-conn "select 1")))))

  (check-pending-cleanup-window!)
  (check-pending-rollback-failure-window!)

  (println "mixed real-sqlite cleanup failure")
  (with-open [failed-conn (jdbc/connection "sqlite::memory:")]
    (jdbc/execute! failed-conn "create table poison_probe (x integer)")
    (let [calls (atom [])
          real-execute jdbc/execute!
          body-error (ex-info "real body failed" {:kind :body})
          cleanup-error (ex-info "ROLLBACK did not reach SQLite" {:kind :cleanup})
          caught
          (with-redefs
            [jdbc/execute!
             (fn [c sql & _opts]
               (swap! calls conj sql)
               (if (= sql "ROLLBACK")
                 (throw cleanup-error)
                 (real-execute c sql)))]
            (thrown
              #(jdbc/atomic-apply
                 failed-conn
                 (fn [c]
                   (jdbc/execute! c "insert into poison_probe values (1)")
                   (throw body-error)))))
          context (first (jdbc/transaction-cleanup-errors failed-conn))
          query-error (thrown #(jdbc/fetch failed-conn "select * from poison_probe"))
          next-error (thrown
                       #(jdbc/atomic-apply
                          failed-conn
                          (fn [_] :must-not-run)))]
      (check "mixed sqlite failure preserves body throwable"
             true (identical? body-error caught))
      (check "mixed sqlite failure reached real begin and body SQL only"
             ["BEGIN"
              "insert into poison_probe values (1)"
              "ROLLBACK"]
             @calls)
      (check "mixed sqlite failure poisons logical state"
             {:depth 0
              :rollback? false
              :pending-cleanup? false
              :poisoned? true}
             (transaction-state failed-conn))
      (check "mixed sqlite cleanup context"
             [:rollback "ROLLBACK" true]
             [(:phase context)
              (:sql context)
              (identical? cleanup-error (:error context))])
      (check "mixed sqlite ordinary query is rejected"
             true (poisoned-error? query-error))
      (check "mixed sqlite query retains cleanup cause"
             true (identical? cleanup-error (ex-cause query-error)))
      (check "mixed sqlite next transaction is rejected before BEGIN"
             true (poisoned-error? next-error))
      (check "mixed sqlite next transaction retains cleanup context"
             [{:phase :rollback :sql "ROLLBACK"}]
             (:jdbc/transaction-cleanup (ex-data next-error)))))

  (check-sqlite-ownership!)

  (println "dbspec parsing")
  (check "map spec works" 1
         (with-open [c (jdbc/connection {:vendor "sqlite" :name ":memory:"})]
           (jdbc/execute! c "create table t (x integer)")
           (jdbc/insert! c :t {:x 7})))

  (when-let [pg-uri (System/getenv "JOLT_TEST_PG_URI")]
    (println "jdbc.core over postgres (" pg-uri ")")
    (with-open [conn (jdbc/connection pg-uri)]
      (jdbc/execute! conn "drop table if exists jolt_person")
      (jdbc/execute! conn "create table jolt_person (id serial primary key, name text, zip integer)")
      (check "pg insert! returns the id" 1 (jdbc/insert! conn :jolt_person {:name "ada" :zip 94546}))
      (check "pg fetch with ? params" [{:id 1 :name "ada" :zip 94546}]
             (jdbc/fetch conn ["select * from jolt_person where name = ?" "ada"]))
      (jdbc/insert! conn :jolt_person {:name "grace" :zip 94546})
      (jdbc/update! conn :jolt_person {:zip 94540} ["zip = ?" 94546])
      (check "pg update applied" 2
             (count (jdbc/fetch conn ["select * from jolt_person where zip = ?" 94540])))
      (jdbc/atomic conn
        (jdbc/insert! conn :jolt_person {:name "tx" :zip 1}))
      (check "pg atomic commits" 1
             (count (jdbc/fetch conn ["select * from jolt_person where name = ?" "tx"])))
      (check "pg atomic rolls back" :threw
             (try (jdbc/atomic conn
                    (jdbc/insert! conn :jolt_person {:name "boom" :zip 2})
                    (throw (ex-info "no" {})))
                  (catch Throwable _ :threw)))
      (check "pg rollback discarded" 0
             (count (jdbc/fetch conn ["select * from jolt_person where name = ?" "boom"])))
      (jdbc/execute! conn "drop table jolt_person")))

  (if (pos? @failures)
    (throw (ex-info "test failures" {:n @failures}))
    (println "all" @checks "checks passed")))
