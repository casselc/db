;; Inspect the actual test runner as data: do not require it or run its backends.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn ensure! [condition label]
  ;; Never report the source form, canary, or captured output on failure.
  (when-not condition
    (throw (ex-info (str "pg diagnostic check failed: " label) {}))))

(defn read-runner-forms []
  (with-open [reader (java.io.PushbackReader.
                      (io/reader "clj-test/jdbc/core_test.clj"))]
    (loop [forms []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn runner-heading [forms]
  (let [mains (filter #(and (seq? %)
                            (= 'defn (first %))
                            (= '-main (second %)))
                      forms)]
    (ensure! (= 1 (count mains)) "one main")
    (let [main (first mains)
          branches (filter #(and (seq? %)
                                 (= 'when-let (first %))
                                 (= '[pg-uri (System/getenv "JOLT_TEST_PG_URI")]
                                    (second %)))
                           (drop 3 main))]
      (ensure! (= 1 (count branches)) "one PostgreSQL branch")
      (let [heading (nth (first branches) 2 nil)]
        (ensure! (and (seq? heading) (= 'println (first heading)))
                 "first diagnostic is println")
        heading))))

(defn heading-output [heading canary]
  ;; Only the selected heading is evaluated, with synthetic lexical input.
  (with-out-str (eval (list 'let ['pg-uri canary] heading))))

(defn accepted-output? [output canary user password token]
  (and (= "jdbc.core over postgres\n" output)
       (every? #(not (str/includes? output %))
               [canary user password token])))

(let [user "synthetic-user-canary"
      password "synthetic-password-canary"
      token "synthetic-token-canary"
      canary (str "postgres://" user ":" password
                  "@example.invalid/db?token=" token)
      current (runner-heading (read-runner-forms))
      current-output (heading-output current canary)
      historical-output
      (heading-output '(println "jdbc.core over postgres (" pg-uri ")")
                      canary)]
  (ensure! (accepted-output? current-output canary user password token)
           "current heading accepted")
  ;; Ensure the historical control really leaks; rejection alone is insufficient.
  (ensure! (every? #(str/includes? historical-output %)
                  [canary user password token])
           "historical control exposes synthetic input")
  (ensure! (not (accepted-output? historical-output canary user password token))
           "same predicate rejects historical heading")
  (println "pg diagnostic: current heading PASS; historical leak control REJECTED"))
