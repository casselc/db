(ns db.sqlite
  "SQLite driver for jolt, binding the system libsqlite3 through jolt.ffi. Exposes
  the surface jdbc.core needs: open / close / query (rows as keyword-keyed maps) /
  changes / last-insert-rowid. No jolt built-in — the binding lives here.

  Handles are single-owner: query, changes, last-insert-rowid, and close must
  not overlap across threads. SqliteHandle fields and generated constructors
  are implementation details; callers must not copy, construct, inspect, or
  mutate them.

  Query params: a byte array (bytes? true) binds as a SQLite BLOB, byte-exact,
  distinct from SQL NULL and from TEXT. BLOB columns come back as byte arrays
  (a zero-length BLOB as a zero-length array); all other column types are
  unchanged."
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; libsqlite3 is declared in deps.edn (:jolt/native) and loaded by jolt before
;; this namespace is required, so the bindings below resolve.

;; --- bindings ----------------------------------------------------------------
;; open/prepare/step/finalize/close_v2 may enter SQLite's VFS or lock-wait
;; paths, so they are :blocking; bind/column accessors are short in-memory
;; reads/writes and are left nonblocking.
;;
;; Chez's __collect_safe (jolt's :blocking) foreign procedures cannot take a
;; :string argument — only :pointer/:int/etc marshal safely while the calling
;; thread is parked. sqlite3_open and sqlite3_prepare_v2 take a C string, so
;; those two bindings take :pointer instead and callers pass a
;; ffi/string->ptr'd buffer (freed by the caller after the call).
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
(ffi/defcfn sqlite3-column-int64  "sqlite3_column_int64"  [:pointer :int] :int64)
(ffi/defcfn sqlite3-column-double "sqlite3_column_double" [:pointer :int] :double)
(ffi/defcfn sqlite3-bind-text     "sqlite3_bind_text"     [:pointer :int :string :int :iptr] :int)
(ffi/defcfn sqlite3-bind-int64    "sqlite3_bind_int64"    [:pointer :int :int64] :int)
(ffi/defcfn sqlite3-bind-double   "sqlite3_bind_double"   [:pointer :int :double] :int)
(ffi/defcfn sqlite3-bind-null     "sqlite3_bind_null"     [:pointer :int] :int)
;; bind-blob64 and the column-blob accessors move raw bytes, not UTF-8 text, so
;; they stay off the :blocking path with the rest of bind/column — the copy
;; into/out of foreign memory is a fast in-memory operation, never a VFS or
;; lock-wait call.
(ffi/defcfn sqlite3-bind-blob64   "sqlite3_bind_blob64"   [:pointer :int :pointer :uint64 :iptr] :int)
(ffi/defcfn sqlite3-column-blob   "sqlite3_column_blob"   [:pointer :int] :pointer)
(ffi/defcfn sqlite3-column-bytes  "sqlite3_column_bytes"  [:pointer :int] :int)
(ffi/defcfn sqlite3-errcode       "sqlite3_errcode"       [:pointer] :int)
(ffi/defcfn sqlite3-changes       "sqlite3_changes"       [:pointer] :int)
(ffi/defcfn sqlite3-last-rowid    "sqlite3_last_insert_rowid" [:pointer] :int64)

(def ^:private SQLITE-OK 0)
(def ^:private SQLITE-NOMEM 7)
(def ^:private SQLITE-ROW 100)
(def ^:private SQLITE-DONE 101)
(def ^:private SQLITE-TRANSIENT -1)        ; tell sqlite to copy the bound text/blob

;; column storage classes (sqlite3_column_type)
(def ^:private TY-INT 1) (def ^:private TY-FLOAT 2) (def ^:private TY-BLOB 4) (def ^:private TY-NULL 5)

(defn- error-message [db]
  (when-not
    (ffi/null? db)
    (try
      (sqlite3-errmsg db)
      (catch Throwable _ nil))))

;; --- connection handle ---------------------------------------------------
;; Each handle owns its own closed? flag, so idempotent close and
;; use-after-close checks need no global pointer-reuse registry. This flag does
;; not serialize native operations with close; the public single-owner contract
;; forbids that race.
(defrecord SqliteHandle [ptr closed?])

(defn- live-ptr!
  "Unwrap `handle`'s raw sqlite3* pointer, rejecting use after close."
  [handle]
  (when @(:closed? handle)
    (throw (ex-info "sqlite handle is closed" {:db.sqlite/closed true})))
  (:ptr handle))

;; --- connection --------------------------------------------------------------
(defn open
  "Open `path` (a file or \":memory:\"). Returns a single-owner handle. Treat
  its record representation as opaque; do not copy or mutate its fields."
  [path]
  (let [path-ptr (ffi/string->ptr path)]
    (try
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try
          (ffi/write pp :pointer 0 ffi/null)
          (let [opened (try
                         {:rc (sqlite3-open path-ptr pp)}
                         (catch Throwable t {:error t}))
                db (ffi/read pp :pointer)]
            (if-let [primary (:error opened)]
              (do
                (when-not
                  (ffi/null? db)
                  (try
                    (sqlite3-close-v2 db)
                    (catch Throwable _ nil)))
                (throw primary))
              (let [rc (:rc opened)]
                (if (and (= rc SQLITE-OK) (not (ffi/null? db)))
                  (->SqliteHandle db (atom false))
                  (let [msg (error-message db)
                        close-outcome
                        (when-not
                          (ffi/null? db)
                          (try
                            {:rc (sqlite3-close-v2 db)}
                            (catch Throwable t {:error t})))
                        data
                        (cond-> {:rc rc :path path}
                          (some? (:rc close-outcome))
                          (assoc :db.sqlite/close-rc (:rc close-outcome))

                          (some? (:error close-outcome))
                          (assoc :db.sqlite/close-error (:error close-outcome)))]
                    (throw (ex-info (str "sqlite open failed: " path
                                          (when (seq msg) (str " — " msg)))
                                     data)))))))
          (finally
            (ffi/free pp))))
      (finally (ffi/free path-ptr)))))

(defn close
  "Close `handle`. Idempotent — a second (or later) call on an already-closed
  handle is a no-op. Do not race close with another operation. If the native
  close call throws or reports failure, native ownership is indeterminate and
  may leak; the handle remains logically closed and is never retried."
  [handle]
  (when (compare-and-set! (:closed? handle) false true)
    (let [rc (sqlite3-close-v2 (:ptr handle))]
      (when-not (= rc SQLITE-OK)
        (throw
          (ex-info "sqlite close failed"
                   {:rc rc :db.sqlite/closed true})))))
  nil)

(defn- bind-blob!
  "Bind byte array `v` to 1-based param `i`. Copies into a native buffer sized
  for at least one byte (even when `v` is empty, so NULL is never passed —
  NULL must stay distinct from a zero-length BLOB) and binds with
  SQLITE_TRANSIENT so sqlite copies the bytes before this call returns; the
  buffer is freed immediately after, whether bind succeeded or threw."
  [stmt i v]
  (let [n (alength v)
        buf (ffi/alloc (max n 1))]
    (try
      (ffi/write-array buf v)
      (sqlite3-bind-blob64 stmt i buf n SQLITE-TRANSIENT)
      (finally
        (ffi/free buf)))))

(defn- bind-params! [stmt params]
  (loop [i 1 ps (seq params)]
    (when ps
      (let [v (first ps)
            rc (cond
                 (nil? v)                       (sqlite3-bind-null stmt i)
                 (and (integer? v) (int? v))    (sqlite3-bind-int64 stmt i v)
                 (number? v)                    (sqlite3-bind-double stmt i (double v))
                 (string? v)                    (sqlite3-bind-text stmt i v -1 SQLITE-TRANSIENT)
                 (bytes? v)                     (bind-blob! stmt i v)
                 :else                          (sqlite3-bind-text stmt i (str v) -1 SQLITE-TRANSIENT))]
        (when-not (= rc SQLITE-OK)
          (throw (ex-info (str "sqlite bind failed at param " i) {:rc rc :index i}))))
      (recur (inc i) (next ps)))))

(defn- read-blob
  "Read column `i` of `stmt` as a byte array, copying out of sqlite-owned
  memory immediately so the result stays valid past the next step/finalize.
  sqlite3_column_blob returns null both for a valid zero-length BLOB and for an
  allocation failure. sqlite3_errcode must therefore be checked immediately
  after a null result, before any other SQLite call can overwrite it."
  [db stmt i]
  (let [p (sqlite3-column-blob stmt i)]
    (if (ffi/null? p)
      (let [rc (sqlite3-errcode db)]
        ;; A successful sqlite3_step that produced this row commonly leaves
        ;; SQLITE_ROW as the connection's current code. SQLite documents
        ;; SQLITE_NOMEM specifically as the signal that a null column pointer
        ;; came from conversion/allocation failure; any other code here is the
        ;; legitimate zero-length BLOB case.
        (if (= rc SQLITE-NOMEM)
          (throw
           (ex-info "sqlite blob column allocation failed"
                    {:rc rc :index i}))
          (byte-array 0)))
      (let [n (sqlite3-column-bytes stmt i)]
        (when (neg? n)
          (throw
           (ex-info "sqlite blob column returned a negative length"
                    {:index i :n n})))
        (ffi/read-array p n)))))

(defn- read-row [db stmt n]
  (loop [i 0 m {}]
    (if (= i n)
      m
      (let [k (keyword (sqlite3-column-name stmt i))
            ty (sqlite3-column-type stmt i)
            v (cond
                (= ty TY-INT)   (sqlite3-column-int64 stmt i)
                (= ty TY-FLOAT) (sqlite3-column-double stmt i)
                (= ty TY-NULL)  nil
                (= ty TY-BLOB)  (read-blob db stmt i)
                :else           (sqlite3-column-text stmt i))]
        (recur (inc i) (assoc m k v))))))

(defn- finalize-outcome [stmt]
  (when-not
    (ffi/null? stmt)
    (try
      {:rc (sqlite3-finalize stmt)}
      (catch Throwable t {:error t}))))

(defn- finalize-failed? [outcome]
  (or (some? (:error outcome))
      (and (some? (:rc outcome))
           (not= SQLITE-OK (:rc outcome)))))

(defn- prepare-error [db sql rc stmt]
  (let [msg (error-message db)
        finalize (finalize-outcome stmt)
        data (cond-> {:rc rc}
               (and (some? (:rc finalize))
                    (not= SQLITE-OK (:rc finalize)))
               (assoc :db.sqlite/finalize-rc (:rc finalize))

               (some? (:error finalize))
               (assoc :db.sqlite/finalize-error (:error finalize)))]
    (ex-info (str "sqlite prepare failed"
                  (when (seq msg) (str ": " msg))
                  " — " sql)
             data)))

(defn- run-prepared [db stmt params]
  (let [outcome
        (try
          {:value
           (do
             (bind-params! stmt params)
             (let [ncol (sqlite3-column-count stmt)]
               (loop [rows (transient [])]
                 (let [r (sqlite3-step stmt)]
                   (cond
                     (= r SQLITE-ROW)
                     (recur (conj! rows (read-row db stmt ncol)))

                     (= r SQLITE-DONE)
                     (persistent! rows)

                     :else
                     (throw
                       (ex-info
                         (str "sqlite step failed"
                              (when-let [msg (error-message db)]
                                (str ": " msg)))
                         {:rc r})))))))}
          (catch Throwable t {:error t}))
        finalize (finalize-outcome stmt)]
    (if-let [primary (:error outcome)]
      ;; Jolt has no suppressed-exception mutation API. Preserve the original
      ;; bind/step/row-read throwable identity; finalization was still attempted
      ;; and checked, but a concurrent cleanup failure cannot replace it.
      (throw primary)
      (cond
        (some? (:error finalize))
        (throw (:error finalize))

        (finalize-failed? finalize)
        (throw
          (ex-info (str "sqlite finalize failed"
                        (when-let [msg (error-message db)]
                          (str ": " msg)))
                   {:rc (:rc finalize)}))

        :else
        (:value outcome)))))

(defn query
  "Run `sql` with `params` (a seq); return a vector of keyword-keyed row maps
  (empty for a non-SELECT)."
  [handle sql params]
  (let [db (live-ptr! handle)
        sql-ptr (ffi/string->ptr sql)]
    (try
      (let [pp (ffi/alloc (ffi/sizeof :pointer))]
        (try
          (ffi/write pp :pointer 0 ffi/null)
          (let [prepared
                (try
                  {:rc (sqlite3-prepare db sql-ptr -1 pp ffi/null)}
                  (catch Throwable t {:error t}))
                stmt (ffi/read pp :pointer)]
            (cond
              (some? (:error prepared))
              (do
                (finalize-outcome stmt)
                (throw (:error prepared)))

              (not= (:rc prepared) SQLITE-OK)
              (throw (prepare-error db sql (:rc prepared) stmt))

              (ffi/null? stmt)
              ;; Whitespace/comment-only SQL: prepare succeeds with nothing to
              ;; run or finalize.
              []

              :else
              (run-prepared db stmt params)))
          (finally
            (ffi/free pp))))
      (finally
        (ffi/free sql-ptr)))))

(defn changes [handle] (sqlite3-changes (live-ptr! handle)))
(defn last-insert-rowid [handle] (sqlite3-last-rowid (live-ptr! handle)))
