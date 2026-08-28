(ns db.export
  "Optional, driver-neutral encoded query export.

  Drivers opt in with QueryBytesDriver and a matching `:query-bytes` capability
  descriptor. The shared boundary owns SQL-vector normalization, option bounds,
  and the returned byte-array contract; SQL validation, serialization, locking,
  native ownership, and any private staging remain driver responsibilities."
  (:require [db.driver :as driver]
            [db.jdbc-shim :as shim]
            [jdbc.proto :as proto]))

(defprotocol QueryBytesDriver
  (query-bytes-handle [driver handle sql params options]
    "Execute one trusted, result-bounded query and return an owned byte array.
    `options` contains normalized `:format`, `:max-rows`, and `:max-bytes`."))

(def ^:private option-keys #{:format :max-rows :max-bytes})
(def ^:private result-keys
  #{:format :content-type :extension :byte-count :bytes})

(defn query-bytes-capability
  "Return a driver's normalized encoded-query capability, or nil."
  [driver]
  (get-in (driver/driver-descriptor driver) [:capabilities :query-bytes]))

(defn- export-error [message data]
  (throw (ex-info message
                  (merge {:jdbc/sql-error true
                          :db.export/query-bytes true}
                         data))))

(defn- normalize-query [query]
  (cond
    (string? query) {:sql query :params []}
    (and (vector? query) (seq query) (string? (first query)))
    {:sql (first query) :params (subvec query 1)}
    :else
    (export-error
     "encoded query must be a SQL string or non-empty JDBC SQL vector"
     {:query query})))

(defn- bounded-option [limits option default-option options]
  (let [value (get options option (get limits default-option))
        maximum (get limits option)]
    (when-not (and (integer? value) (<= 1 value maximum))
      (export-error "encoded query cap is outside the driver hard bound"
                    {:option option :value value :minimum 1 :maximum maximum}))
    value))

(defn- normalize-options [capability options]
  (when-not (map? options)
    (export-error "encoded query options must be a map" {:options options}))
  (when-let [unknown (seq (remove option-keys (keys options)))]
    (export-error "encoded query options contain unsupported keys"
                  {:keys (vec (sort-by str unknown))}))
  (let [format (:format options)
        format-info (get-in capability [:formats format])
        limits (:limits capability)]
    (when-not format-info
      (export-error "encoded query format is not supported by this driver"
                    {:format format
                     :supported-formats (vec (sort-by str (keys (:formats capability))))}))
    {:format format
     :max-rows (bounded-option limits :max-rows :default-max-rows options)
     :max-bytes (bounded-option limits :max-bytes :default-max-bytes options)}))

(defn- validate-result [result format-info options]
  (when-not (map? result)
    (export-error "encoded query driver result must be a map"
                  {:field :result :actual-type (str (class result))}))
  (when-let [unknown (seq (remove result-keys (keys result)))]
    (export-error "encoded query driver result contains unsupported keys"
                  {:keys (vec (sort-by str unknown))}))
  (let [payload (:bytes result)
        byte-count (:byte-count result)
        actual-count (when (bytes? payload) (alength payload))
        expected {:format (:format options)
                  :content-type (:content-type format-info)
                  :extension (:extension format-info)}]
    (when-not (bytes? payload)
      (export-error "encoded query driver result :bytes must be a byte array"
                    {:field :bytes :actual-type (str (class payload))}))
    (when-not (and (integer? byte-count) (not (neg? byte-count)))
      (export-error "encoded query driver result :byte-count must be nonnegative"
                    {:field :byte-count :actual byte-count}))
    (when-not (= byte-count actual-count)
      (export-error "encoded query driver result byte count does not match its payload"
                    {:field :byte-count :reported byte-count :actual actual-count}))
    (when (> actual-count (:max-bytes options))
      (export-error "encoded query driver result exceeds the requested byte cap"
                    {:actual-bytes actual-count
                     :maximum-bytes (:max-bytes options)}))
    (doseq [[field value] expected]
      (when (and (contains? result field) (not= value (get result field)))
        (export-error "encoded query driver result metadata conflicts with its descriptor"
                      {:field field :expected value :actual (get result field)})))
    (assoc expected :byte-count actual-count :bytes payload)))

(defn query-bytes
  "Execute one trusted, result-bounded query and return owned encoded bytes.

  `connection` must resolve through jdbc.proto/IConnection. `query` is a SQL
  string or `[sql & params]`, including HoneySQL JDBC vectors. `options` requires
  a descriptor-advertised `:format`; `:max-rows` and `:max-bytes` default to and
  may lower the driver's advertised hard caps. Results are rejected rather than
  truncated. No filesystem path is part of this API."
  [connection query options]
  (shim/extension-operation
   (fn []
     (let [{:keys [sql params]} (normalize-query query)
           shim-connection (proto/connection connection)
           requirements
           {:capability :query-bytes
            :preflight
            (fn [{:keys [driver descriptor]}]
              (when-not (satisfies? QueryBytesDriver driver)
                (export-error
                 "driver advertises :query-bytes without implementing QueryBytesDriver"
                 {:driver-id (:id descriptor)}))
              ;; Validate closed options and descriptor-selected bounds before a
              ;; pending transaction can be materialized.
              (normalize-options
               (get-in descriptor [:capabilities :query-bytes]) options))}
           {:keys [driver descriptor handle]}
           (shim/driver-context shim-connection nil requirements)
           capability (get-in descriptor [:capabilities :query-bytes])
           normalized-options (normalize-options capability options)
           format-info (get-in capability [:formats (:format normalized-options)])]
       (validate-result
        (query-bytes-handle driver handle sql params normalized-options)
        format-info normalized-options)))))
