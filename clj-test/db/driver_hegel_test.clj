(ns db.driver-hegel-test
  (:require [clojure.string :as str]
            [db.jdbc]
            [db.driver :as driver]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jdbc.core :as jdbc]
            [jdbc.proto :as proto]))

(def ^:private registry-ids [:hegel-reg-a :hegel-reg-b :hegel-reg-c])

(defn- ensure! [origin condition message data]
  (when-not condition
    (throw (ex-info message (assoc data :hegel/origin origin)))))

(defn- test-descriptor [id alias prefix product]
  {:id id
   :aliases #{alias}
   :uri-prefixes #{prefix}
   :product-name product
   :capabilities {:transactions :flat :generated-keys :none}})

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
      (let [params (vec params)]
        (swap! calls conj [:execute sql params])
        (if (str/starts-with? (str/lower-case sql) "select")
          {:labels (if (seq params)
                     (mapv #(str "p" %) (range (count params)))
                     ["p0"])
           :rows (if (seq params) [params] [[42]])
           :count 0}
          {:labels [] :rows [] :count 1})))))

(defn- variant [id n]
  (let [stem (str (name id) "-" n)]
    {:id id
     :alias stem
     :prefix (str stem ":")
     :product (str "Hegel " stem)}))

(defn- actual-registry-model []
  (into {}
        (keep (fn [id]
                (when-let [d (driver/driver-by-id id)]
                  [id (driver/driver-descriptor d)])))
        registry-ids))

(defn- unresolved? [spec]
  (= :missing
     (try (driver/resolve-driver spec) :resolved
          (catch Exception _ :missing))))

(defn- registry-matches?
  [{:keys [model retired-aliases retired-prefixes]}]
  (and
   (= (set (keys model)) (set (keys (actual-registry-model))))
   (every?
    (fn [[id expected]]
      (let [actual (get (actual-registry-model) id)]
        (and (= (:product expected) (:product-name actual))
             (= id (:id (driver/driver-descriptor
                         (driver/resolve-driver {:vendor (:alias expected)}))))
             (= id (:id (driver/driver-descriptor
                         (driver/resolve-driver (str (:prefix expected) "value"))))))))
    model)
   (every? #(unresolved? {:vendor %}) retired-aliases)
   (every? #(unresolved? (str % "retired")) retired-prefixes)))

(defn- absent-registry-id [model]
  (h/draw! (g/sampled-from (vec (remove (set (keys model)) registry-ids)))))

(defn- registry-machine! []
  (hs/run!
   {:initial-state {:model {}
                    :retired-aliases #{}
                    :retired-prefixes #{}}
    :rules
    [(hs/rule
      :register-or-reload
      (fn [{:keys [model retired-aliases retired-prefixes] :as state}]
        (let [id (h/draw! (g/sampled-from registry-ids))
              n (h/draw! (g/integer 0 31))
              v (variant id n)
              old (get model id)]
          (driver/register!
           (fake-driver
            (test-descriptor id (:alias v) (:prefix v) (:product v)) (atom [])))
          (assoc state
                 :model (assoc model id v)
                 :retired-aliases (cond-> (disj retired-aliases (:alias v))
                                    (and old (not= (:alias old) (:alias v)))
                                    (conj (:alias old)))
                 :retired-prefixes (cond-> (disj retired-prefixes (:prefix v))
                                     (and old (not= (:prefix old) (:prefix v)))
                                     (conj (:prefix old)))))))

     (hs/rule
      :unregister
      (fn [{:keys [model retired-aliases retired-prefixes] :as state}]
        (let [id (h/draw! (g/sampled-from registry-ids))
              old (get model id)]
          (driver/unregister! id)
          (cond-> (assoc state :model (dissoc model id))
            old (assoc :retired-aliases (conj retired-aliases (:alias old))
                       :retired-prefixes (conj retired-prefixes (:prefix old)))))))

     (hs/rule
      :reject-alias-collision
      {:precondition #(and (seq (:model %))
                           (< (count (:model %)) (count registry-ids)))}
      (fn [{:keys [model] :as state}]
        (let [owner (h/draw! (g/sampled-from (vec (keys model))))
              candidate (absent-registry-id model)
              owned (get model owner)
              before (actual-registry-model)
              collision (fake-driver
                         (test-descriptor candidate (:alias owned)
                                          (str (name candidate) ":") "Collision")
                         (atom []))]
          (ensure! "db.registry/alias-collision"
                   (= :rejected
                      (try (driver/register! collision) :accepted
                           (catch Exception _ :rejected)))
                   "alias collision was accepted"
                   {:owner owner :candidate candidate})
          (ensure! "db.registry/collision-atomicity"
                   (= before (actual-registry-model))
                   "failed registration changed the registry"
                   {:owner owner :candidate candidate})
          state)))

     (hs/rule
      :reject-prefix-collision
      {:precondition #(and (seq (:model %))
                           (< (count (:model %)) (count registry-ids)))}
      (fn [{:keys [model] :as state}]
        (let [owner (h/draw! (g/sampled-from (vec (keys model))))
              candidate (absent-registry-id model)
              owned (get model owner)
              before (actual-registry-model)
              collision (fake-driver
                         (test-descriptor candidate (name candidate)
                                          (:prefix owned) "Collision")
                         (atom []))]
          (ensure! "db.registry/prefix-collision"
                   (= :rejected
                      (try (driver/register! collision) :accepted
                           (catch Exception _ :rejected)))
                   "URI prefix collision was accepted"
                   {:owner owner :candidate candidate})
          (ensure! "db.registry/collision-atomicity"
                   (= before (actual-registry-model))
                   "failed registration changed the registry"
                   {:owner owner :candidate candidate})
          state)))]
    :invariants [(hs/invariant :registry-matches-model registry-matches?)]}))

(defn- check-registry-stateful! []
  (h/run-test!
   {:name "db-spi-registry-stateful-v1"
    :test-cases 100
    :stateful-step-count 40
    :derandomize? true
    :database ""
    :verbosity :quiet}
   (fn [_]
     (doseq [id registry-ids] (driver/unregister! id))
     (try
       (registry-machine!)
       (finally
         (doseq [id registry-ids] (driver/unregister! id)))))))

(defn- check-longest-prefix! []
  (h/run-test!
   {:name "db-spi-longest-prefix-v1"
    :test-cases 150
    :derandomize? true
    :database ""
    :verbosity :quiet}
   (fn [_]
     (let [suffix (h/draw! (g/string {:min-size 0 :max-size 32
                                      :alphabet "abcXYZ012_-"}))
           short (fake-driver
                  (test-descriptor :hegel-prefix-short "hegel-prefix-short"
                                   "hegel-prefix:" "Short") (atom []))
           long (fake-driver
                 (test-descriptor :hegel-prefix-long "hegel-prefix-long"
                                  "hegel-prefix:specific:" "Long") (atom []))]
       (doseq [id [:hegel-prefix-short :hegel-prefix-long]] (driver/unregister! id))
       (try
         (driver/register! short)
         (driver/register! long)
         (ensure! "db.registry/longest-prefix"
                  (= :hegel-prefix-long
                     (:id (driver/driver-descriptor
                           (driver/resolve-driver
                            (str "HeGeL-PrEfIx:SpEcIfIc:" suffix)))))
                  "longest matching URI prefix did not win"
                  {:suffix suffix})
         (finally
           (doseq [id [:hegel-prefix-short :hegel-prefix-long]]
             (driver/unregister! id))))))))

(defn- calls-since [calls n]
  (subvec (vec @calls) n))

(defn- connection-invariants? [{:keys [conn calls closed?]}]
  (let [events @calls
        closes (count (filter #(= [:close] %) events))]
    (and (= closed? (.isClosed (proto/connection conn)))
         (= closes (if closed? 1 0))
         (= :open (ffirst events))
         (or (not closed?) (= [:close] (last events))))))

(defn- connection-machine! [conn calls]
  (hs/run!
   {:initial-state {:conn conn :calls calls :closed? false}
    :rules
    [(hs/rule
      :query
      {:precondition #(not (:closed? %))}
      (fn [{:keys [conn] :as state}]
        (let [values (h/draw! (g/vector {:max-size 8}
                                        (g/integer -100000 100000)))
              sql (str "select " (str/join ", " (repeat (count values) "?")))
              actual (if (seq values)
                       (first (jdbc/fetch conn (into [sql] values)))
                       (first (jdbc/fetch conn "select 42")))
              actual-values (if (seq values)
                              (mapv #(get actual (keyword (str "p" %)))
                                    (range (count values)))
                              [42])]
          (ensure! "db.connection/query-values"
                   (= (if (seq values) values [42]) actual-values)
                   "query values did not survive the shim"
                   {:values values :actual actual-values})
          state)))

     (hs/rule
      :atomic-success
      {:precondition #(not (:closed? %))}
      (fn [{:keys [conn calls] :as state}]
        (let [before (count @calls)]
          (jdbc/atomic conn (jdbc/execute! conn "update t set x = 1"))
          (let [sqls (mapv second (filter #(= :execute (first %))
                                         (calls-since calls before)))]
            (ensure! "db.connection/commit-sequence"
                     (and (= "BEGIN" (first sqls))
                          (some #{"update t set x = 1"} sqls)
                          (= "COMMIT" (last sqls)))
                     "successful atomic block did not bracket its body"
                     {:sqls sqls}))
          state)))

     (hs/rule
      :atomic-rollback
      {:precondition #(not (:closed? %))}
      (fn [{:keys [conn calls] :as state}]
        (let [before (count @calls)
              outcome (try
                        (jdbc/atomic conn
                          (jdbc/execute! conn "update t set x = 2")
                          (throw (ex-info "expected rollback" {})))
                        :returned
                        (catch Throwable _ :threw))
              sqls (mapv second (filter #(= :execute (first %))
                                        (calls-since calls before)))]
          (ensure! "db.connection/rollback-throws" (= :threw outcome)
                   "transaction body exception was swallowed" {})
          (ensure! "db.connection/rollback-sequence"
                   (and (= "BEGIN" (first sqls))
                        (some #{"update t set x = 2"} sqls)
                        (some #{"ROLLBACK"} sqls))
                   "failed atomic block did not roll back its body"
                   {:sqls sqls})
          state)))

     (hs/rule
      :close
      {:precondition #(not (:closed? %))}
      (fn [{:keys [conn] :as state}]
        (.close (proto/connection conn))
        (assoc state :closed? true)))

     (hs/rule
      :repeat-close
      {:precondition #(:closed? %)}
      (fn [{:keys [conn] :as state}]
        (.close (proto/connection conn))
        state))

     (hs/rule
      :reject-use-after-close
      {:precondition #(:closed? %)}
      (fn [{:keys [conn calls] :as state}]
        (let [before (count @calls)
              outcome (try (jdbc/fetch conn "select 1") :open
                           (catch java.sql.SQLException _ :closed))]
          (ensure! "db.connection/use-after-close" (= :closed outcome)
                   "closed connection accepted a query" {})
          (ensure! "db.connection/no-native-use-after-close"
                   (= before (count @calls))
                   "closed connection reached the driver" {})
          state)))]
    :invariants [(hs/invariant :connection-lifecycle connection-invariants?)]}))

(defn- check-connection-stateful! []
  (h/run-test!
   {:name "db-spi-connection-stateful-v1"
    :test-cases 100
    :stateful-step-count 35
    :derandomize? true
    :database ""
    :verbosity :quiet}
   (fn [_]
     (let [calls (atom [])
           drv (fake-driver
                (test-descriptor :hegel-connection "hegel-connection"
                                 "hegel-connection:" "Hegel Connection") calls)]
       (driver/unregister! :hegel-connection)
       (driver/register! drv)
       (let [conn (jdbc/connection "hegel-connection:value")]
         (try
           (connection-machine! conn calls)
           (finally
             (.close (proto/connection conn))
             (driver/unregister! :hegel-connection))))))))

(defn- report-result! [check label result]
  (println " " label "seed" (:seed result)
           "valid" (:valid-test-cases result)
           "invalid" (:invalid-test-cases result))
  (check label true (and (:passed? result) (not (:flaky? result)))))

(defn run [check]
  (println "jolt-hegel driver SPI properties (v0.3.0)")
  (report-result! check "registry state machine" (check-registry-stateful!))
  (report-result! check "longest URI prefix" (check-longest-prefix!))
  (report-result! check "connection state machine" (check-connection-stateful!)))
