(ns db.driver.sqlite
  "SQLite adapter for db.driver. Requiring this namespace registers the driver;
  opening a connection is still the point at which libsqlite3 is used."
  (:require [clojure.string :as str]
            [db.driver :as driver]
            [db.sqlite :as sqlite]))

(defn- sqlite-name [spec]
  (if (string? spec)
    (if (str/starts-with? (str/lower-case spec) "sqlite:") (subs spec 7) spec)
    (let [n (or (:subname spec) (:name spec) (:dbname spec))]
      (if (str/starts-with? (str n) "//") (subs (str n) 2) (str n)))))

(defn- execute [handle sql params]
  (let [before (sqlite/total-changes handle)
        {:keys [labels rows]} (sqlite/query-raw handle sql params)]
    {:labels labels
     :rows rows
     :count (if (seq labels) 0 (- (sqlite/total-changes handle) before))}))

(def sqlite-driver
  (reify driver/Driver
    (descriptor [_]
      {:id :sqlite
       :aliases #{"sqlite" "sqlite3"}
       :uri-prefixes ["sqlite:"]
       :product-name "SQLite"
       :capabilities {:transactions :savepoint :generated-keys :returning}
       :transaction-settings
       {:defaults {:isolation 8 :read-only false}
        :isolation
        {1 {:transaction "PRAGMA read_uncommitted=1"
            :session "PRAGMA read_uncommitted=1"}
         8 {:transaction "PRAGMA read_uncommitted=0"
            :session "PRAGMA read_uncommitted=0"}}
        :read-only
        {true {:transaction "PRAGMA query_only=1" :session "PRAGMA query_only=1"}
         false {:transaction "PRAGMA query_only=0" :session "PRAGMA query_only=0"}}}
       :schema-sql nil})
    (open-handle [_ spec]
      (let [handle (sqlite/open (sqlite-name spec))]
        (try
          (sqlite/query-raw handle "PRAGMA foreign_keys=1;" [])
          handle
          (catch Throwable e
            (sqlite/close handle)
            (throw e)))))
    (close-handle [_ handle] (sqlite/close handle))
    (execute-handle [_ handle sql params] (execute handle sql params))))

(driver/register! sqlite-driver)
