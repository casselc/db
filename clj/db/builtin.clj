(ns db.builtin
  "Load the bundled db.driver adapters. Loading this namespace registers the
  drivers but does not connect to either native library."
  (:require [clojure.string :as str]
            [db.driver :as driver]
            [db.driver.sqlite]
            [db.pg :as pg]))

(def ^:private uri-hex "0123456789ABCDEF")

(defn- hex-value [byte]
  (cond
    (and (>= byte 48) (<= byte 57)) (- byte 48)
    (and (>= byte 65) (<= byte 70)) (- byte 55)
    (and (>= byte 97) (<= byte 102)) (- byte 87)
    :else nil))

(defn- same-bytes? [left right]
  (and (= (alength left) (alength right))
       (every? (fn [i] (= (aget left i) (aget right i)))
               (range (alength left)))))

(defn- uri-decode [value component]
  ;; Work at the UTF-8 byte level so a literal `+` remains a plus (this is URI
  ;; syntax, not application/x-www-form-urlencoded) and percent escapes can
  ;; represent arbitrary UTF-8 octets. Canonical re-encoding below then prevents
  ;; encoded delimiters from becoming URI structure.
  (let [source (.getBytes (str value) "UTF-8")
        n (alength source)
        decoded
        (loop [i 0 out (transient [])]
          (if (= i n)
            (byte-array (persistent! out))
            (let [byte (bit-and (int (aget source i)) 255)]
              (if (= byte 37)
                (let [high (when (< (+ i 1) n)
                             (hex-value (bit-and (int (aget source (inc i))) 255)))
                      low (when (< (+ i 2) n)
                            (hex-value (bit-and (int (aget source (+ i 2))) 255)))]
                  (when (or (nil? high) (nil? low))
                    (throw (ex-info (str "invalid percent escape in postgres " component)
                                    {:jdbc/sql-error true :component component})))
                  (let [decoded-byte (+ (* 16 high) low)]
                    (recur (+ i 3)
                           (conj! out (if (> decoded-byte 127)
                                        (- decoded-byte 256)
                                        decoded-byte)))))
                (recur (inc i) (conj! out (aget source i)))))))
        text (String. decoded "UTF-8")]
    ;; String decoding replaces malformed UTF-8. Reject it rather than silently
    ;; changing a database name, option, or credential.
    (when-not (same-bytes? decoded (.getBytes text "UTF-8"))
      (throw (ex-info (str "invalid UTF-8 in postgres " component)
                      {:jdbc/sql-error true :component component})))
    text))

(defn- uri-component [value]
  (apply str
         (mapcat
          (fn [byte]
            (let [n (bit-and (int byte) 255)]
              (if (or (and (>= n 65) (<= n 90))
                      (and (>= n 97) (<= n 122))
                      (and (>= n 48) (<= n 57))
                      (contains? #{45 46 95 126} n))
                [(char n)]
                [\% (nth uri-hex (quot n 16)) (nth uri-hex (mod n 16))])))
          (.getBytes (str value) "UTF-8"))))

(defn- query-map [query]
  (when (seq query)
    (into {}
          (map (fn [entry]
                 (let [[key value] (str/split entry #"=" 2)]
                   [(uri-decode key :query-key)
                    (uri-decode (or value "") :query-value)]))
               (str/split query #"&")))))

(defn- option-name [key]
  (str/replace (if (keyword? key) (name key) (str key)) "-" "_"))

(def ^:private top-level-pg-options
  {:sslmode "sslmode"
   :connect-timeout "connect_timeout"
   :application-name "application_name"
   :target-session-attrs "target_session_attrs"
   :keepalives "keepalives"})

(defn- map-options [spec from-subname]
  (let [nested (merge (when (map? (:options spec)) (:options spec))
                      (when (map? (:pg/options spec)) (:pg/options spec)))
        common (into {}
                     (keep (fn [[key wire-name]]
                             (when (contains? spec key) [wire-name (get spec key)])))
                     top-level-pg-options)]
    (cond-> (merge (dissoc from-subname "user" "password") nested common)
      (and (contains? spec :options) (not (map? (:options spec))))
      (assoc "options" (:options spec)))))

(defn- query-string [options]
  (when (seq options)
    (->> options
         (map (fn [[key value]] [(option-name key) value]))
         (sort-by first)
         (map (fn [[key value]]
                (str (uri-component key) "=" (uri-component value))))
         (str/join "&"))))

(defn- invalid-authority! [message]
  ;; Never include the rejected authority: callers sometimes put credentials in
  ;; malformed host fields, and construction errors must remain safe to report.
  (throw (ex-info message {:jdbc/sql-error true :component :authority})))

(defn- valid-port [port]
  (when (some? port)
    (let [text (str port)]
      (when-not (re-matches #"[0-9]+" text)
        (invalid-authority! "invalid postgres port"))
      (let [number (try (parse-long text) (catch Throwable _ nil))]
        (when-not (and number (pos? number) (<= number 65535))
          (invalid-authority! "invalid postgres port"))
        (str number)))))

(defn- safe-host-text? [host]
  (and (not (str/blank? host))
       (not-any? #(or (Character/isWhitespace %)
                      (< (int %) 32)
                      (contains? #{\/ \? \# \@} %))
                 host)))

(defn- ipv4-address-text? [text]
  (let [parts (str/split text #"[.]" -1)]
    (and (= 4 (count parts))
         (every? (fn [part]
                   (and (re-matches #"[0-9]+" part)
                        (let [number (try (parse-long part) (catch Throwable _ nil))]
                          (and number (<= 0 number 255)))))
                 parts))))

(defn- ipv6-side-width [text]
  (if (str/blank? text)
    0
    (let [parts (str/split text #":" -1)
          ipv4 (last parts)
          ipv4? (str/includes? ipv4 ".")
          hex-parts (if ipv4? (butlast parts) parts)]
      (when (and (every? #(re-matches #"[0-9A-Fa-f]{1,4}" %) hex-parts)
                 (or (not ipv4?) (ipv4-address-text? ipv4)))
        (+ (count hex-parts) (if ipv4? 2 0))))))

(defn- ipv6-address-text? [host]
  ;; Syntax-only validation avoids DNS and distinguishes a literal from an
  ;; injected host:port. `::` must compress at least one of the eight groups;
  ;; an embedded IPv4 tail occupies two groups.
  (when (> (count (filter #(= % \:) host)) 1)
    (if-let [compression (str/index-of host "::")]
      (when-not (str/index-of (subs host (+ compression 2)) "::")
        (let [left (ipv6-side-width (subs host 0 compression))
              right (ipv6-side-width (subs host (+ compression 2)))]
          (and (some? left) (some? right) (< (+ left right) 8))))
      (= 8 (ipv6-side-width host)))))

(defn- canonical-host [host]
  (let [host (str host)]
    (when-not (safe-host-text? host)
      (invalid-authority! "invalid postgres host"))
    (cond
      (str/starts-with? host "[")
      (let [inside (when (str/ends-with? host "]")
                     (subs host 1 (dec (count host))))]
        (if (and inside
                 (= 1 (count (filter #(= % \[) host)))
                 (= 1 (count (filter #(= % \]) host)))
                 (ipv6-address-text? inside))
          host
          (invalid-authority! "invalid bracketed postgres host")))

      (or (str/includes? host "[") (str/includes? host "]"))
      (invalid-authority! "invalid bracketed postgres host")

      (str/includes? host ":")
      ;; A structured colon-bearing host is an IPv6 literal and must be bracketed
      ;; in a URI. Zone identifiers are rejected until they have an explicit
      ;; canonical encoding contract rather than being passed through ambiguously.
      (if (ipv6-address-text? host)
        (str "[" host "]")
        (invalid-authority! "invalid postgres IPv6 host"))

      :else host)))

(defn- split-authority [authority]
  (when (or (str/includes? authority "@")
            (not (safe-host-text? authority)))
    (invalid-authority! "invalid postgres subname authority"))
  (if (str/starts-with? authority "[")
    (let [close (str/index-of authority "]")]
      (when-not close
        (invalid-authority! "invalid bracketed postgres subname authority"))
      (let [host (subs authority 0 (inc close))
            suffix (subs authority (inc close))]
        (when-not (or (str/blank? suffix) (str/starts-with? suffix ":"))
          (invalid-authority! "invalid postgres subname authority"))
        [(canonical-host host)
         (when-not (str/blank? suffix) (valid-port (subs suffix 1)))]))
    (let [colons (count (filter #(= % \:) authority))]
      (when (> colons 1)
        (invalid-authority! "postgres IPv6 subname hosts must be bracketed"))
      (if (= colons 1)
        (let [[host port] (str/split authority #":" 2)]
          [(canonical-host host) (valid-port port)])
        [(canonical-host authority) nil]))))

(defn- authority [subname-authority host port]
  (if-not (str/blank? subname-authority)
    (let [[parsed-host parsed-port] (split-authority subname-authority)]
      (str parsed-host (when parsed-port (str ":" parsed-port))))
    (let [parsed-host (canonical-host (or host "127.0.0.1"))
          parsed-port (valid-port port)]
      (str parsed-host (when parsed-port (str ":" parsed-port))))))

(defn- pg-uri [{:keys [subname host port user password dbname] :as spec}]
  (if (string? spec)
    spec
    (let [sn (or subname "")
          sn (if (str/starts-with? sn "//") (subs sn 2) sn)
          [hostport db] (let [i (str/index-of sn "/")]
                          (if i [(subs sn 0 i) (subs sn (inc i))] ["" sn]))
          [db qs] (let [i (str/index-of (or db "") "?")]
                    (if i [(subs db 0 i) (subs db (inc i))] [db nil]))
          params (query-map qs)
          user (or user (get params "user"))
          password (or password (get params "password"))
          options (query-string (map-options spec params))]
      (str "postgres://"
           (when user (str (uri-component user)
                           (when password
                             (str ":" (uri-component password))) "@"))
           (authority hostport host port)
           "/" (uri-component (if (and (not (str/blank? db)) subname)
                                 (uri-decode db :database)
                                 (or dbname (:name spec) "")))
           (when options (str "?" options))))))

(def postgresql-driver
  (reify driver/Driver
    (descriptor [_]
      {:id :postgresql
       :aliases #{"postgresql" "postgres" "pgsql"}
       :uri-prefixes ["postgres://" "postgresql://"]
       :product-name "PostgreSQL"
       :capabilities {:transactions :savepoint :generated-keys :returning}
       :transaction-settings
       {:defaults {:isolation 2 :read-only false}
        :isolation
        {1 {:transaction "SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED"
            :session "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ UNCOMMITTED"}
         2 {:transaction "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"
            :session "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ COMMITTED"}
         4 {:transaction "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"
            :session "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ"}
         8 {:transaction "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"
            :session "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE"}}
        :read-only
        {true {:transaction "SET TRANSACTION READ ONLY"
               :session "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"}
         false {:transaction "SET TRANSACTION READ WRITE"
                :session "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE"}}}
       :constraints {:handle-concurrency :serialized}
       :schema-sql (fn [schema] (str "SET search_path TO " schema))})
    (open-handle [_ spec] (pg/connect (pg-uri spec)))
    (close-handle [_ handle] (pg/close handle))
    (execute-handle [_ handle sql params] (pg/execute-any handle sql params))))

(driver/register! postgresql-driver)
