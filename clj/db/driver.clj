(ns db.driver
  "Driver SPI and registry behind the java.sql shim.

  Applications use jdbc.core or next.jdbc. Driver libraries implement Driver and
  register themselves explicitly; requiring a driver namespace is the only
  discovery mechanism, and registration never downloads or loads native code."
  (:require [clojure.string :as str]))

(defprotocol Driver
  (descriptor [driver]
    "Return {:id keyword :aliases coll :uri-prefixes coll :product-name string
    :capabilities {:transactions :none|:flat|:savepoint
                   :generated-keys :none|:returning}
    :transaction-settings optionally describes truthful JDBC transaction
    settings as {:defaults {:isolation int :read-only boolean}
                 :isolation {jdbc-int {:transaction sql :session sql}}
                 :read-only {boolean {:transaction sql :session sql}}}.
    Missing setting metadata means explicit transaction options are unsupported.
    :constraints optional driver-specific operational constraints}.")
  (open-handle [driver spec] "Open `spec` and return driver-owned state.")
  (close-handle [driver handle] "Close driver-owned state. Called at most once.")
  (execute-handle [driver handle sql params]
    "Execute exactly once and return an eager positional result
    {:labels [string ...] :rows [[value ...] ...] :count integer}. Labels and
    row positions must correspond. Handle concurrency is driver-defined and
    must be surfaced in the descriptor when it is more restrictive than the
    backing engine's ordinary connection contract."))

(def ^:private transaction-modes #{:none :flat :savepoint})
(def ^:private generated-key-modes #{:none :returning})
(defonce ^:private registry (atom {}))

(defn- normalized-alias [x]
  (-> (if (keyword? x) (name x) (str x)) str/lower-case))

(defn- normalize-descriptor [d]
  (let [id (:id d)
        capabilities (:capabilities d)
        tx (:transactions capabilities)
        keys-mode (:generated-keys capabilities)]
    (when-not (keyword? id)
      (throw (ex-info "driver descriptor :id must be a keyword" {:descriptor d})))
    (when-not (and (string? (:product-name d)) (seq (:product-name d)))
      (throw (ex-info "driver descriptor requires :product-name" {:driver-id id})))
    (when-not (transaction-modes tx)
      (throw (ex-info "invalid driver transaction capability"
                      {:driver-id id :transactions tx})))
    (when-not (generated-key-modes keys-mode)
      (throw (ex-info "invalid driver generated-keys capability"
                      {:driver-id id :generated-keys keys-mode})))
    (when (and (contains? d :transaction-settings)
               (not (map? (:transaction-settings d))))
      (throw (ex-info "driver :transaction-settings must be a map"
                      {:driver-id id :transaction-settings (:transaction-settings d)})))
    (assoc d
           :aliases (set (map normalized-alias (conj (vec (:aliases d)) id)))
           :uri-prefixes (set (map (comp str/lower-case str) (:uri-prefixes d))))))

(defn registered
  "Return the immutable registry snapshot, keyed by driver id."
  []
  @registry)

(defn register!
  "Register a Driver. Re-registering the same id replaces it atomically. Exact
  aliases and URI prefixes may not be owned by two different driver ids."
  [driver]
  (when-not (satisfies? Driver driver)
    (throw (ex-info "registered value must satisfy db.driver/Driver"
                    {:value driver})))
  (let [desc (normalize-descriptor (descriptor driver))
        id (:id desc)]
    (swap! registry
           (fn [old]
             (let [others (dissoc old id)
                   alias-owner (into {} (mapcat (fn [[oid entry]]
                                                  (map (fn [a] [a oid])
                                                       (get-in entry [:descriptor :aliases])))
                                                others))
                   prefix-owner (into {} (mapcat (fn [[oid entry]]
                                                   (map (fn [p] [p oid])
                                                        (get-in entry [:descriptor :uri-prefixes])))
                                                 others))
                   alias-conflicts (select-keys alias-owner (:aliases desc))
                   prefix-conflicts (select-keys prefix-owner (:uri-prefixes desc))]
               (when (or (seq alias-conflicts) (seq prefix-conflicts))
                 (throw (ex-info "driver registration conflicts with an existing driver"
                                 {:driver-id id
                                  :alias-conflicts alias-conflicts
                                  :prefix-conflicts prefix-conflicts})))
               (assoc others id {:driver driver :descriptor desc}))))
    driver))

(defn unregister!
  "Remove `id`. Intended for tests and controlled REPL reloads."
  [id]
  (swap! registry dissoc id)
  nil)

(defn driver-by-id [id]
  (get-in @registry [id :driver]))

(defn driver-descriptor [driver]
  (get-in @registry [(-> driver descriptor :id) :descriptor]
          (normalize-descriptor (descriptor driver))))

(defn capabilities [driver]
  (:capabilities (driver-descriptor driver)))

(defn- by-alias [alias]
  (let [a (normalized-alias alias)]
    (some (fn [[_ {:keys [driver descriptor]}]]
            (when (contains? (:aliases descriptor) a) driver))
          @registry)))

(defn- by-prefix [s]
  (let [s (str/lower-case s)]
    (->> @registry
         vals
         (mapcat (fn [{:keys [driver descriptor]}]
                   (map (fn [prefix] {:prefix prefix :driver driver})
                        (:uri-prefixes descriptor))))
         (filter (fn [{:keys [prefix]}] (str/starts-with? s prefix)))
         (sort-by (fn [{:keys [prefix]}] (- (count prefix))))
         first
         :driver)))

(defn resolve-driver
  "Resolve a registered driver for a JDBC dbspec. Bare strings remain SQLite
  paths. URI-looking strings without a registered prefix fail with a useful
  explicit-require error rather than being opened as accidental SQLite files."
  [spec]
  (cond
    (string? spec)
    (or (by-prefix spec)
        (when-not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" spec)
          (driver-by-id :sqlite))
        (throw (ex-info (str "no driver registered for URI; require its jdbc.<driver> namespace: " spec)
                        {:spec spec :jdbc/sql-error true})))

    (map? spec)
    (let [alias (or (:subprotocol spec) (:vendor spec) (:dbtype spec))]
      (or (and alias (by-alias alias))
          (throw (ex-info (str "unsupported or unregistered database vendor: " alias)
                          {:spec spec :vendor alias :jdbc/sql-error true}))))

    :else
    (throw (ex-info (str "invalid dbspec: " (pr-str spec))
                    {:spec spec :jdbc/sql-error true}))))
