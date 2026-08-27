(ns db.pg-property-test
  (:require [clojure.string :as str]
            [db.builtin]
            [db.pg :as pg]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jolt.ffi :as ffi]))

(defn- ensure! [condition message data]
  (when-not condition
    (throw (ex-info message (assoc data :hegel/origin "db.pg/handle-lifecycle")))))

(defn run-property! []
  (h/run-test!
   {:name "db-pg-handle-lifecycle-v1"
    :test-cases 60
    :stateful-step-count 24
    :derandomize? true
    :database ""
    :verbosity :quiet}
   (fn [_]
     (let [pointer (ffi/alloc 1)
           finishes (atom 0)
           handle (pg/->PgHandle pointer (atom false) (Object.))]
       (try
         (with-redefs [pg/PQfinish (fn [_] (swap! finishes inc) nil)]
           (hs/run!
            {:initial-state {:closed? false :uses 0}
             :rules
             [(hs/rule
               :use
               {:precondition #(not (:closed? %))}
               (fn [state]
                 (pg/with-live-handle handle (fn [actual]
                                               (ensure! (= pointer actual)
                                                        "live handle changed its pointer" {})
                                               nil))
                 (update state :uses inc)))
              (hs/rule
               :close
               {:precondition #(not (:closed? %))}
               (fn [state]
                 (pg/close handle)
                 (assoc state :closed? true)))
              (hs/rule
               :repeat-close
               {:precondition #(:closed? %)}
               (fn [state]
                 (pg/close handle)
                 state))
              (hs/rule
               :reject-use-after-close
               {:precondition #(:closed? %)}
               (fn [state]
                 (ensure! (= :closed
                             (try (pg/with-live-handle handle identity) :open
                                  (catch Exception _ :closed)))
                          "closed postgres handle accepted use" {})
                 state))]
             :invariants
             [(hs/invariant
               :native-close-count
               (fn [{:keys [closed?]}]
                 (= @finishes (if closed? 1 0))))]}))
         (finally
           (ffi/free pointer)))))))

(defn run-uri-property! []
  (h/run-test!
   {:name "db-pg-uri-canonicalization-v1"
    :test-cases 80
    :derandomize? true
    :database ""
    :verbosity :quiet}
   (fn [_]
     (let [host (h/draw! (g/ipv6))
           [escaped decoded]
           (h/draw! (g/sampled-from [["%2F" "/"] ["%3F" "?"]
                                     ["%23" "#"] ["%40" "@"]
                                     ["%20" " "] ["%2B" "+"]]))
           uri-fn @(ns-resolve 'db.builtin 'pg-uri)
           uri (uri-fn {:dbtype "postgresql"
                        :subname (str "//[" host "]/db" escaped
                                      "?user=user" escaped
                                      "&password=pass" escaped
                                      "&application%5Fname=value%20x")})
           expected-user (str "user" escaped)
           expected-db (str "/db" escaped)]
       (when-not (str/starts-with? uri (str "postgres://" expected-user ":pass" escaped
                                           "@[" host "]" expected-db "?"))
         (throw (ex-info "encoded URI component or IPv6 authority changed semantics"
                         {:hegel/origin "db.pg/uri-canonicalization"})))
       (when (or (str/includes? uri (str "%25" (subs escaped 1)))
                 (str/includes? uri (str "user" decoded "@")))
         (throw (ex-info "encoded URI delimiter was double-decoded or double-encoded"
                         {:hegel/origin "db.pg/uri-delimiter"})))))))
