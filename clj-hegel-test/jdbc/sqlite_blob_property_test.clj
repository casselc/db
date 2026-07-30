(ns jdbc.sqlite-blob-property-test
  (:require [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.report :as report]
            [jdbc.core :as jdbc]))

(def ^:private blob-generator
  ;; Includes empty, small, page-sized, and multi-page values while retaining
  ;; one native span that Hegel can shrink all the way to an empty byte array.
  (g/bytes 0 8192))

(def ^:private default-options
  {:test-cases 48
   :database ""
   :derandomize? true
   :name "jdbc.sqlite/blob-round-trip-v1"
   :report-multiple-failures? true
   :verbosity :quiet})

(defn- require! [condition origin message data]
  (when-not condition
    (throw
     (ex-info message
              (assoc data :hegel/origin origin)))))

(defn- complement-bytes [payload]
  (if (zero? (alength payload))
    (byte-array [165 90])
    (byte-array
     (map #(bit-xor 255 (bit-and % 255)) payload))))

(defn- check-blob-case! []
  (let [payload (h/draw! blob-generator :payload)
        payload-size (alength payload)
        expected (vec payload)]
    (h/target! payload-size :payload-size)
    (h/fprn :minimal-payload-size payload-size)
    (with-open [conn (jdbc/connection "sqlite::memory:")]
      (jdbc/execute!
       conn
       "create table blob_probe (id integer primary key, data blob)")
      (jdbc/insert! conn :blob_probe {:id 1 :data payload})
      (jdbc/insert! conn :blob_probe {:id 2 :data nil})
      (jdbc/insert! conn :blob_probe {:id 3 :data (byte-array 0)})
      (let [returned
            (:data
             (jdbc/fetch-one
              conn
              ["select data from blob_probe where id = ?" 1]))
            null-row
            (jdbc/fetch-one
             conn
             ["select data, typeof(data) as storage
                 from blob_probe where id = ?" 2])
            empty-row
            (jdbc/fetch-one
             conn
             ["select data, typeof(data) as storage
                 from blob_probe where id = ?" 3])
            empty-value (:data empty-row)]
        (require! (bytes? returned)
                  "jdbc.sqlite/blob-round-trip/type"
                  "SQLite BLOB did not return a byte array"
                  {:payload-size payload-size})
        (require! (= expected (vec returned))
                  "jdbc.sqlite/blob-round-trip/content"
                  "SQLite BLOB round-trip changed bytes"
                  {:payload-size payload-size})
        (require! (and (nil? (:data null-row))
                       (= "null" (:storage null-row)))
                  "jdbc.sqlite/blob-round-trip/null"
                  "SQL NULL did not remain NULL"
                  {:payload-size payload-size
                   :storage (:storage null-row)})
        (require! (and (bytes? empty-value)
                       (zero? (alength empty-value))
                       (= "blob" (:storage empty-row)))
                  "jdbc.sqlite/blob-round-trip/empty"
                  "empty byte array did not remain an empty BLOB"
                  {:payload-size payload-size
                   :storage (:storage empty-row)})
        (require! (not= (:data null-row) empty-value)
                  "jdbc.sqlite/blob-round-trip/null-empty-distinct"
                  "SQL NULL and an empty BLOB became indistinguishable"
                  {:payload-size payload-size})

        ;; fetch-one has finalized the statement which produced `returned`.
        ;; Update and re-read the same row with deliberately different bytes to
        ;; force later SQLite buffer activity, then inspect the earlier array.
        (let [later-payload (complement-bytes payload)]
          (jdbc/execute!
           conn
           ["update blob_probe set data = ? where id = ?" later-payload 1])
          (let [later-returned
                (:data
                 (jdbc/fetch-one
                  conn
                  ["select data from blob_probe where id = ?" 1]))]
            (require! (= (vec later-payload) (vec later-returned))
                      "jdbc.sqlite/blob-round-trip/later-work-control"
                      "later SQLite BLOB work did not complete byte-exactly"
                      {:payload-size payload-size})
            (require! (= expected (vec returned))
                      "jdbc.sqlite/blob-round-trip/post-finalize-lifetime"
                      "returned BLOB bytes changed after finalization and later SQLite work"
                      {:payload-size payload-size})))))))

(defn- options [args]
  (when (> (count args) 1)
    (throw
     (ex-info "expected at most one replay seed"
              {:arguments (vec args)})))
  (if-let [seed-text (first args)]
    (if-let [seed (parse-long seed-text)]
      (assoc default-options :seed seed)
      (throw
       (ex-info "replay seed must be an integer"
                {:seed seed-text})))
    default-options))

(defn -main [& args]
  (let [runner (report/counting-runner)
        result
        (report/run!
         runner
         "SQLite byte-array BLOB semantics"
         #(h/run-test! (options args)
                       (fn [_] (check-blob-case!))))]
    (when result
      (println "Hegel seed" (:seed result)
               "valid cases" (:valid-test-cases result)))
    (println "Ran" (report/run-count runner)
             "property;"
             (report/failure-count runner) "failures")
    (flush)
    (System/exit (if (report/passed? runner) 0 1))))
