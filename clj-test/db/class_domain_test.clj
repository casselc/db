(ns db.class-domain-test
  (:require [db.jdbc-shim :as shim]))

(defn run [check]
  (let [register #'shim/register-shim-class!
        predicate (fn [_] false) class-fn identity tags-fn vector
        calls (atom [])]
    (register (fn [p c t domain] (swap! calls conj [p c t domain]))
              predicate class-fn tags-fn)
    (check "JDBC requests explicit host-table class domain"
           [[predicate class-fn tags-fn :host-table]] @calls)
    (reset! calls [])
    (register (fn [p c t] (swap! calls conj [p c t]))
              predicate class-fn tags-fn)
    (check "legacy class registration keeps original callbacks once"
           [[predicate class-fn tags-fn]] @calls)
    (reset! calls [])
    (let [error (ex-info "registration rejected" {:type ::rejected})
          caught (try
                   (register (fn [& _] (swap! calls conj :attempt) (throw error))
                             predicate class-fn tags-fn)
                   nil
                   (catch Exception e e))]
      (check "non-arity registration errors are propagated" true (identical? error caught))
      (check "non-arity failures do not retry registration" [:attempt] @calls))
    (reset! calls [])
    (let [caught (try
                   (register (fn [& _]
                               (swap! calls conj :attempt)
                               ;; This fails INSIDE a registrar that accepts four
                               ;; arguments, not at the outer invocation boundary.
                               ((fn [x] x)))
                             predicate class-fn tags-fn)
                   nil
                   (catch clojure.lang.ArityException e e))]
      (check "internal registrar arity errors propagate" true (some? caught))
      (check "internal arity errors never retry registration" [:attempt] @calls))))

(defn -main [& _]
  (let [failures (atom [])]
    (run (fn [label expected actual]
           (when-not (= expected actual) (swap! failures conj label))))
    (assert (empty? @failures) (pr-str @failures))
    (println "class-domain registration checks passed")))
