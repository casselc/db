(ns db.export-test
  (:require [db.driver :as driver]
            [db.export :as export]
            [db.jdbc-shim :as shim]
            [jdbc.core :as jdbc]
            [jdbc.proto :as proto]))

(defn- descriptor [id query-bytes]
  (cond->
   {:id id
    :aliases #{(name id)}
    :uri-prefixes [(str (name id) ":")]
    :product-name (str "Fake " (name id))
    :capabilities {:transactions :flat :generated-keys :none}}
    query-bytes (assoc-in [:capabilities :query-bytes] query-bytes)))

(def query-bytes-capability
  {:version 1
   :formats
   {:arrow {:content-type "application/vnd.apache.arrow.file"
            :extension "arrow"}
    :parquet {:content-type "application/vnd.apache.parquet"
              :extension "parquet"}}
   :limits {:max-rows 100
            :max-bytes 1024
            :default-max-rows 50
            :default-max-bytes 512}
   :staging :memory})

(defn- ordinary-driver [desc calls]
  (reify driver/Driver
    (descriptor [_] desc)
    (open-handle [_ spec]
      (swap! calls conj [:open spec])
      {:calls calls})
    (close-handle [_ _] (swap! calls conj [:close]) nil)
    (execute-handle [_ _ sql params]
      (swap! calls conj [:execute sql (vec params)])
      {:labels [] :rows [] :count 0})))

(defn- exporting-driver
  ([desc calls result] (exporting-driver desc calls result nil))
  ([desc calls result execute-fn]
   (reify
    driver/Driver
    (descriptor [_] desc)
    (open-handle [_ spec]
      (swap! calls conj [:open spec])
      {:calls calls})
    (close-handle [_ _] (swap! calls conj [:close]) nil)
    (execute-handle [_ _ sql params]
      (swap! calls conj [:execute sql (vec params)])
      (if execute-fn
        (execute-fn sql params)
        {:labels [] :rows [] :count 0}))

    export/QueryBytesDriver
    (query-bytes-handle [_ _ sql params options]
      (swap! calls conj [:query-bytes sql (vec params) options])
      (let [value @result]
        (if (instance? Throwable value) (throw value) value))))))

(defn- rejected [f]
  (try (f) nil (catch Throwable t t)))

(defn- sql-exception? [error]
  (instance? java.sql.SQLException error))

(defn- cause-data [error]
  (some-> error ex-cause ex-data))

(defn run [check]
  (println "optional encoded-query driver capability")

  (let [calls (atom [])
        invalid (ordinary-driver
                 (descriptor :export-invalid
                             (assoc-in query-bytes-capability
                                       [:limits :default-max-bytes] 2048))
                 calls)
        error (rejected #(driver/register! invalid))]
    (check "invalid capability descriptors fail registration" true (some? error))
    (check "invalid capability registration opens no handle" [] @calls)
    (check "invalid capability reports the capability"
           :query-bytes (:capability (ex-data error))))

  (doseq [[label field value]
          [["content type with a control" :content-type "text/plain\r\nX-Evil: yes"]
           ["content type with parameters" :content-type "text/plain; charset=utf-8"]
           ["path-shaped extension" :extension "../arrow"]
           ["oversized extension" :extension "this_extension_is_far_too_long"]]]
    (let [calls (atom [])
          invalid-capability
          (assoc-in query-bytes-capability [:formats :arrow field] value)
          invalid (ordinary-driver
                   (descriptor (keyword (str "export-invalid-" (name field)
                                             "-" (count value)))
                               invalid-capability)
                   calls)
          error (rejected #(driver/register! invalid))]
      (check (str "unsafe descriptor rejects " label) field
             (:field (ex-data error)))
      (check (str "unsafe descriptor opens no handle, " label) [] @calls)))

  (let [error (rejected
               #(export/query-bytes 42 "select 1" {:format :arrow}))]
    (check "connection conversion failure is a SQLException" true
           (sql-exception? error)))

  (let [error (rejected
               #(export/query-bytes 42 [:not-a-sql-string]
                                    {:format :arrow}))]
    (check "query validation failure is a SQLException" true
           (sql-exception? error))
    (check "query validation preserves structured cause data" true
           (:db.export/query-bytes (cause-data error))))

  (let [calls (atom [])
        unsupported (ordinary-driver (descriptor :export-unsupported nil) calls)]
    (try
      (driver/register! unsupported)
      (with-open [conn (jdbc/connection "export-unsupported:value")]
        (reset! calls [])
        (.setAutoCommit (proto/connection conn) false)
        (let [error (rejected #(export/query-bytes conn "select 1" {:format :arrow}))]
          (check "unadvertised capability rejects as SQLException" true
                 (sql-exception? error))
          (check "unadvertised capability rejects before BEGIN or driver export" [] @calls))
        (.setAutoCommit (proto/connection conn) true))
      (finally (driver/unregister! :export-unsupported))))

  (let [calls (atom [])
        mismatch (ordinary-driver
                  (descriptor :export-mismatch query-bytes-capability) calls)]
    (try
      (driver/register! mismatch)
      (with-open [conn (jdbc/connection "export-mismatch:value")]
        (reset! calls [])
        (.setAutoCommit (proto/connection conn) false)
        (let [error (rejected #(export/query-bytes conn "select 1" {:format :arrow}))]
          (check "descriptor/protocol mismatch rejects as SQLException" true
                 (sql-exception? error))
          (check "protocol mismatch preserves structured cause data"
                 :export-mismatch (:driver-id (cause-data error)))
          (check "descriptor/protocol mismatch rejects before BEGIN" [] @calls))
        (.setAutoCommit (proto/connection conn) true))
      (finally (driver/unregister! :export-mismatch))))

  (let [calls (atom [])
        drv (ordinary-driver (descriptor :export-requirements nil) calls)]
    (try
      (driver/register! drv)
      (with-open [conn (jdbc/connection "export-requirements:value")]
        (.setAutoCommit (proto/connection conn) false)
        (reset! calls [])
        (doseq [[label requirements]
                [["non-map" []]
                 ["unknown key" {:unknown true}]
                 ["non-keyword capability" {:capability "query-bytes"}]
                 ["non-callable preflight" {:preflight nil}]]]
          (let [error (rejected
                       #(shim/driver-context (proto/connection conn) nil
                                             requirements))]
            (check (str "driver-context rejects " label " requirements") true
                   (sql-exception? error))))
        (check "invalid requirements never materialize deferred BEGIN" [] @calls)
        (.setAutoCommit (proto/connection conn) true))
      (finally (driver/unregister! :export-requirements))))

  (let [calls (atom [])
        result (atom {:byte-count 1 :bytes (byte-array [1])})
        begin-error (ex-info "injected BEGIN failure"
                             {:jdbc/sql-error true :phase :begin})
        drv (exporting-driver
             (descriptor :export-begin-failure query-bytes-capability)
             calls result
             (fn [sql _]
               (if (= "BEGIN" sql)
                 (throw begin-error)
                 {:labels [] :rows [] :count 0})))]
    (try
      (driver/register! drv)
      (with-open [conn (jdbc/connection "export-begin-failure:value")]
        (.setAutoCommit (proto/connection conn) false)
        (reset! calls [])
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow}))]
          (check "deferred BEGIN failure is a SQLException" true
                 (sql-exception? error))
          (check "deferred BEGIN preserves driver cause data"
                 :begin (:phase (cause-data error)))
          (check "BEGIN failure never invokes encoded query"
                 [[:execute "BEGIN" []]] @calls)))
      (finally (driver/unregister! :export-begin-failure))))

  (let [calls (atom [])
        invoke-error (ex-info "injected encoded query failure"
                              {:jdbc/sql-error true :phase :invoke})
        result (atom invoke-error)
        drv (exporting-driver
             (descriptor :export-invoke-failure query-bytes-capability)
             calls result)]
    (try
      (driver/register! drv)
      (with-open [conn (jdbc/connection "export-invoke-failure:value")]
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow}))]
          (check "driver invocation failure is a SQLException" true
                 (sql-exception? error))
          (check "driver invocation preserves driver cause data"
                 :invoke (:phase (cause-data error)))))
      (finally (driver/unregister! :export-invoke-failure))))

  (let [calls (atom [])
        result (atom {:byte-count 4 :bytes (byte-array [1 2 3 4])})
        exporting (exporting-driver
                   (descriptor :export-fake query-bytes-capability) calls result)]
    (try
      (driver/register! exporting)
      (check "capability is discoverable from the driver descriptor"
             query-bytes-capability (export/query-bytes-capability exporting))
      (with-open [conn (jdbc/connection "export-fake:value")]
        (reset! calls [])
        (.setAutoCommit (proto/connection conn) false)
        (let [actual (export/query-bytes
                      conn ["select ? as value" 42]
                      {:format :arrow :max-rows 7 :max-bytes 20})]
          (check "successful extension materializes BEGIN before driver work"
                 :execute (ffirst @calls))
          (check "HoneySQL/JDBC vector preserves SQL and positional params"
                 [:query-bytes "select ? as value" [42]
                  {:format :arrow :max-rows 7 :max-bytes 20}]
                 (second @calls))
          (check "neutral boundary supplies canonical result metadata"
                 {:format :arrow
                  :content-type "application/vnd.apache.arrow.file"
                  :extension "arrow"
                  :byte-count 4}
                 (dissoc actual :bytes))
          (check "successful result has only the public keys"
                 #{:format :content-type :extension :byte-count :bytes}
                 (set (keys actual)))
          (check "neutral boundary returns the owned payload" [1 2 3 4]
                 (vec (:bytes actual))))
        (.setAutoCommit (proto/connection conn) true)

        (reset! calls [])
        (.setAutoCommit (proto/connection conn) false)
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :csv :max-rows 1 :max-bytes 1}))]
          (check "unsupported format rejects as SQLException" true
                 (sql-exception? error))
          (check "unsupported format rejects before BEGIN or driver work" [] @calls))
        (.setAutoCommit (proto/connection conn) true)

        (reset! calls [])
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :unknown true}))]
          (check "unknown option rejects as SQLException" true
                 (sql-exception? error))
          (check "unknown option rejects before driver work" [] @calls))

        (reset! calls [])
        (let [_ (export/query-bytes conn "select 1" {:format :parquet})]
          (check "descriptor defaults reach the driver"
                 [:query-bytes "select 1" []
                  {:format :parquet :max-rows 50 :max-bytes 512}]
                 (first @calls)))

        (reset! calls [])
        (.setAutoCommit (proto/connection conn) false)
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :max-rows 101}))]
          (check "cap above descriptor hard bound rejects as SQLException" true
                 (sql-exception? error))
          (check "cap rejection occurs before BEGIN or driver work" [] @calls))
        (.setAutoCommit (proto/connection conn) true)

        (reset! result {:byte-count 3 :bytes (byte-array [1 2 3 4])})
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :max-bytes 20}))]
          (check "result conversion failure is a SQLException" true
                 (sql-exception? error))
          (check "mismatched driver byte count rejects"
                 :byte-count (:field (cause-data error))))

        (reset! result {:byte-count 4 :bytes (byte-array [1 2 3 4])
                        :content-type "application/octet-stream"})
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :max-bytes 20}))]
          (check "driver cannot contradict descriptor media metadata"
                 :content-type (:field (cause-data error))))

        (reset! result {:byte-count 4 :bytes (byte-array [1 2 3 4])
                        :native-pointer :private})
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :max-bytes 20}))]
          (check "driver private result keys reject as SQLException" true
                 (sql-exception? error))
          (check "driver private result key is retained in cause diagnostics"
                 [:native-pointer] (:keys (cause-data error))))

        (reset! result {:byte-count 4 :bytes (byte-array [1 2 3 4])})
        (let [error (rejected
                     #(export/query-bytes conn "select 1"
                                          {:format :arrow :max-bytes 3}))]
          (check "neutral boundary rechecks requested byte cap"
                 3 (:maximum-bytes (cause-data error)))))
      (finally (driver/unregister! :export-fake)))))
