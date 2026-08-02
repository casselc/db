(ns jdbc.tx-depth-probe-test
  "Slice-1 probes revalidating the reported transaction-depth/double-transition
  bug against live db main@41da91e under the Jolt v0.5.17 launcher.

  Probes are deliberately minimal and executable. Where a check encodes a
  safety property the live code violates, the check FAILS and is the smallest
  failing characterization of that defect. Where a check encodes the current
  contract, it passes and acts as a regression tripwire.

  P1  outer BEGIN reported as failed after the boundary physically opened
  P2  nested SAVEPOINT reported as failed after the savepoint was created
  P3  completion-path trace matrix (exactly one completion per begun boundary)
  P4  reentrant depth bookkeeping (samples never negative, returns to zero)
  P5  driver-level multi-statement truncation (sqlite3_prepare_v2 pzTail null)

  Run: jolt -M:test -m jdbc.tx-depth-probe-test"
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

;;; P1 — outer begin: driver reports failure AFTER the boundary opened.
;;;
;;; Emulates sqlite3_step/finalize reporting an error for a BEGIN that had
;;; already transitioned the database. atomic-apply propagates before any
;;; bookkeeping or cleanup, so the logical and physical states can diverge.

(defn probe-p1-begin-divergence! []
  (println "P1 outer begin post-success failure")
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (let [real-execute jdbc/execute!
          injected (ex-info "simulated post-begin driver failure" {:kind :injected})
          events (atom [])
          caught (with-redefs [jdbc/execute!
                               (fn [c sql & opts]
                                 (swap! events conj [:attempt sql])
                                 (let [ret (apply real-execute c sql opts)]
                                   (swap! events conj [:completed sql])
                                   (when (= sql "BEGIN") (throw injected))
                                   ret))]
                   (thrown #(jdbc/atomic-apply conn (fn [_] :must-not-run))))]
      (check "P1: injected begin failure surfaces" true (identical? injected caught))
      (check "P1: BEGIN physically completed before the injected throw"
             [[:attempt "BEGIN"] [:completed "BEGIN"]] @events)
      (check "P1: logical state reports ready" true (transaction-ready? conn))
      (let [next-outcome (capture #(jdbc/atomic-apply conn (fn [_] :recovered)))]
        ;; Safety property: a connection that reports ready must accept a new
        ;; transaction. A physically open boundary makes the next BEGIN fail.
        (check "P1: next transaction after begin failure succeeds"
               :recovered (:value next-outcome)))
      (let [phys (physical-tx-state conn)]
        ;; Safety property: logical depth zero implies physical idle.
        (check "P1: physical boundary idle when logical depth is zero"
               :idle (:state phys))
        (when (:error phys)
          (println "  note P1: next-transaction BEGIN error was"
                   (ex-message (:error phys)))))
      ;; P1 left the boundary open if the property failed; restore idle so the
      ;; remaining probes start clean.
      (capture #(sqlite/query (:handle conn) "ROLLBACK" []))
      (check "P1: restored to physical idle for later probes"
             :idle (:state (physical-tx-state conn))))))

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
  (probe-p1-begin-divergence!)
  (probe-p2-nested-begin-divergence!)
  (probe-p3-trace-matrix!)
  (probe-p4-depth-bookkeeping!)
  (probe-p5-multistatement-truncation!)
  (if (pos? @failures)
    (throw (ex-info "tx-depth probe failures" {:n @failures :checks @checks}))
    (println "all" @checks "probe checks passed")))
