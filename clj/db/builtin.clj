(ns db.builtin
  "SQLite and PostgreSQL adapters for db.driver. Loading this namespace registers
  the built-ins but does not connect to either native library."
  (:require [clojure.string :as str]
            [db.driver :as driver]
            [db.sqlite :as sqlite]
            [db.pg :as pg]))

(defn- sqlite-name [spec]
  (if (string? spec)
    (if (str/starts-with? (str/lower-case spec) "sqlite:") (subs spec 7) spec)
    (let [n (or (:subname spec) (:name spec) (:dbname spec))]
      (if (str/starts-with? (str n) "//") (subs (str n) 2) (str n)))))

(defn- pg-uri [{:keys [subname host port user password dbname] :as spec}]
  (if (string? spec)
    spec
    (let [sn (or subname "")
          sn (if (str/starts-with? sn "//") (subs sn 2) sn)
          [hostport db] (let [i (str/index-of sn "/")]
                          (if i [(subs sn 0 i) (subs sn (inc i))] ["" sn]))
          [db qs] (let [i (str/index-of (or db "") "?")]
                    (if i [(subs db 0 i) (subs db (inc i))] [db nil]))
          params (when qs
                   (into {} (map (fn [kv]
                                   (let [[k v] (str/split kv #"=" 2)] [k v]))
                                 (str/split qs #"&"))))
          user (or user (get params "user"))
          password (or password (get params "password"))]
      (str "postgres://"
           (when user (str user (when password (str ":" password)) "@"))
           (if (str/blank? hostport)
             (str (or host "127.0.0.1") (when port (str ":" port)))
             hostport)
           "/" (or (when-not (str/blank? db) db) dbname (:name spec))))))

(defn- sqlite-execute [handle sql params]
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
    (execute-handle [_ handle sql params] (sqlite-execute handle sql params))))

(def postgresql-driver
  (reify driver/Driver
    (descriptor [_]
      {:id :postgresql
       :aliases #{"postgresql" "postgres" "pgsql"}
       :uri-prefixes ["postgres://" "postgresql://"]
       :product-name "PostgreSQL"
       :capabilities {:transactions :savepoint :generated-keys :returning}
       :schema-sql (fn [schema] (str "SET search_path TO " schema))})
    (open-handle [_ spec] (pg/connect (pg-uri spec)))
    (close-handle [_ handle] (pg/close handle))
    (execute-handle [_ handle sql params] (pg/execute-any handle sql params))))

(driver/register! sqlite-driver)
(driver/register! postgresql-driver)
