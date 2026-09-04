(ns db.aspect-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private resource-name
  "META-INF/jolt/aspects/db-jdbc-shim.edn")

(def ^:private expected-manifest
  {:schema 1
   :library {:id 'jolt-lang/db
             :version "a55c554a66d8f5e9e5198e238773f8218f6050d7"}
   :aspects
   [{:id :db.jdbc-shim/execute
     :match {:arity 4
             :entry 'db.jdbc-shim/observed-driver-execute-handle}
     :advice-role :db/client
     :expect {:matches 1}}]})

(defn run [check]
  (println "library-owned database aspect contract")
  (let [resource (io/resource resource-name)
        text (some-> resource slurp)
        manifest (some-> text edn/read-string)]
    (check "aspect manifest is packaged as a classpath resource"
           true (some? resource))
    (check "aspect manifest has the exact inert schema and compatibility id"
           expected-manifest manifest)
    (check "aspect manifest carries no telemetry implementation"
           false (and text (.contains (.toLowerCase text) "otel")))))
