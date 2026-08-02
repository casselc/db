(ns jdbc.tx-depth-probe-test
  "Slice-1 probes revalidating the reported transaction-depth/double-transition
  bug against live db main@41da91e under the Jolt v0.5.17 launcher.

  Probes are deliberately minimal and executable. Where a check encodes a
  safety property the live code violates, the check FAILS and is the smallest
  failing characterization of that defect. Where a check encodes the current
  contract, it passes and acts as a regression tripwire.

  P0  sqlite3_get_autocommit binding (physical ground truth)
  P1  begin-boundary recovery contract matrix (prove clean or poison), plus a
      legacy-path control witness showing the old fail-open divergence
  P2  nested SAVEPOINT reported as failed after the savepoint was created
  P3  completion-path trace matrix (exactly one completion per begun boundary)
  P4  reentrant depth bookkeeping (samples never negative, returns to zero)
  P5  driver-level multi-statement truncation (sqlite3_prepare_v2 pzTail null)
      — out of this slice's scope; its two checks remain intended tripwire
      failures, so the suite still exits nonzero with exactly 2 failures.

  Run: jolt -A:test -m jdbc.tx-depth-probe-test
  (-M:test -m <this-ns> silently runs the alias's -m jdbc.core-test instead —
  the baseline suite — because the alias :main-opts consume trailing args.)"
  (:require [jdbc.core :as jdbc]
            [db.sqlite :as sqlite]))

(def failures (atom 0))
(def checks (atom 0))

(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn thrown [f]
  (try (f) nil (catch Throwable t t)))

(defn capture [f]
  (try {:value (f)} (catch Throwable t {:error t})))

(defn transaction-state [conn]
  (select-keys @(:tx-state conn) [:depth :rollback? :pending-cleanup? :poisoned?]))

(defn transaction-ready? [conn]
  (= {:depth 0 :rollback? false :pending-cleanup? false :poisoned? false}
     (transaction-state conn)))

(defn poisoned-error? [t]
  (true? (:jdbc/transaction-poisoned (ex-data t))))

(defn physical-tx-state
  "Non-mutating physical probe: a raw BEGIN either errors (connection is
  physically inside a transaction; error is non-mutating) or succeeds
  (connection was idle; probe immediately rolls back to restore idle)."
  [conn]
  (let [outcome (capture #(sqlite/query (:handle conn) "BEGIN" []))]
    (if (:error outcome)
      {:state :inside :error (:error outcome)}
      (do (sqlite/query (:handle conn) "ROLLBACK" [])
          {:state :idle}))))

;;; P0 — sqlite3_get_autocommit binding (physical ground truth).

(defn probe-p0-autocommit-binding! []
  (println "P0 sqlite3_get_autocommit binding")
  (let [h (sqlite/open ":memory:")]
    (try
      (check "P0: autocommit after open" 1 (sqlite/get-autocommit h))
      (sqlite/query h "BEGIN" [])
      (check "P0: autocommit inside explicit BEGIN" 0 (sqlite/get-autocommit h))
      (sqlite/query h "COMMIT" [])
      (check "P0: autocommit after COMMIT" 1 (sqlite/get-autocommit h))
      (sqlite/query h "BEGIN" [])
      (sqlite/query h "ROLLBACK" [])
      (check "P0: autocommit after ROLLBACK" 1 (sqlite/get-autocommit h))
      (finally (sqlite/close h)))))

;;; P1 — begin-boundary recovery contract (prove clean or poison).
;;;
;;; C1/C2 are the converted original probe-p1-begin-divergence! checks: the
;;; two properties that failed under the old unverified begin (next
;;; transaction succeeds; physical idle at logical depth zero) are now
;;; passing regression checks. C3-C5 cover the fail-closed branches. The
;;; legacy-path control witness retains the old behavior as a discriminating
;;; buggy control.

(defn probe-p1-begin-recovery! []
  (println "P1 begin-boundary recovery (prove clean or poison)")
  ;; C1: BEGIN reported failed before executing; autocommit proves idle.
  (println "P1/C1 begin failure before transition")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          injected (ex-info "begin reported failed pre-transition" {:kind :injected})
          events (atom [])
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (swap! events conj [:attempt sql])
                                 (when (= sql "BEGIN") (throw injected))
                                 (let [ret (apply real-execute c sql opts)]
                                   (swap! events conj [:completed sql])
                                   ret))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C1: original begin error primary" true (identical? injected caught))
      (check "C1: no counter-rollback issued" [[:attempt "BEGIN"]] @events)
      (check "C1: connection ready after proven-clean failure" true (transaction-ready? conn))
      (check "C1: next transaction succeeds" :recovered
             (:value (capture #(jdbc/atomic-apply conn (fn [_] :recovered)))))
      (check "C1: physical idle" :idle (:state (physical-tx-state conn)))))
  ;; C2: BEGIN physically completed, then reported failed. Counter-rollback
  ;; plus a final autocommit != 0 observation proves clean — the two checks
  ;; that FAILed under the old code now pass.
  (println "P1/C2 begin failure after transition, recovery proves clean")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          injected (ex-info "begin reported failed post-transition" {:kind :injected})
          events (atom [])
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (swap! events conj [:attempt sql])
                                 (let [ret (apply real-execute c sql opts)]
                                   (swap! events conj [:completed sql])
                                   (when (= sql "BEGIN") (throw injected))
                                   ret))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C2: original begin error primary" true (identical? injected caught))
      (check "C2: exactly one counter-rollback issued"
             [[:attempt "BEGIN"] [:completed "BEGIN"]
              [:attempt "ROLLBACK"] [:completed "ROLLBACK"]] @events)
      (check "C2: connection ready after proven-clean recovery" true (transaction-ready? conn))
      (check "C2: next transaction succeeds" :recovered
             (:value (capture #(jdbc/atomic-apply conn (fn [_] :recovered)))))
      (check "C2: physical idle" :idle (:state (physical-tx-state conn)))))
  ;; C3: counter-rollback fails — cannot prove clean, fail closed.
  (println "P1/C3 counter-rollback failure poisons")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          begin-error (ex-info "begin post-transition failure" {:kind :begin})
          rollback-error (ex-info "counter-rollback failed" {:kind :cleanup})
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (when (= sql "ROLLBACK") (throw rollback-error))
                                 (let [ret (apply real-execute c sql opts)]
                                   (when (= sql "BEGIN") (throw begin-error))
                                   ret))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C3: begin error primary" true (identical? begin-error caught))
      (check "C3: connection poisoned" true (:poisoned? (transaction-state conn)))
      (check "C3: rollback failure recorded"
             [:begin-rollback "ROLLBACK" true]
             (let [e (first (jdbc/transaction-cleanup-errors conn))]
               [(:phase e) (:sql e) (identical? rollback-error (:error e))]))
      (check "C3: subsequent op rejected poisoned" true
             (poisoned-error? (thrown #(jdbc/fetch conn "select 1"))))
      ;; Physical transaction is still open; clean up below the API so
      ;; with-open close is deterministic.
      (capture #(sqlite/query (:handle conn) "ROLLBACK" []))))
  ;; C4a: pre-probe unavailable — BEGIN never issued, fail closed.
  (println "P1/C4a pre-probe failure poisons before BEGIN")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [probe-error (ex-info "autocommit probe unavailable" {:kind :probe})
          events (atom [])
          real-execute jdbc/execute!
          caught (with-redefs [sqlite/get-autocommit (fn [_] (throw probe-error))
                               jdbc/execute!
                               (fn [c sql & opts]
                                 (swap! events conj [:attempt sql])
                                 (apply real-execute c sql opts))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C4a: probe error primary" true (identical? probe-error caught))
      (check "C4a: BEGIN never attempted" [] @events)
      (check "C4a: connection poisoned" true (:poisoned? (transaction-state conn)))
      (check "C4a: pre-probe failure recorded" :begin-pre-probe
             (:phase (first (jdbc/transaction-cleanup-errors conn))))))
  ;; C4b: post-probe unavailable after a completed BEGIN — fail closed,
  ;; begin error stays primary.
  (println "P1/C4b post-probe failure poisons after begin failure")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-ac sqlite/get-autocommit
          calls (atom 0)
          probe-error (ex-info "post probe unavailable" {:kind :probe})
          begin-error (ex-info "begin post-transition failure" {:kind :begin})
          real-execute jdbc/execute!
          caught (with-redefs [sqlite/get-autocommit
                               (fn [h] (if (= 1 (swap! calls inc))
                                         (real-ac h)
                                         (throw probe-error)))
                               jdbc/execute!
                               (fn [c sql & opts]
                                 (let [ret (apply real-execute c sql opts)]
                                   (when (= sql "BEGIN") (throw begin-error))
                                   ret))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C4b: begin error primary" true (identical? begin-error caught))
      (check "C4b: connection poisoned" true (:poisoned? (transaction-state conn)))
      (check "C4b: post-probe failure recorded" :begin-post-probe
             (:phase (first (jdbc/transaction-cleanup-errors conn))))
      (capture #(sqlite/query (:handle conn) "ROLLBACK" []))))
  ;; C5: physical transaction already open at logical depth zero (legacy
  ;; divergence state) — poison before BEGIN, leave the unknown transaction
  ;; untouched.
  (println "P1/C5 pre-existing physical transaction poisons before BEGIN")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (sqlite/query (:handle conn) "BEGIN" [])
    (let [events (atom [])
          real-execute jdbc/execute!
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (swap! events conj [:attempt sql])
                                 (apply real-execute c sql opts))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "C5: divergence rejected before BEGIN" true (poisoned-error? caught))
      (check "C5: BEGIN never attempted" [] @events)
      (check "C5: connection poisoned" true (:poisoned? (transaction-state conn)))
      (check "C5: precondition recorded" :begin-precondition
             (:phase (first (jdbc/transaction-cleanup-errors conn))))
      (check "C5: pre-existing transaction left in place" :inside
             (:state (physical-tx-state conn)))
      (sqlite/query (:handle conn) "ROLLBACK" [])))
  ;; C6: retained context from a resolved prior attempt must not mask a
  ;; fresh poison cause (independent-review scenario).
  (println "P1/C6 stale cleanup context cannot mask fresh poison cause")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          old-error (ex-info "old release failure" {:kind :old})
          ;; Seed retained context: nested cleanup (RELEASE) fails once, the
          ;; encompassing outer rollback succeeds, so the old error is
          ;; retained with pending/poison clear.
          seeded-error (with-redefs [jdbc/execute!
                                     (fn [c sql & opts]
                                       (if (= sql "RELEASE SAVEPOINT jdbc_sp_1")
                                         (throw old-error)
                                         (apply real-execute c sql opts)))]
                         (thrown
                           #(jdbc/atomic-apply conn
                                               (fn [c]
                                                 (jdbc/atomic-apply c (fn [_] (throw (ex-info "body" {}))))))))
          seeded (jdbc/transaction-cleanup-errors conn)
          seeded-ready (transaction-ready? conn)
          begin-error (ex-info "fresh begin failure" {:kind :begin})
          rollback-error (ex-info "fresh rollback failure" {:kind :cleanup})
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (cond
                                   (= sql "ROLLBACK") (throw rollback-error)
                                   (= sql "BEGIN") (let [ret (apply real-execute c sql opts)]
                                                     (throw begin-error))
                                   :else (apply real-execute c sql opts)))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))
          later (thrown #(jdbc/fetch conn "select 1"))]
      (check "C6: seed left resolved connection" true seeded-ready)
      (check "C6: prior context seeded and retained"
             [:release-after-rollback true]
             [(:phase (first seeded)) (identical? old-error (:error (first seeded)))])
      (check "C6: fresh begin error primary" true (identical? begin-error caught))
      (check "C6: fresh attempt's record replaces retained context"
             [:begin-rollback "ROLLBACK" 1]
             (let [es (jdbc/transaction-cleanup-errors conn)]
               [(:phase (first es)) (:sql (first es)) (count es)]))
      (check "C6: poison cause is the fresh rollback error" true
             (identical? rollback-error (ex-cause later)))
      (capture #(sqlite/query (:handle conn) "ROLLBACK" [])))))

(defn probe-p1-control-legacy-path! []
  (println "P1/control legacy unverified begin remains fail-open (buggy witness)")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          injected (ex-info "begin post-transition failure" {:kind :injected})
          outcomes (with-redefs [jdbc/verified-sqlite-begin!
                                 (fn [c _state] (jdbc/execute! c "BEGIN"))
                                 jdbc/execute!
                                 (fn [c sql & opts]
                                   (let [ret (apply real-execute c sql opts)]
                                     (when (= sql "BEGIN") (throw injected))
                                     ret))]
                     {:caught (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run)))
                      :ready (transaction-ready? conn)
                      :next (capture #(jdbc/atomic-apply conn (fn [_] :recovered)))
                      :physical (:state (physical-tx-state conn))})]
      (check "control: legacy path surfaces begin error" true (identical? injected (:caught outcomes)))
      (check "control: legacy logical state reports ready" true (:ready outcomes))
      (check "control: legacy next transaction fails (divergence)" true
             (some? (:error (:next outcomes))))
      (check "control: legacy physical boundary still open" :inside (:physical outcomes))
      (capture #(sqlite/query (:handle conn) "ROLLBACK" [])))))

;;; P2 — nested savepoint begin: failure reported AFTER the savepoint exists.

(defn probe-p2-nested-begin-divergence! []
  (println "P2 nested savepoint begin post-success failure")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (jdbc/execute! conn "create table p2_probe (x integer)")
    (let [real-execute jdbc/execute!
          injected (ex-info "simulated post-savepoint driver failure" {:kind :injected})
          events (atom [])
          nested-caught (atom nil)
          outer (with-redefs [jdbc/execute!
                              (fn [c sql & opts]
                                (swap! events conj [:attempt sql])
                                (let [ret (apply real-execute c sql opts)]
                                  (swap! events conj [:completed sql])
                                  (when (= sql "SAVEPOINT jdbc_sp_1") (throw injected))
                                  ret))]
                  (capture
                   #(jdbc/atomic-apply conn
                                       (fn [c]
                                         (jdbc/execute! c "insert into p2_probe values (1)")
                                         (reset! nested-caught
                                                 (thrown #(jdbc/atomic-apply c (fn [_] :nested))))
                                         :outer-done))))]
      (check "P2: nested begin failure caught by user body"
             true (identical? injected @nested-caught))
      (check "P2: outer transaction completes" :outer-done (:value outer))
      (check "P2: logical state ready after outer completion" true (transaction-ready? conn))
      (check "P2: physical idle after outer COMMIT clears savepoint"
             :idle (:state (physical-tx-state conn)))
      (check "P2: committed row visible" [{:x 1}] (jdbc/fetch conn "select x from p2_probe"))
      (println "  note P2: trace" (pr-str @events)))))

;;; P3 — completion trace matrix: exactly one completion per begun boundary.

(defn- traced []
  (let [events (atom [])
        real-execute jdbc/execute!]
    [events
     (fn [c sql & opts]
       (swap! events conj [:attempt sql])
       (let [ret (apply real-execute c sql opts)]
         (swap! events conj [:completed sql])
         ret))]))

(defn- completed-trace [events]
  (mapv second (filter #(= :attempt (first %)) events)))

(defn probe-p3-trace-matrix! []
  (println "P3 completion trace matrix")
  ;; S1 outer success
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [[events traced-execute!] (traced)
          ret (with-redefs [jdbc/execute! traced-execute!]
                (jdbc/atomic-apply conn (fn [_] 42)))]
      (check "P3/S1: returns" 42 ret)
      (check "P3/S1: exactly one BEGIN then one COMMIT" ["BEGIN" "COMMIT"] (completed-trace @events))
      (check "P3/S1: ready" true (transaction-ready? conn))))
  ;; S2 body throws
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [[events traced-execute!] (traced)
          body-error (ex-info "body" {})
          caught (with-redefs [jdbc/execute! traced-execute!]
                   (thrown #(jdbc/atomic-apply conn (fn [_] (throw body-error)))))]
      (check "P3/S2: body throwable primary" true (identical? body-error caught))
      (check "P3/S2: exactly one BEGIN then one ROLLBACK" ["BEGIN" "ROLLBACK"] (completed-trace @events))
      (check "P3/S2: ready" true (transaction-ready? conn))))
  ;; S3 rollback-only
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [[events traced-execute!] (traced)
          ret (with-redefs [jdbc/execute! traced-execute!]
                (jdbc/atomic-apply conn (fn [c] (jdbc/set-rollback! c) 7)))]
      (check "P3/S3: returns body value" 7 ret)
      (check "P3/S3: exactly one BEGIN then one ROLLBACK" ["BEGIN" "ROLLBACK"] (completed-trace @events))
      (check "P3/S3: ready" true (transaction-ready? conn))))
  ;; S4 nested success
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [[events traced-execute!] (traced)
          ret (with-redefs [jdbc/execute! traced-execute!]
                (jdbc/atomic-apply conn (fn [c] (jdbc/atomic-apply c (fn [_] :nested)) :outer)))]
      (check "P3/S4: returns" :outer ret)
      (check "P3/S4: one completion per boundary"
             ["BEGIN" "SAVEPOINT jdbc_sp_1" "RELEASE SAVEPOINT jdbc_sp_1" "COMMIT"]
             (completed-trace @events))
      (check "P3/S4: ready" true (transaction-ready? conn))))
  ;; S5 nested body throws, user catches inside outer body, outer commits
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [[events traced-execute!] (traced)
          ret (with-redefs [jdbc/execute! traced-execute!]
                (jdbc/atomic-apply conn
                                   (fn [c]
                                     (thrown #(jdbc/atomic-apply c (fn [_] (throw (ex-info "nested" {})))))
                                     :outer)))]
      (check "P3/S5: returns" :outer ret)
      (check "P3/S5: nested rollback-to plus release, single outer commit"
             ["BEGIN" "SAVEPOINT jdbc_sp_1" "ROLLBACK TO SAVEPOINT jdbc_sp_1"
              "RELEASE SAVEPOINT jdbc_sp_1" "COMMIT"]
             (completed-trace @events))
      (check "P3/S5: ready" true (transaction-ready? conn))))
  ;; S6 COMMIT reported failed before executing; cleanup rollback follows
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          commit-error (ex-info "commit failed" {:kind :cleanup})
          ev2 (atom [])
          caught2 (with-redefs [jdbc/execute!
                                (fn [c sql & opts]
                                  (swap! ev2 conj [:attempt sql])
                                  (when (= sql "COMMIT") (throw commit-error))
                                  (let [ret (apply real-execute c sql opts)]
                                    (swap! ev2 conj [:completed sql])
                                    ret))]
                    (thrown #(jdbc/atomic-apply conn (fn [_] 1))))]
      (check "P3/S6: completion throwable primary" true (identical? commit-error caught2))
      (check "P3/S6: commit attempt then cleanup rollback"
             [[:attempt "BEGIN"] [:completed "BEGIN"]
              [:attempt "COMMIT"]
              [:attempt "ROLLBACK"] [:completed "ROLLBACK"]]
             @ev2)
      (check "P3/S6: ready after successful cleanup rollback" true (transaction-ready? conn)))))

;;; P4 — reentrant depth bookkeeping under mixed success/failure.

(defn probe-p4-depth-bookkeeping! []
  (println "P4 reentrant depth bookkeeping")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [samples (atom [])
          sample! (fn [c tag] (swap! samples conj [tag (:depth @(:tx-state c))]))
          ret (jdbc/atomic-apply conn
                                 (fn [c1]
                                   (sample! c1 :outer)
                                   (thrown
                                    #(jdbc/atomic-apply c1
                                                        (fn [c2]
                                                          (sample! c2 :mid)
                                                          (jdbc/atomic-apply c2
                                                                             (fn [c3]
                                                                               (sample! c3 :inner)
                                                                               (throw (ex-info "inner boom" {})))))))
                                   (jdbc/atomic-apply c1 (fn [c2] (sample! c2 :mid-again) :ok))
                                   :done))]
      (check "P4: outer completes after caught nested failure" :done ret)
      (check "P4: depth samples ascend per boundary"
             [[:outer 1] [:mid 2] [:inner 3] [:mid-again 2]] @samples)
      (check "P4: depth returns to zero, conn ready" true (transaction-ready? conn)))))

;;; P5 — driver multi-statement truncation (sqlite3_prepare_v2 with null pzTail).

(defn probe-p5-multistatement-truncation! []
  (println "P5 multi-statement SQL truncation")
  (let [h (sqlite/open ":memory:")]
    (try
      (check "P5: compound create+insert runs without error"
             [] (sqlite/query h "create table pz (x integer); insert into pz values (1)" []))
      (check "P5: rows inserted by the second statement"
             [{:n 1}] (sqlite/query h "select count(*) as n from pz" []))
      (check "P5: insert with trailing select executes silently"
             [] (sqlite/query h "insert into pz values (2); select 99 as ignored" []))
      (check "P5: trailing select result is dropped, insert applied"
             [{:n 2}] (sqlite/query h "select count(*) as n from pz" []))
      (finally (sqlite/close h)))))

(defn -main [& _]
  (probe-p0-autocommit-binding!)
  (probe-p1-begin-recovery!)
  (probe-p1-control-legacy-path!)
  (probe-p2-nested-begin-divergence!)
  (probe-p3-trace-matrix!)
  (probe-p4-depth-bookkeeping!)
  (probe-p5-multistatement-truncation!)
  (if (pos? @failures)
    (throw (ex-info "tx-depth probe failures" {:n @failures :checks @checks}))
    (println "all" @checks "probe checks passed")))
