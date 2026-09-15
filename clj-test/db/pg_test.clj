(ns db.pg-test
  (:require [clojure.string :as str]
            [db.builtin :as builtin]
            [db.driver :as driver]
            [db.pg :as pg]
            [db.pg-property-test :as property]
            [jolt.ffi :as ffi]))

(defn- throws [f]
  (try (f) nil (catch Throwable t t)))

(defn- private-var [namespace symbol]
  (or (ns-resolve namespace symbol)
      (throw (ex-info (str "missing private var " namespace "/" symbol)
                      {:namespace namespace :symbol symbol}))))

(defn- fake-handle [pointer]
  (pg/->PgHandle pointer (atom false) (Object.)))

(defn run [check]
  (println "postgres remote driver without a server")
  (check "postgres declares serialized handle concurrency"
         :serialized
         (get-in (driver/descriptor builtin/postgresql-driver)
                 [:constraints :handle-concurrency]))
  (let [settings (:transaction-settings
                  (driver/descriptor builtin/postgresql-driver))]
    (check "postgres advertises concrete serializable transaction SQL"
           "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"
           (get-in settings [:isolation 8 :transaction]))
    (check "postgres advertises concrete read-only restoration SQL"
           "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE"
           (get-in settings [:read-only false :session])))

  (let [uri-fn @(private-var 'db.builtin 'pg-uri)
        password "not-for-output /?#"
        uri (uri-fn {:dbtype "postgresql"
                     :host "db.internal"
                     :port 5544
                     :user "app user"
                     :password password
                     :dbname "app/data"
                     :sslmode "verify-full"
                     :connect-timeout 7
                     :pg/options {:channel-binding "require"}})]
    (check "map dbspec uses preserved host/port/user/database"
           true
           (str/starts-with?
            uri "postgres://app%20user:not-for-output%20%2F%3F%23@db.internal:5544/app%2Fdata?"))
    (check "map dbspec carries deterministic common and arbitrary options"
           ["channel_binding=require" "connect_timeout=7" "sslmode=verify-full"]
           (str/split (second (str/split uri #"[?]" 2)) #"&")))

  (let [uri-fn @(private-var 'db.builtin 'pg-uri)
        encoded (uri-fn {:dbtype "postgresql"
                         :subname (str "//db.internal/app%2Fdata"
                                       "?user=app%20user"
                                       "&password=s%2F%3F%23"
                                       "&application%5Fname=my%20app"
                                       "&options=-c%20x%3D1")})]
    (check "encoded subname components are canonicalized without double encoding"
           (str "postgres://app%20user:s%2F%3F%23@db.internal/app%2Fdata"
                "?application_name=my%20app&options=-c%20x%3D1")
           encoded)
    (check "encoded delimiters never become URI structure" false
           (or (str/includes? encoded "%252F")
               (str/includes? encoded "s/?#@")))
    (check "percent-encoded UTF-8 is decoded and canonically re-encoded"
           "postgres://db.internal/caf%C3%A9%F0%9F%98%80"
           (uri-fn {:dbtype "postgresql"
                    :subname "//db.internal/caf%C3%A9%F0%9F%98%80"})))

  (let [uri-fn @(private-var 'db.builtin 'pg-uri)]
    (check "structured IPv6 host is bracketed"
           "postgres://[2001:db8::1]:5432/app"
           (uri-fn {:dbtype "postgresql" :host "2001:db8::1"
                    :port 5432 :dbname "app"}))
    (check "bracketed IPv6 subname authority is preserved"
           "postgres://[2001:db8::2]:5544/app"
           (uri-fn {:dbtype "postgresql"
                    :subname "//[2001:db8::2]:5544/app"}))
    (doseq [spec [{:dbtype "postgresql" :host "db/app" :dbname "safe"}
                  {:dbtype "postgresql" :host "db@attacker" :dbname "safe"}
                  {:dbtype "postgresql" :host "db:5432" :dbname "safe"}
                  {:dbtype "postgresql" :host "::::" :dbname "safe"}
                  {:dbtype "postgresql" :host "db" :port "5432/path" :dbname "safe"}
                  {:dbtype "postgresql" :subname "//2001:db8::1/app"}]]
      (let [error (throws #(uri-fn spec))]
        (check "authority delimiter/ambiguity is rejected" true (some? error))
        (check "authority failure contains no supplied value" false
               (boolean
                (some #(str/includes? (str (ex-message error) (pr-str (ex-data error))) %)
                      ["db/app" "db@attacker" "db:5432" "::::"
                       "5432/path" "2001:db8::1"]))))))

  (let [conn (ffi/alloc 1)
        seen-uri (atom nil)
        finishes (atom 0)]
    (try
      (with-redefs [pg/PQconnectdb (fn [uri-ptr]
                                     (reset! seen-uri (ffi/ptr->string uri-ptr))
                                     conn)
                    pg/PQstatus (constantly 0)
                    pg/PQfinish (fn [_] (swap! finishes inc) nil)]
        (let [handle (pg/connect "postgres://user:secret@db/app")]
          (pg/close handle)
          (pg/close handle)
          (check "blocking connect receives the scoped C string"
                 "postgres://user:secret@db/app" @seen-uri)
          (check "postgres native close is exactly once" 1 @finishes)
          (check "postgres use after close fails before native execution"
                 true
                 (:db.pg/closed (ex-data (throws #(pg/execute-any handle "select 1" [])))))))
      (finally
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        finishes (atom 0)
        error (try
                (with-redefs [pg/PQconnectdb (constantly conn)
                              pg/PQstatus (constantly 1)
                              pg/PQerrorMessage (constantly "connection refused")
                              pg/PQfinish (fn [_] (swap! finishes inc) nil)]
                  (throws #(pg/connect "postgres://user:credential-sentinel@db/app")))
                (finally
                  (ffi/free conn)))]
    (check "failed connect closes its non-null PGconn" 1 @finishes)
    (check "failed connect error does not expose credentials" false
           (str/includes? (str (ex-message error) (pr-str (ex-data error)))
                          "credential-sentinel")))

  (let [conn (ffi/alloc 1)
        finishes (atom 0)
        error (try
                (with-redefs [pg/PQconnectdb (constantly conn)
                              pg/PQstatus (constantly 1)
                              pg/PQerrorMessage
                              (fn [_]
                                (throw (ex-info "credential-sentinel" {})))
                              pg/PQfinish (fn [_] (swap! finishes inc) nil)]
                  (throws #(pg/connect "postgres://user:another-secret@db/app")))
                (finally
                  (ffi/free conn)))]
    (check "error-message read failure still closes PGconn exactly once" 1 @finishes)
    (check "error-message read failure remains a structured SQL error" true
           (:jdbc/sql-error (ex-data error)))
    (check "error-message read failure uses a credential-free fallback"
           "pg connect failed: unable to read connection error"
           (ex-message error)))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [_ _ _ _ _ _ _ _] result)
                    pg/PQresultStatus (constantly 1)
                    pg/PQnfields (constantly 0)
                    pg/PQntuples (constantly 0)
                    pg/PQcmdTuples (constantly "2")
                    pg/PQclear (fn [_] (swap! clears inc) nil)]
        (check "command result shape survives owned handle" 2
               (:count (pg/execute-any handle "update t set x = ?" [1])))
        (check "successful PGresult is cleared exactly once" 1 @clears))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        captured (atom nil)
        handle (fake-handle conn)
        pointer-size (ffi/sizeof :pointer)
        int-size (ffi/sizeof :int)
        uint-size (ffi/sizeof :uint)]
    (try
      (with-redefs [pg/PQexecParams
                    (fn [_ sql n types values lengths formats result-format]
                      ;; Capture while the driver's query scope still owns every
                      ;; parameter allocation passed to libpq.
                      (let [value-pointers
                            (mapv #(ffi/read values :pointer (* % pointer-size))
                                  (range n))]
                        (reset! captured
                                {:sql (ffi/ptr->string sql)
                                 :count n
                                 :types (mapv #(ffi/read types :uint (* % uint-size))
                                              (range n))
                                 :null? (ffi/null? (nth value-pointers 0))
                                 :text (ffi/ptr->string (nth value-pointers 1))
                                 :bytes (vec (ffi/read-array (nth value-pointers 2) 3))
                                 :lengths (mapv #(ffi/read lengths :int (* % int-size))
                                                (range n))
                                 :formats (mapv #(ffi/read formats :int (* % int-size))
                                                (range n))
                                 :result-format result-format}))
                      result)
                    pg/PQresultStatus (constantly 1)
                    pg/PQnfields (constantly 0)
                    pg/PQntuples (constantly 0)
                    pg/PQcmdTuples (constantly "0")
                    pg/PQclear (constantly nil)]
        (pg/execute-any handle "select ?, ?, ?" [nil "abc" (byte-array [0 -1 42])])
        (check "postgres parameter arrays preserve libpq values and offsets"
               {:sql "select $1, $2, $3"
                :count 3
                :types [0 0 17]
                :null? true
                :text "abc"
                :bytes [0 -1 42]
                :lengths [0 0 3]
                :formats [0 0 1]
                :result-format 0}
               @captured))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (doseq [[label status nfields ntuples]
              [["negative field count" 2 -1 0]
               ["negative tuple count" 2 1 -1]
               ["null field count" 2 nil 0]
               ["non-integer tuple count" 2 1 "0"]
               ["command with tuple dimensions" 1 1 0]]]
        (with-redefs [pg/PQexecParams (fn [& _] result)
                      pg/PQresultStatus (constantly status)
                      pg/PQnfields (constantly nfields)
                      pg/PQntuples (constantly ntuples)
                      pg/PQclear (fn [_] (swap! clears inc) nil)]
          (let [error (throws #(pg/execute-any handle "select 1" []))]
            (check (str label " is rejected") true (:jdbc/sql-error (ex-data error))))))
      (check "every malformed-dimension PGresult is cleared" 5 @clears)
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [& _] result)
                    pg/PQresultStatus (constantly 2)
                    pg/PQnfields (constantly 0)
                    pg/PQntuples (constantly 2)
                    pg/PQclear (fn [_] (swap! clears inc) nil)]
        (check "tuple status, not field width, preserves zero-column rows"
               {:labels [] :rows [[] []] :count 0}
               (pg/execute-any handle "select from generate_series(1, 2)" []))
        (check "zero-column tuple PGresult clears exactly once" 1 @clears))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [& _] result)
                    pg/PQresultStatus (constantly 2)
                    pg/PQnfields (constantly 1)
                    pg/PQntuples (constantly 0)
                    pg/PQfname (constantly nil)
                    pg/PQclear (fn [_] (swap! clears inc) nil)]
        (let [error (throws #(pg/execute-any handle "select 1" []))]
          (check "null native column name is rejected" true
                 (:jdbc/sql-error (ex-data error))))
        (check "null-name PGresult clears exactly once" 1 @clears))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (doseq [count-value [nil "-1" "2x" 2]]
        (with-redefs [pg/PQexecParams (fn [& _] result)
                      pg/PQresultStatus (constantly 1)
                      pg/PQnfields (constantly 0)
                      pg/PQntuples (constantly 0)
                      pg/PQcmdTuples (constantly count-value)
                      pg/PQclear (fn [_] (swap! clears inc) nil)]
          (let [error (throws #(pg/execute-any handle "update t set x = 1" []))]
            (check "null/non-decimal command count is rejected" true
                   (:jdbc/sql-error (ex-data error))))))
      (check "every malformed-count PGresult is cleared" 4 @clears)
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [& _] result)
                    pg/PQresultStatus
                    (fn [_] (throw (ex-info "injected status failure" {})))
                    pg/PQclear (fn [_] (swap! clears inc) nil)]
        (check "result-status failure is observed" true
               (some? (throws #(pg/execute-any handle "select 1" []))))
        (check "PGresult clears when status inspection throws" 1 @clears))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        handle (fake-handle conn)
        real-alloc ffi/alloc
        real-string ffi/string->ptr
        real-free ffi/free
        live (atom #{})]
    (try
      (with-redefs [ffi/alloc (fn [size]
                                (let [pointer (real-alloc size)]
                                  (swap! live conj pointer)
                                  pointer))
                    ffi/string->ptr (fn [value]
                                      (let [pointer (real-string value)]
                                        (swap! live conj pointer)
                                        pointer))
                    ffi/free (fn [pointer]
                               (swap! live disj pointer)
                               (real-free pointer))
                    pg/PQexecParams (fn [& _]
                                      (throw (ex-info "injected exec failure" {})))]
        (check "injected PQexecParams failure is observed" true
               (some? (throws #(pg/execute-any handle "select ?, ?" ["text" (byte-array [0 -1])]))))
        (check "all parameter and query allocations free on exec failure" #{} @live))
      (finally
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        result (ffi/alloc 1)
        clears (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [& _] result)
                    pg/PQresultStatus (constantly 2)
                    pg/PQnfields (fn [_] (throw (ex-info "injected decode failure" {})))
                    pg/PQclear (fn [_] (swap! clears inc) nil)]
        (check "decode failure is observed" true
               (some? (throws #(pg/execute-any handle "select 1" []))))
        (check "PGresult clears when decoding throws" 1 @clears))
      (finally
        (ffi/free result)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        statuses (atom 0)
        handle (fake-handle conn)]
    (try
      (with-redefs [pg/PQexecParams (fn [& _] ffi/null)
                    pg/PQresultStatus (fn [_] (swap! statuses inc) 2)]
        (check "null PGresult is rejected" true
               (some? (throws #(pg/execute-any handle "select 1" []))))
        (check "null PGresult is never dereferenced" 0 @statuses))
      (finally
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        handle (fake-handle conn)
        first-entered (promise)
        release-first (promise)
        second-entered (promise)]
    (try
      (let [first-op (future
                       (pg/with-live-handle
                        handle
                        (fn [_]
                          (deliver first-entered true)
                          @release-first
                          :first)))
            _ @first-entered
            second-op (future
                        (pg/with-live-handle
                         handle
                         (fn [_]
                           (deliver second-entered true)
                           :second)))]
        (check "second operation cannot enter a busy PGconn"
               :blocked (deref second-entered 50 :blocked))
        (deliver release-first true)
        (check "serialized first operation completes" :first @first-op)
        (check "serialized second operation completes" :second @second-op))
      (finally
        (deliver release-first true)
        (ffi/free conn))))

  (let [conn (ffi/alloc 1)
        handle (fake-handle conn)
        operation-entered (promise)
        release-operation (promise)
        native-finished (promise)]
    (try
      (with-redefs [pg/PQfinish (fn [_] (deliver native-finished true) nil)]
        (let [operation (future
                          (pg/with-live-handle
                           handle
                           (fn [_]
                             (deliver operation-entered true)
                             @release-operation
                             :operation)))
              _ @operation-entered
              closing (future (pg/close handle))]
          (check "close cannot consume a PGconn during an operation"
                 :blocked (deref native-finished 50 :blocked))
          (deliver release-operation true)
          (check "operation completes before serialized close" :operation @operation)
          @closing
          (check "serialized close eventually reaches PQfinish" true @native-finished)))
      (finally
        (deliver release-operation true)
        (ffi/free conn))))

  (let [result (property/run-property!)]
    (println "  postgres handle swarm seed" (:seed result)
             "valid" (:valid-test-cases result))
    (check "postgres handle lifecycle swarm" true
           (and (:passed? result) (not (:flaky? result)))))

  (let [result (property/run-uri-property!)]
    (println "  postgres URI canonicalization seed" (:seed result)
             "valid" (:valid-test-cases result))
    (check "postgres URI canonicalization property" true
           (and (:passed? result) (not (:flaky? result))))))
