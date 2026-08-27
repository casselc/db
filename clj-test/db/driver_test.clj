(ns db.driver-test
  (:require [clojure.string :as str]
            [db.driver :as driver]
            [db.sqlite :as sqlite]
            [jdbc.core :as jdbc]
            [jdbc.proto :as proto]))

(defn- fake-driver [desc calls]
  (reify driver/Driver
    (descriptor [_] desc)
    (open-handle [_ spec]
      (swap! calls conj [:open spec])
      {:calls calls})
    (close-handle [_ _]
      (swap! calls conj [:close])
      nil)
    (execute-handle [_ _ sql params]
      (swap! calls conj [:execute sql (vec params)])
      (if (or (str/starts-with? (str/lower-case sql) "select")
              (str/includes? (str/lower-case sql) " returning "))
        {:labels ["value"] :rows [[42]] :count 0}
        {:labels [] :rows [] :count 1}))))

(defn- descriptor [id aliases prefixes tx keys-mode product]
  {:id id
   :aliases aliases
   :uri-prefixes prefixes
   :product-name product
   :capabilities {:transactions tx :generated-keys keys-mode}})

(defn- driver-id [d] (:id (driver/driver-descriptor d)))

(defn- sqlite-jdbc-conformance-checks [check]
  (println "sqlite driver SPI through jdbc.core")
  (driver/unregister! :sqlite)
  (require 'db.driver.sqlite :reload)
  (let [desc (driver/driver-descriptor (driver/resolve-driver "sqlite::memory:"))]
    (check "SQLite registers independently" :sqlite (:id desc))
    (check "SQLite declares savepoints and generated keys"
           {:transactions :savepoint :generated-keys :returning}
           (:capabilities desc)))
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (jdbc/execute! conn "create table spi_item (id integer primary key, name text)")
    (check "generated row returns through jdbc.core"
           {:id 1 :name "outer"}
           (first (jdbc/insert! conn :spi_item {:name "outer"} {:returning true})))
    (jdbc/atomic conn
      (try
        (jdbc/atomic conn
          (jdbc/insert! conn :spi_item {:name "inner"})
          (throw (ex-info "rollback inner savepoint" {})))
        (catch Throwable _ nil)))
    (check "nested savepoint rollback preserves the outer connection"
           [{:id 1 :name "outer"}]
           (jdbc/fetch conn "select id, name from spi_item order by id"))))

(defn- sqlite-ownership-checks [check]
  (println "sqlite native ownership")
  (let [h (sqlite/open ":memory:")
        real-close sqlite/sqlite3-close-v2
        closes (atom 0)]
    (with-redefs [sqlite/sqlite3-close-v2
                  (fn [ptr] (swap! closes inc) (real-close ptr))]
      (sqlite/close h)
      (sqlite/close h))
    (check "native close is exactly once" 1 @closes)
    (check "use after close is rejected" :closed
           (try (sqlite/total-changes h) :open
                (catch Exception _ :closed))))

  (let [h (sqlite/open ":memory:")
        payload (byte-array (mapv (fn [i] (let [v (mod (* i 17) 256)]
                                            (if (> v 127) (- v 256) v)))
                                  (range 8192)))]
    (try
      (sqlite/query-raw h "create table payload (body blob)" [])
      (sqlite/query-raw h "insert into payload values (?)" [payload])
      (let [actual (-> (sqlite/query-raw h "select body from payload" [])
                       :rows first first)]
        (sqlite/query-raw h "select 1" [])
        (check "blob bytes outlive statement finalization" (vec payload) (vec actual)))
      (finally (sqlite/close h))))

  (let [h (sqlite/open ":memory:")
        real-finalize sqlite/sqlite3-finalize
        finalizes (atom 0)]
    (try
      (with-redefs [sqlite/sqlite3-bind-int64 (fn [_ _ _] 1)
                    sqlite/sqlite3-finalize
                    (fn [stmt] (swap! finalizes inc) (real-finalize stmt))]
        (check "bind failure is surfaced" :failed
               (try (sqlite/query-raw h "select ?" [1]) :ran
                    (catch Exception _ :failed))))
      (check "bind failure finalizes exactly once" 1 @finalizes)
      (finally (sqlite/close h)))))

(defn run [check]
  (sqlite-jdbc-conformance-checks check)
  (println "driver registry and capability conformance")
  (let [calls (atom [])
        base (fake-driver (descriptor :fake #{"fake"} ["fake:"] :savepoint :returning "FakeDB") calls)
        specific (fake-driver (descriptor :fake-special #{"fake-special"}
                                          ["fake:special:"] :savepoint :returning
                                          "Specific FakeDB") calls)]
    (try
      (driver/register! base)
      (driver/register! specific)
      (check "map specs resolve exact aliases" :fake
             (driver-id (driver/resolve-driver {:vendor :fake :name "x"})))
      (check "URI resolution uses the longest prefix" :fake-special
             (driver-id (driver/resolve-driver "fake:special:value")))
      (check "bare strings retain the SQLite fallback" :sqlite
             (driver-id (driver/resolve-driver "/tmp/plain-db-file")))
      (check "unknown URI diagnostics fail instead of creating SQLite files" :missing
             (try (driver/resolve-driver "missing-driver:value") :resolved
                  (catch Exception _ :missing)))
      (let [replacement (fake-driver (descriptor :fake #{"fake" "fake2"}
                                                 ["fake:" "fake2:"] :savepoint
                                                 :returning "FakeDB 2") calls)]
        (driver/register! replacement)
        (check "same-id registration replaces atomically" "FakeDB 2"
               (:product-name (driver/driver-descriptor
                                (driver/resolve-driver "fake2:value")))))
      (let [conflict (fake-driver (descriptor :conflict #{"fake"} ["conflict:"]
                                              :savepoint :returning "Conflict") calls)]
        (check "alias collisions are rejected" :conflict
               (try (driver/register! conflict) :registered
                    (catch Exception _ :conflict)))
        (check "failed registration leaves the old owner intact" :fake
               (driver-id (driver/resolve-driver {:vendor "fake"}))))

      (reset! calls [])
      (with-open [conn (jdbc/connection "fake:value")]
        (check "driver metadata reaches jdbc.core" "FakeDB 2"
               (.getDatabaseProductName (.getMetaData (proto/connection conn))))
        (check "driver rows preserve labels and values" [{:value 42}]
               (jdbc/fetch conn ["select ? as value" 42]))
        (check "one JDBC query executes once" 1
               (count (filter #(= :execute (first %)) @calls))))
      (check "connection close reaches the driver once" 1
             (count (filter #(= [:close] %) @calls)))
      (let [conn (jdbc/connection "fake:value")]
        (.close conn)
        (let [before (count (filter #(= :execute (first %)) @calls))]
          (check "use after close fails as SQLException" :closed
                 (try (jdbc/fetch conn "select 1") :open
                      (catch java.sql.SQLException _ :closed)))
          (check "use after close never reaches the driver" before
                 (count (filter #(= :execute (first %)) @calls)))))
      (finally
        (driver/unregister! :fake-special)
        (driver/unregister! :fake))))

  (let [calls (atom [])
        none (fake-driver (descriptor :tx-none #{"tx-none"} ["tx-none:"]
                                      :none :none "NoTx") calls)]
    (try
      (driver/register! none)
      (with-open [conn (jdbc/connection "tx-none:value")]
        (let [body-ran (atom false)]
          (check "transaction-less driver rejects before the body" :unsupported
                 (try (jdbc/atomic conn (reset! body-ran true)) :ran
                      (catch java.sql.SQLException _ :unsupported)))
          (check "transaction-less body was not run" false @body-ran))
        (reset! calls [])
        (check "generated-key requests fail before driver execution" :unsupported
               (try (jdbc/insert! conn :t {:x 1} {:returning true}) :ran
                    (catch java.sql.SQLException _ :unsupported)))
        (check "unsupported generated keys execute no SQL" 0
               (count (filter #(= :execute (first %)) @calls)))
        (check "explicit user RETURNING remains an ordinary result" [{:value 42}]
               (jdbc/fetch conn "insert into t values (1) returning value")))
      (finally (driver/unregister! :tx-none))))

  (let [calls (atom [])
        flat (fake-driver (descriptor :tx-flat #{"tx-flat"} ["tx-flat:"]
                                      :flat :none "FlatTx") calls)]
    (try
      (driver/register! flat)
      (with-open [conn (jdbc/connection "tx-flat:value")]
        (jdbc/atomic conn (jdbc/execute! conn "update t set x = 1"))
        (check "flat transactions execute BEGIN and COMMIT" true
               (and (some #(= [:execute "BEGIN" []] %) @calls)
                    (some #(= [:execute "COMMIT" []] %) @calls)))
        (let [nested-ran (atom false)]
          (jdbc/atomic conn
            (check "flat nested transaction rejects before its body" :unsupported
                   (try (jdbc/atomic conn (reset! nested-ran true)) :ran
                        (catch java.sql.SQLException _ :unsupported))))
          (check "flat nested body was not run" false @nested-ran)))
      (finally (driver/unregister! :tx-flat))))

  (sqlite-ownership-checks check))
