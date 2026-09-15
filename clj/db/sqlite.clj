(ns db.sqlite
  "SQLite driver for jolt, binding the system libsqlite3 through jolt.ffi. Exposes
  the surface jdbc.core needs: open / close / query (rows as keyword-keyed maps) /
  changes / last-insert-rowid. No jolt built-in — the binding lives here."
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; libsqlite3 is declared in deps.edn (:jolt/native) and loaded by jolt before
;; this namespace is required, so the bindings below resolve.

;; --- bindings ----------------------------------------------------------------
(ffi/defcfn sqlite3-open          "sqlite3_open"          [:pointer :pointer] :int :blocking)
(ffi/defcfn sqlite3-close-v2      "sqlite3_close_v2"      [:pointer] :int :blocking)
(ffi/defcfn sqlite3-errmsg        "sqlite3_errmsg"        [:pointer] :string)
(ffi/defcfn sqlite3-prepare       "sqlite3_prepare_v2"    [:pointer :pointer :int :pointer :pointer] :int :blocking)
(ffi/defcfn sqlite3-step          "sqlite3_step"          [:pointer] :int :blocking)
(ffi/defcfn sqlite3-finalize      "sqlite3_finalize"      [:pointer] :int :blocking)
(ffi/defcfn sqlite3-column-count  "sqlite3_column_count"  [:pointer] :int)
(ffi/defcfn sqlite3-column-name   "sqlite3_column_name"   [:pointer :int] :string)
(ffi/defcfn sqlite3-column-type   "sqlite3_column_type"   [:pointer :int] :int)
(ffi/defcfn sqlite3-column-text   "sqlite3_column_text"   [:pointer :int] :string)
(ffi/defcfn sqlite3-column-blob   "sqlite3_column_blob"   [:pointer :int] :pointer)
(ffi/defcfn sqlite3-column-bytes  "sqlite3_column_bytes"  [:pointer :int] :int)
(ffi/defcfn sqlite3-column-int64  "sqlite3_column_int64"  [:pointer :int] :int64)
(ffi/defcfn sqlite3-column-double "sqlite3_column_double" [:pointer :int] :double)
(ffi/defcfn sqlite3-bind-text     "sqlite3_bind_text"     [:pointer :int :string :int :iptr] :int)
(ffi/defcfn sqlite3-bind-blob64   "sqlite3_bind_blob64"   [:pointer :int :pointer :uint64 :iptr] :int)
(ffi/defcfn sqlite3-bind-int64    "sqlite3_bind_int64"    [:pointer :int :int64] :int)
(ffi/defcfn sqlite3-bind-double   "sqlite3_bind_double"   [:pointer :int :double] :int)
(ffi/defcfn sqlite3-bind-null     "sqlite3_bind_null"     [:pointer :int] :int)
(ffi/defcfn sqlite3-errcode       "sqlite3_errcode"       [:pointer] :int)
(ffi/defcfn sqlite3-changes       "sqlite3_changes"       [:pointer] :int)
(ffi/defcfn sqlite3-total-changes "sqlite3_total_changes" [:pointer] :int)
(ffi/defcfn sqlite3-last-rowid    "sqlite3_last_insert_rowid" [:pointer] :int64)

(def ^:private SQLITE-OK 0)
(def ^:private SQLITE-NOMEM 7)
(def ^:private SQLITE-ROW 100)
(def ^:private SQLITE-DONE 101)
(def ^:private SQLITE-TRANSIENT -1)        ; tell sqlite to copy the bound data

;; column storage classes (sqlite3_column_type)
(def ^:private TY-INT 1) (def ^:private TY-FLOAT 2) (def ^:private TY-BLOB 4) (def ^:private TY-NULL 5)

(defn- sql-ex [message data]
  (ex-info message (assoc data :jdbc/sql-error true)))

(defn- error-message [db]
  (when-not (ffi/null? db)
    (try (sqlite3-errmsg db) (catch Throwable _ nil))))

(defrecord SqliteHandle [ptr closed?])

(defn- live-ptr! [handle]
  (when @(:closed? handle)
    (throw (sql-ex "sqlite handle is closed" {:db.sqlite/closed true})))
  (:ptr handle))

;; --- connection --------------------------------------------------------------
(defn open
  "Open `path` and return a single-owner, fail-closed handle."
  [path]
  (ffi/with-c-string [path-ptr path]
    (ffi/with-out [pp :pointer]
      (ffi/write pp :pointer ffi/null)
      (let [opened (try {:rc (sqlite3-open path-ptr pp)}
                        (catch Throwable t {:error t}))
            db (ffi/read pp :pointer)]
        (if-let [primary (:error opened)]
          (do
            (when-not (ffi/null? db)
              (try (sqlite3-close-v2 db) (catch Throwable _ nil)))
            (throw primary))
          (let [rc (:rc opened)]
            (if (and (= rc SQLITE-OK) (not (ffi/null? db)))
              (->SqliteHandle db (atom false))
              (let [msg (error-message db)
                    close-outcome (when-not (ffi/null? db)
                                    (try {:rc (sqlite3-close-v2 db)}
                                         (catch Throwable t {:error t})))]
                (throw
                 (sql-ex (str "sqlite open failed: " path
                              (when (seq msg) (str " — " msg)))
                         (cond-> {:rc rc :path path}
                           (some? (:rc close-outcome))
                           (assoc :db.sqlite/close-rc (:rc close-outcome))
                           (some? (:error close-outcome))
                           (assoc :db.sqlite/close-error (:error close-outcome)))))))))))))

(defn close [handle]
  (when (compare-and-set! (:closed? handle) false true)
    (let [rc (sqlite3-close-v2 (:ptr handle))]
      (when-not (= rc SQLITE-OK)
        (throw (sql-ex "sqlite close failed" {:rc rc :db.sqlite/closed true})))))
  nil)

(defn- bind-blob! [stmt i v]
  (let [n (alength v)
        ptr (ffi/alloc (max 1 n))]
    (try
      (ffi/write-array ptr v)
      (sqlite3-bind-blob64 stmt i ptr n SQLITE-TRANSIENT)
      (finally (ffi/free ptr)))))

(defn- bind-params! [stmt params]
  (loop [i 1 ps (seq params)]
    (when ps
      (let [v (first ps)
            rc (cond
                 (nil? v)                    (sqlite3-bind-null stmt i)
                 (bytes? v)                  (bind-blob! stmt i v)
                 (and (integer? v) (int? v)) (sqlite3-bind-int64 stmt i v)
                 (number? v)                 (sqlite3-bind-double stmt i (double v))
                 (string? v)                 (sqlite3-bind-text stmt i v -1 SQLITE-TRANSIENT)
                 :else                       (sqlite3-bind-text stmt i (str v) -1 SQLITE-TRANSIENT))]
        (when-not (= rc SQLITE-OK)
          (throw (sql-ex (str "sqlite bind failed at param " i)
                         {:rc rc :index i}))))
      (recur (inc i) (next ps)))))

(defn- read-blob [db stmt i]
  (let [p (sqlite3-column-blob stmt i)]
    (if (ffi/null? p)
      ;; A null pointer is valid for a zero-length BLOB. SQLITE_NOMEM is the
      ;; documented conversion/allocation failure signal and must be read before
      ;; another SQLite accessor can replace it.
      (let [rc (sqlite3-errcode db)]
        (if (= rc SQLITE-NOMEM)
          (throw (sql-ex "sqlite blob column allocation failed" {:rc rc :index i}))
          (byte-array 0)))
      (let [n (sqlite3-column-bytes stmt i)]
        (when (neg? n)
          (throw (sql-ex "sqlite blob column returned a negative length"
                         {:index i :length n})))
        (ffi/read-array p n)))))

(defn- read-value [db stmt i]
  (let [ty (sqlite3-column-type stmt i)]
    (cond
      (= ty TY-INT)   (sqlite3-column-int64 stmt i)
      (= ty TY-FLOAT) (sqlite3-column-double stmt i)
      (= ty TY-BLOB)  (read-blob db stmt i)
      (= ty TY-NULL)  nil
      :else           (sqlite3-column-text stmt i))))

(defn- read-values [db stmt n]
  (loop [i 0 acc (transient [])]
    (if (= i n)
      (persistent! acc)
      (recur (inc i) (conj! acc (read-value db stmt i))))))

(defn- finalize-outcome [stmt]
  (when-not (ffi/null? stmt)
    (try {:rc (sqlite3-finalize stmt)}
         (catch Throwable t {:error t}))))

(defn- finalize-failed? [outcome]
  (or (some? (:error outcome))
      (and (some? (:rc outcome)) (not= SQLITE-OK (:rc outcome)))))

(defn- run-prepared [db stmt params]
  (let [outcome
        (try
          (bind-params! stmt params)
          (let [ncol (sqlite3-column-count stmt)
                labels (mapv (fn [i] (sqlite3-column-name stmt i)) (range ncol))]
            {:value
             (loop [rows (transient [])]
               (let [rc (sqlite3-step stmt)]
                 (cond
                   (= rc SQLITE-ROW)
                   (recur (conj! rows (read-values db stmt ncol)))

                   (= rc SQLITE-DONE)
                   {:labels labels :rows (persistent! rows)}

                   :else
                   (throw (sql-ex (str "sqlite step failed"
                                       (when-let [msg (error-message db)]
                                         (str ": " msg)))
                                  {:rc rc})))))})
          (catch Throwable t {:error t}))
        finalized (finalize-outcome stmt)]
    (if-let [primary (:error outcome)]
      ;; Jolt has no suppressed-exception mutation API. Cleanup is still
      ;; attempted exactly once, but never replaces the primary failure.
      (throw primary)
      (cond
        (some? (:error finalized)) (throw (:error finalized))
        (finalize-failed? finalized)
        (throw (sql-ex "sqlite finalize failed" {:rc (:rc finalized)}))
        :else (:value outcome)))))

(defn query-raw
  "Run `sql` with `params` (a seq); return {:labels [col-name ...] :rows [[v ...]]}.
  Column order is preserved, which a JDBC-shaped caller needs to read a row by
  index. `query` is this with the rows turned into maps."
  [handle sql params]
  (let [db (live-ptr! handle)]
    (ffi/with-c-string [sql-ptr sql]
      (ffi/with-out [pp :pointer]
        (ffi/write pp :pointer ffi/null)
        (let [prepared (try {:rc (sqlite3-prepare db sql-ptr -1 pp ffi/null)}
                            (catch Throwable t {:error t}))
              stmt (ffi/read pp :pointer)]
          (cond
            (some? (:error prepared))
            (do (finalize-outcome stmt) (throw (:error prepared)))

            (not= (:rc prepared) SQLITE-OK)
            (let [msg (error-message db)
                  finalized (finalize-outcome stmt)]
              (throw
               (sql-ex (str "sqlite prepare failed"
                            (when (seq msg) (str ": " msg)) " — " sql)
                       (cond-> {:rc (:rc prepared)}
                         (and (some? (:rc finalized))
                              (not= SQLITE-OK (:rc finalized)))
                         (assoc :db.sqlite/finalize-rc (:rc finalized))
                         (some? (:error finalized))
                         (assoc :db.sqlite/finalize-error (:error finalized))))))

            (ffi/null? stmt) {:labels [] :rows []}
            :else (run-prepared db stmt params)))))))

(defn query
  "Run `sql` with `params` (a seq); return a vector of keyword-keyed row maps
  (empty for a non-SELECT)."
  [handle sql params]
  (let [{:keys [labels rows]} (query-raw handle sql params)
        ks (mapv keyword labels)]
    (mapv (fn [vs] (zipmap ks vs)) rows)))

(defn changes [handle] (sqlite3-changes (live-ptr! handle)))
;; changes() reports the LAST row-changing statement, so it reads stale after
;; DDL; a before/after delta of total-changes answers "what did THIS statement
;; change" for any statement.
(defn total-changes [handle] (sqlite3-total-changes (live-ptr! handle)))
(defn last-insert-rowid [handle] (sqlite3-last-rowid (live-ptr! handle)))
