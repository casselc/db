(ns db.jdbc-shim
  "The java.sql surface clojure.jdbc drives, over the native drivers in db.sqlite
  and db.pg, so the real clojure.jdbc runs on jolt unchanged rather than being
  reimplemented here. Registered through jolt's host-shim hooks
  (__register-class-statics! / __register-class-methods! /
  __register-instance-check!), the same way jolt-lang/http-client stands in for
  java.net.URL so clj-http-lite runs as published.

  Load order matters: clojure.jdbc's namespaces resolve the java.sql constants when
  they compile, so this namespace has to load before jdbc.core. Requiring db.jdbc
  does that in the right order.

  Shim objects are host tagged-tables whose fields are read and written with
  jolt.host/ref-get and ref-put!. Only the surface clojure.jdbc actually touches is
  implemented; anything else is deliberately absent so a gap shows up as a missing
  method rather than as a silently wrong answer."
  (:require [clojure.string :as str]
            [db.driver :as driver]))

;; Driver capability failures are a SQLException subtype on the JVM. Register
;; that edge in Jolt's modeled class hierarchy before constructing one.
(jolt.host/register-class-supers! "java.sql.SQLFeatureNotSupportedException"
                                  ["java.sql.SQLException"])

;; --- java.sql constants ------------------------------------------------------
;; jdbc.constants maps its keyword options onto these, so they have to read as the
;; same ints the JVM uses: a caller who passes :serializable through to
;; setTransactionIsolation gets 8 either way.

(clojure.core/__register-class-statics! "java.sql.ResultSet"
  {"TYPE_FORWARD_ONLY"        1003
   "TYPE_SCROLL_INSENSITIVE"  1004
   "TYPE_SCROLL_SENSITIVE"    1005
   "CONCUR_READ_ONLY"         1007
   "CONCUR_UPDATABLE"         1008
   "HOLD_CURSORS_OVER_COMMIT" 1
   "CLOSE_CURSORS_AT_COMMIT"  2
   "FETCH_FORWARD"            1000
   "FETCH_REVERSE"            1001
   "FETCH_UNKNOWN"            1002})

(clojure.core/__register-class-statics! "java.sql.Connection"
  {"TRANSACTION_NONE"             0
   "TRANSACTION_READ_UNCOMMITTED" 1
   "TRANSACTION_READ_COMMITTED"   2
   "TRANSACTION_REPEATABLE_READ"  4
   "TRANSACTION_SERIALIZABLE"     8})

(clojure.core/__register-class-statics! "java.sql.Statement"
  {"RETURN_GENERATED_KEYS" 1
   "NO_GENERATED_KEYS"     2
   "CLOSE_CURRENT_RESULT"  1
   "KEEP_CURRENT_RESULT"   2
   "CLOSE_ALL_RESULTS"     3
   "SUCCESS_NO_INFO"       -2
   "EXECUTE_FAILED"        -3})

;; --- shim object plumbing ----------------------------------------------------
(defn- tt [tag] (jolt.host/tagged-table tag))
(defn- tget [t k] (jolt.host/ref-get t k))
(defn- tput! [t k v] (jolt.host/ref-put! t k v))
(defn- table? [x] (jolt.host/table? x))

(defn- tagged? [x tag] (and (table? x) (= tag (tget x :jolt/type))))

(defn- sql-error
  "Throw as java.sql.SQLException by class, so a caller's (catch SQLException ...)
  matches on the class rather than on anything we put in ex-data."
  ([msg]
   (throw (jolt.host/throwable "java.sql.SQLException" (str msg))))
  ([msg cause]
   ;; Modeled SQLExceptions do not themselves carry ex-data, but preserving the
   ;; original exception as their cause keeps precise driver/boundary data
   ;; available through (ex-data (ex-cause e)).
   (throw (jolt.host/throwable "java.sql.SQLException" (str msg) cause))))

(defn- unsupported [msg]
  (throw (jolt.host/throwable "java.sql.SQLFeatureNotSupportedException" (str msg))))

;; The drivers report failures as ex-info carrying :jdbc/sql-error. Re-throw those
;; as typed SQLExceptions at the shim boundary so clojure.jdbc and its callers see
;; the class they expect.
(defn- as-sql-error [e]
  (if (:jdbc/sql-error (ex-data e))
    (sql-error (ex-message e) e)
    (throw e)))

(defmacro ^:private sql-try [& body]
  `(try ~@body (catch Exception e# (as-sql-error e#))))

(defn extension-operation
  "Run one complete public driver-extension operation at the JDBC boundary.

  Existing SQLExceptions retain their identity. Every other Exception becomes
  a SQLException whose cause is the original value, preserving precise ex-data
  for callers that need structured diagnostics. The supplied function should
  include connection conversion, driver-context preflight, invocation, and
  public result conversion."
  [operation]
  (try
    (operation)
    (catch java.sql.SQLException error
      (throw error))
    (catch Exception error
      (sql-error (or (ex-message error) "driver extension operation failed")
                 error))))

;; --- driver-facing operations ------------------------------------------------
(defn- driver-of [conn] (tget conn :driver))
(defn- descriptor-of [conn] (tget conn :descriptor))
(defn- handle [conn] (tget conn :handle))
(defn- connection-closed? [conn]
  (boolean @(tget conn :close-claimed?)))

(defn- invalid-result! [conn message data]
  (throw (ex-info message
                  (merge {:jdbc/sql-error true
                          :db.driver/error :invalid-result
                          :driver-id (:id (descriptor-of conn))}
                         data))))

(defn- validate-result
  "Fail at the external-driver boundary instead of letting zipmap/nth silently
  truncate a malformed eager positional result."
  [conn result]
  (when-not (map? result)
    (invalid-result! conn "driver result must be a map"
                     {:field :result :actual-type (str (class result))}))
  (let [{:keys [labels rows count]} result]
    (when-not (vector? labels)
      (invalid-result! conn "driver result :labels must be a vector"
                       {:field :labels :actual-type (str (class labels))}))
    (doseq [[index label] (map-indexed vector labels)]
      (when-not (string? label)
        (invalid-result! conn "driver result labels must be strings"
                         {:field :labels :label-index index
                          :actual-type (str (class label))})))
    (when-not (vector? rows)
      (invalid-result! conn "driver result :rows must be a vector"
                       {:field :rows :actual-type (str (class rows))}))
    (doseq [[index row] (map-indexed vector rows)]
      (when-not (vector? row)
        (invalid-result! conn "driver result rows must be vectors"
                         {:field :rows :row-index index
                          :actual-type (str (class row))}))
      (when-not (= (clojure.core/count labels) (clojure.core/count row))
        (invalid-result! conn "driver result row width does not match labels"
                         {:field :rows :row-index index
                          :expected-width (clojure.core/count labels)
                          :actual-width (clojure.core/count row)})))
    (when-not (and (integer? count) (not (neg? count)))
      (invalid-result! conn "driver result :count must be a nonnegative integer"
                       {:field :count :actual count}))
    result))

(defn- driver-execute! [conn sql params]
  (validate-result conn
                   (driver/execute-handle (driver-of conn) (handle conn) sql params)))

(defn- setting-entry [conn setting value]
  (get-in (descriptor-of conn) [:transaction-settings setting value]))

(defn- require-setting! [conn setting value]
  (or (setting-entry conn setting value)
      (unsupported
       (str (:product-name (descriptor-of conn)) " does not support transaction "
            (name setting) " " (pr-str value)))))

(defn validate-transaction-options!
  "Fail before transaction startup when an adapter does not truthfully advertise
  every explicitly requested setting. `:isolation` is the JDBC integer value."
  [conn {:keys [isolation] :as options}]
  (when (contains? options :isolation)
    (require-setting! conn :isolation isolation))
  (when (contains? options :read-only)
    (require-setting! conn :read-only (boolean (:read-only options))))
  nil)

(defn- setting-statements [conn setting value context]
  (let [entry (require-setting! conn setting value)
        action (get entry context)
        action (if (fn? action)
                 (action {:connection conn :setting setting :value value
                          :context context})
                 action)]
    (cond
      (string? action) [action]
      (and (sequential? action) (every? string? action)) (vec action)
      :else (unsupported
             (str (:product-name (descriptor-of conn)) " has no " (name context)
                  " hook for transaction " (name setting) " " (pr-str value))))))

(defn- apply-setting! [conn setting value context]
  (doseq [sql (setting-statements conn setting value context)]
    (driver-execute! conn sql [])))

(defn- ensure-transaction-started! [conn]
  (when (tget conn :tx-pending)
    (tput! conn :tx-pending false)
    (try
      (driver-execute! conn "BEGIN" [])
      (tput! conn :tx-active true)
      (doseq [setting [:isolation :read-only]]
        (when (contains? (tget conn :pending-transaction-settings) setting)
          (apply-setting! conn setting
                          (get (tget conn :pending-transaction-settings) setting)
                          :transaction)))
      (tput! conn :pending-transaction-settings {})
      (catch Throwable t
        (when (tget conn :tx-active)
          (try (driver-execute! conn "ROLLBACK" []) (catch Throwable _ nil)))
        (tput! conn :tx-active false)
        (tput! conn :pending-transaction-settings {})
        (throw t)))))

(defn- run-any [conn sql params]
  (when (connection-closed? conn)
    (sql-error "connection is closed"))
  (sql-try
    (ensure-transaction-started! conn)
    (driver-execute! conn sql params)))

(defn- run-query
  "Execute `sql` and return {:labels [...] :rows [[v ...]]}."
  [conn sql params]
  (let [{:keys [labels rows]} (run-any conn sql params)]
    {:labels labels :rows rows}))

(defn- run-update
  "Execute `sql` and return the number of rows it affected."
  [conn sql params]
  (:count (run-any conn sql params)))

(defn execute-any
  "Execute `sql` ONCE and report both faces: {:labels [...] :rows [[...]]
  :count n}. A statement that returns a result set (SELECT, RETURNING) fills
  labels/rows; one that does not reports its update count. next.jdbc's
  execute!/execute-one!/plan sit on this, so they never run a statement twice
  to learn which kind it was."
  [conn sql params]
  (run-any conn sql params))

;; --- java.sql.ResultSetMetaData ----------------------------------------------
(defn- make-rsmeta [labels]
  (let [t (tt :jdbc/rsmeta)] (tput! t :labels labels) t))

(clojure.core/__register-class-methods! :jdbc/rsmeta
  {"getColumnCount" (fn [self] (count (tget self :labels)))
   ;; JDBC indexes columns from 1
   "getColumnLabel" (fn [self i] (nth (tget self :labels) (dec i)))
   "getColumnName"  (fn [self i] (nth (tget self :labels) (dec i)))})

;; --- java.sql.ResultSet ------------------------------------------------------
;; The drivers hand back every row at once, so this is a cursor over a vector
;; rather than a live server-side cursor. .next walks it; a fetch that streamed on
;; the JVM is eager here, which is a real difference and not just an internal one.
(defn- make-resultset [{:keys [labels rows]}]
  (let [t (tt :jdbc/resultset)]
    (tput! t :labels (or labels []))
    (tput! t :rows (or rows []))
    (tput! t :pos -1)
    (tput! t :closed false)
    t))

(defn- rs-current [self]
  (let [pos (tget self :pos) rows (tget self :rows)]
    (when (and (>= pos 0) (< pos (count rows))) (nth rows pos))))

(clojure.core/__register-class-methods! :jdbc/resultset
  {"next" (fn [self]
            (let [pos (inc (tget self :pos))]
              (tput! self :pos pos)
              (< pos (count (tget self :rows)))))
   "getMetaData" (fn [self] (make-rsmeta (tget self :labels)))
   "getObject" (fn [self i]
                 (let [row (or (rs-current self) (sql-error "ResultSet not positioned on a row"))]
                   (if (number? i)
                     (nth row (dec i))
                     ;; by label, case-insensitively, as JDBC does
                     (let [labels (tget self :labels)
                           idx (first (keep-indexed
                                       (fn [n l] (when (= (str/lower-case l)
                                                          (str/lower-case (str i))) n))
                                       labels))]
                       (if idx (nth row idx) (sql-error (str "no such column: " i)))))))
   "close" (fn [self] (tput! self :closed true) nil)
   "isClosed" (fn [self] (tget self :closed))})

;; --- java.sql.PreparedStatement ----------------------------------------------
;; Params arrive one at a time through .setObject at 1-based indexes, so collect
;; them in a map and flatten to a vector at execute time. That way a caller who
;; sets them out of order still gets them in order.
(defn- make-prepared [conn sql opts]
  (let [t (tt :jdbc/prepared)]
    (tput! t :conn conn)
    (tput! t :sql sql)
    (tput! t :params {})
    (tput! t :returning (:returning opts))
    (tput! t :max-rows (:max-rows opts))
    (tput! t :batch [])
    (tput! t :keys nil)
    (tput! t :closed false)
    t))

(defn- param-vec [self]
  (let [m (tget self :params)]
    (if (empty? m)
      []
      (mapv (fn [i] (get m i)) (range 1 (inc (apply max (keys m))))))))

(defn- limit-rows [self {:keys [labels rows]}]
  (let [n (tget self :max-rows)]
    {:labels labels :rows (if (and n (pos? n)) (vec (take n rows)) rows)}))

;; RETURNING is how the generated keys come back, since neither driver has a
;; JDBC-style generated-keys channel. :all / true asks for the whole row, which is
;; what postgres' own driver gives for RETURN_GENERATED_KEYS; a sequence of names
;; asks for those columns.
(defn- returning-sql [self]
  (let [r (tget self :returning)
        sql (str/trimr (str/replace (tget self :sql) #";\s*$" ""))]
    (cond
      (or (true? r) (= :all r)) (str sql " RETURNING *")
      (sequential? r)           (str sql " RETURNING "
                                     (str/join ", " (map name r)))
      :else                     nil)))

(defn- supports-generated-keys? [conn]
  (= :returning (get-in (descriptor-of conn) [:capabilities :generated-keys])))

(clojure.core/__register-class-methods! :jdbc/prepared
  {"setObject" (fn [self i v] (tput! self :params (assoc (tget self :params) i v)) nil)
   "setString" (fn [self i v] (tput! self :params (assoc (tget self :params) i v)) nil)
   "setNull"   (fn [self i & _] (tput! self :params (assoc (tget self :params) i nil)) nil)

   "executeQuery" (fn [self]
                    (make-resultset
                     (limit-rows self (run-query (tget self :conn) (tget self :sql)
                                                 (param-vec self)))))

   "executeUpdate" (fn [self]
                     (let [conn (tget self :conn)
                           params (param-vec self)]
                       (if-let [rsql (returning-sql self)]
                         (do
                           (when-not (supports-generated-keys? conn)
                             (unsupported (str "generated keys are not supported by "
                                               (:product-name (descriptor-of conn)))))
                           ;; run it as a query so the RETURNING rows can be handed
                           ;; back from getGeneratedKeys, and report the row count
                           (let [res (run-query conn rsql params)]
                             (tput! self :keys res)
                             (count (:rows res))))
                         (do (tput! self :keys nil)
                             (run-update conn (tget self :sql) params)))))

   ;; An empty ResultSet when nothing was requested is what makes insert! fall back
   ;; to the update count, which is how it behaves on a driver without generated
   ;; keys.
   "getGeneratedKeys" (fn [self]
                        (make-resultset (or (tget self :keys) {:labels [] :rows []})))

   "addBatch" (fn [self & _]
                (tput! self :batch (conj (tget self :batch) (param-vec self)))
                (tput! self :params {})
                nil)
   "executeBatch" (fn [self]
                    (let [conn (tget self :conn) sql (tget self :sql)]
                      (mapv (fn [ps] (run-update conn sql ps)) (tget self :batch))))

   "setQueryTimeout" (fn [self _] nil)
   "setFetchSize"    (fn [self _] nil)
   "setMaxRows"      (fn [self n] (tput! self :max-rows n) nil)
   "close"           (fn [self] (tput! self :closed true) nil)
   "isClosed"        (fn [self] (tget self :closed))})

;; --- java.sql.Statement ------------------------------------------------------
;; createStatement is only used for the no-parameter execute path, which batches a
;; single SQL string.
(defn- make-statement [conn]
  (let [t (tt :jdbc/statement)]
    (tput! t :conn conn) (tput! t :batch []) (tput! t :closed false) t))

(clojure.core/__register-class-methods! :jdbc/statement
  {"addBatch" (fn [self sql] (tput! self :batch (conj (tget self :batch) sql)) nil)
   "executeBatch" (fn [self]
                    (let [conn (tget self :conn)]
                      (mapv (fn [sql] (run-update conn sql [])) (tget self :batch))))
   "executeUpdate" (fn [self sql] (run-update (tget self :conn) sql []))
   "executeQuery" (fn [self sql] (make-resultset (run-query (tget self :conn) sql [])))
   "setQueryTimeout" (fn [self _] nil)
   "close" (fn [self] (tput! self :closed true) nil)})

;; --- java.sql.DatabaseMetaData -----------------------------------------------
(defn- make-dbmeta [conn]
  (let [t (tt :jdbc/dbmeta)] (tput! t :conn conn) t))

(clojure.core/__register-class-methods! :jdbc/dbmeta
  {"getDatabaseProductName" (fn [self]
                              (:product-name (descriptor-of (tget self :conn))))
   "getConnection" (fn [self] (tget self :conn))})

;; --- java.sql.Connection -----------------------------------------------------
;; Transactions go through the same BEGIN / SAVEPOINT sequence the drivers already
;; understand. BEGIN is deferred until the first ordinary or driver-extension
;; operation so every requested setting can be validated before any SQL runs.
(defn- make-connection [drv descriptor handle]
  (let [t (tt :jdbc/connection)
        defaults (get-in descriptor [:transaction-settings :defaults])]
    (tput! t :vendor (:id descriptor))
    (tput! t :driver drv)
    (tput! t :descriptor descriptor)
    (tput! t :handle handle)
    (tput! t :autocommit true)
    (tput! t :readonly (boolean (get defaults :read-only false)))
    (tput! t :isolation (get defaults :isolation 2))
    (tput! t :tx-active false)
    (tput! t :tx-pending false)
    (tput! t :pending-transaction-settings {})
    (tput! t :transaction-setting-baseline nil)
    (tput! t :savepoints [])
    (tput! t :closed false)
    ;; The connection layer owns the one call into Driver/close-handle. Keep the
    ;; claim here rather than asking every driver to compensate for concurrent
    ;; or repeated Closeable clients. Driver handles may still defend their own
    ;; native resource as a final safety boundary.
    (tput! t :close-claimed? (atom false))
    t))

(defn- exec! [conn sql] (:count (driver-execute! conn sql [])))

(defn- transaction-mode [conn]
  (get-in (descriptor-of conn) [:capabilities :transactions]))

(defn- set-transaction-setting! [conn setting field value]
  (when-not (= value (tget conn field))
    (try
      (require-setting! conn setting value)
      (catch Throwable t
        ;; clojure.jdbc calls setAutoCommit(false) before its option setters.
        ;; A rejected option must unwind that deferred, SQL-free begin so the
        ;; connection remains usable and no earlier supported option is staged.
        (when (tget conn :tx-pending)
          (doseq [[baseline-field baseline-value]
                  (tget conn :transaction-setting-baseline)]
            (tput! conn baseline-field baseline-value))
          (tput! conn :autocommit true)
          (tput! conn :tx-pending false)
          (tput! conn :pending-transaction-settings {})
          (tput! conn :transaction-setting-baseline nil))
        (throw t)))
    (cond
      (tget conn :autocommit)
      (apply-setting! conn setting value :session)

      (tget conn :tx-pending)
      (tput! conn :pending-transaction-settings
             (assoc (tget conn :pending-transaction-settings) setting value))

      :else
      (apply-setting! conn setting value :transaction))
    (tput! conn field value))
  nil)

(clojure.core/__register-class-methods! :jdbc/connection
  {"createStatement"  (fn [self & _] (make-statement self))
   ;; the overloads differ only in how generated keys are asked for: an int is
   ;; RETURN_GENERATED_KEYS, an array of names asks for those columns
   "prepareStatement" (fn [self sql & args]
                        (let [a (first args)]
                          (make-prepared self sql
                                         (cond
                                           (nil? a) {}
                                           (number? a) (if (= 1 a) {:returning :all} {})
                                           (sequential? a) {:returning (vec a)}
                                           :else {}))))

   "setAutoCommit" (fn [self v]
                     (let [v (boolean v)]
                       (when (not= v (tget self :autocommit))
                         (if v
                           (do
                             (when (and (not (connection-closed? self))
                                        (tget self :tx-active))
                               (exec! self "COMMIT"))
                             (tput! self :tx-active false)
                             (tput! self :tx-pending false)
                             (tput! self :pending-transaction-settings {})
                             (tput! self :transaction-setting-baseline nil))
                           (if (= :none (transaction-mode self))
                             (unsupported (str "transactions are not supported by "
                                               (:product-name (descriptor-of self))))
                             (do
                               ;; Defer BEGIN until the first statement. The
                               ;; transaction strategy sets and validates all
                               ;; options after this call, so unsupported options
                               ;; fail before any SQL or user body runs.
                               (tput! self :tx-pending true)
                               (tput! self :pending-transaction-settings {})
                               (tput! self :transaction-setting-baseline
                                      {:isolation (tget self :isolation)
                                       :readonly (tget self :readonly)}))))
                         (tput! self :autocommit v))
                       nil))
   "getAutoCommit" (fn [self] (tget self :autocommit))

   "commit" (fn [self]
              (when (tget self :tx-active) (exec! self "COMMIT"))
              (tput! self :tx-active false)
              (when-not (tget self :autocommit)
                (tput! self :tx-pending true)
                (tput! self :pending-transaction-settings {})
                (tput! self :transaction-setting-baseline
                       {:isolation (tget self :isolation)
                        :readonly (tget self :readonly)}))
              nil)
   "rollback" (fn [self & [sp]]
                (if sp
                  (do
                    (exec! self (str "ROLLBACK TO SAVEPOINT " (tget sp :name)))
                    (exec! self (str "RELEASE SAVEPOINT " (tget sp :name))))
                  (do (when (tget self :tx-active) (exec! self "ROLLBACK"))
                      (tput! self :tx-active false)
                      (when-not (tget self :autocommit)
                        (tput! self :tx-pending true)
                        (tput! self :pending-transaction-settings {})
                        (tput! self :transaction-setting-baseline
                               {:isolation (tget self :isolation)
                                :readonly (tget self :readonly)}))))
                nil)

   "setSavepoint" (fn [self & [nm]]
                    (when-not (= :savepoint (transaction-mode self))
                      (unsupported (str "nested transactions are not supported by "
                                               (:product-name (descriptor-of self)))))
                    (ensure-transaction-started! self)
                    (let [n (count (tget self :savepoints))
                          name (or nm (str "jdbc_sp_" n))
                          sp (tt :jdbc/savepoint)]
                      (tput! sp :name name)
                      (tput! self :savepoints (conj (tget self :savepoints) name))
                      (exec! self (str "SAVEPOINT " name))
                      sp))
   "releaseSavepoint" (fn [self sp]
                        (exec! self (str "RELEASE SAVEPOINT " (tget sp :name)))
                        nil)

   "setReadOnly" (fn [self v]
                   (set-transaction-setting! self :read-only :readonly (boolean v)))
   "isReadOnly"  (fn [self] (tget self :readonly))
   "setTransactionIsolation" (fn [self v]
                               (set-transaction-setting! self :isolation :isolation v))
   "getTransactionIsolation" (fn [self] (tget self :isolation))
   "setSchema" (fn [self s]
                 (when-let [schema-sql (and s (:schema-sql (descriptor-of self)))]
                   (exec! self (schema-sql s)))
                 nil)

   "getMetaData" (fn [self] (make-dbmeta self))
   "isClosed" (fn [self] (connection-closed? self))
   "close" (fn [self]
             (when (compare-and-set! (tget self :close-claimed?) false true)
               ;; Fail closed: a native close failure must not make a second
               ;; close retry an already-consumed handle.
               (tput! self :closed true)
               (driver/close-handle (driver-of self) (handle self)))
             nil)})

(clojure.core/__register-class-methods! :jdbc/savepoint
  {"getSavepointName" (fn [self] (tget self :name))})

;; --- instance? / catch -------------------------------------------------------
;; clojure.jdbc dispatches protocols on these classes and uses with-open, so the
;; shim values have to answer instance? for them.
(def ^:private class-tags
  {"java.sql.Connection"       :jdbc/connection
   "java.sql.PreparedStatement" :jdbc/prepared
   "java.sql.Statement"        :jdbc/statement
   "java.sql.ResultSet"        :jdbc/resultset
   "java.sql.ResultSetMetaData" :jdbc/rsmeta
   "java.sql.DatabaseMetaData" :jdbc/dbmeta
   "java.sql.Savepoint"        :jdbc/savepoint})

;; Answer true or nil, never false. nil means "not one of mine, keep looking",
;; while false settles the question for every other library's check as well: the
;; first non-nil answer wins. next.jdbc registers its own check so its connection
;; wrapper answers instance? java.sql.Connection, which is how migratus picks its
;; Connection branch, and returning false here silently overruled it.
(clojure.core/__register-instance-check!
  (fn [cn val]
    (when-let [tag (get class-tags cn)]
      ;; a PreparedStatement is a Statement too
      (when (or (tagged? val tag)
                (and (= cn "java.sql.Statement") (tagged? val :jdbc/prepared)))
        true))))

;; Report the java.sql class name for (class x) and, more importantly, so a
;; protocol extended to java.sql.Connection dispatches on these values.
;; clojure.jdbc extends IConnection to java.sql.Connection returning `this`, and
;; without this that arm never fires.
(def ^:private tag->class
  (into {} (map (fn [[c t]] [t c]) class-tags)))

(clojure.core/__register-class!
  (fn [x] (and (table? x) (contains? tag->class (tget x :jolt/type))))
  (fn [x] (get tag->class (tget x :jolt/type)))
  (fn [x] (let [c (get tag->class (tget x :jolt/type))]
            (if (= c "java.sql.PreparedStatement")
              ["java.sql.PreparedStatement" "java.sql.Statement"]
              [c]))))

;; --- connection construction -------------------------------------------------

(defn driver-context
  "Driver-extension SPI. Return the registered descriptor and native state for
  an open shim connection, optionally asserting the expected driver id. Driver
  libraries use this to implement high-level operations such as chDB streaming
  inserts without exposing a native pointer as an application API.

  The optional requirements map accepts only `:capability` and `:preflight`.
  Both are checked before a deferred transaction is started. `:preflight`
  receives the context map and may validate an optional driver protocol or
  operation options before BEGIN or execute-handle work."
  ([conn] (driver-context conn nil))
  ([conn expected-id] (driver-context conn expected-id nil))
  ([conn expected-id requirements]
   (when-not (or (nil? requirements) (map? requirements))
     (sql-error "driver extension requirements must be a map"))
   (when-let [unknown (seq (remove #{:capability :preflight}
                                   (keys requirements)))]
     (sql-error (str "unsupported driver extension requirements: "
                     (pr-str (vec (sort-by str unknown))))))
   (when (and (contains? requirements :capability)
              (not (keyword? (:capability requirements))))
     (sql-error "driver extension :capability must be a keyword"))
   (when (and (contains? requirements :preflight)
              (not (ifn? (:preflight requirements))))
     (sql-error "driver extension :preflight must be callable"))
   (let [{:keys [capability preflight]} requirements]
     (when-not (tagged? conn :jdbc/connection)
       (sql-error "expected a db.jdbc-shim connection"))
     (when (connection-closed? conn)
       (sql-error "connection is closed"))
     (let [descriptor (descriptor-of conn)]
       (when (and expected-id (not= expected-id (:id descriptor)))
         (sql-error (str "expected " expected-id " connection, got " (:id descriptor))))
       (when (and capability
                  (not (contains? (:capabilities descriptor) capability)))
         (unsupported
          (str (:product-name descriptor) " does not support " (name capability))))
       (let [context {:driver (driver-of conn)
                      :descriptor descriptor
                      :handle (handle conn)}]
         (when preflight (preflight context))
         ;; Driver-specific operations bypass execute-handle, so crossing this
         ;; seam materializes the same deferred BEGIN/settings as ordinary SQL.
         ;; Identity and requirements reject before that BEGIN/execute work.
         (sql-try (ensure-transaction-started! conn))
         context)))))

(defn connection
  "Open a java.sql.Connection shim for a clojure.jdbc dbspec. Recognises the
  classic :subprotocol/:subname form, the pretty :vendor/:name form, and a uri
  string; anything else is not a spec this library can serve."
  [spec]
  (if (tagged? spec :jdbc/connection)
    spec
    (sql-try
      (let [drv (driver/resolve-driver spec)
            descriptor (driver/driver-descriptor drv)
            h (driver/open-handle drv spec)]
        (make-connection drv descriptor h)))))
