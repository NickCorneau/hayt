(ns qbits.hayt.cql
  "CQL3 ref: http://cassandra.apache.org/doc/cql3/CQL.html or
https://github.com/apache/cassandra/blob/trunk/doc/cql3/CQL.textile#functions

This one is really up to date:
https://github.com/apache/cassandra/blob/cassandra-1.2/src/java/org/apache/cassandra/cql3/Cql.g
And a useful test suite: https://github.com/riptano/cassandra-dtest/blob/master/cql_tests.py"
  (:import
   (org.apache.commons.lang3 StringUtils)
   (java.nio ByteBuffer)
   (java.util Date)
   (java.net InetAddress)
   (qbits.hayt Hex)))

(declare emit-query emit-row)

;; Wraps a CQL function (a template to clj.core/format and its
;; argument for later encoding.
(defrecord CQLFn [name args])
(defrecord CQLRaw [value])
(defrecord CQLRawPreparable [value])
(defrecord CQLNamespaced [value])
(defrecord CQLComposite [value])
(defrecord CQLUserType [value])

(defn join [^java.lang.Iterable coll ^String sep]
  (when (seq coll)
    (StringUtils/join coll sep)))

(def join-and #(join % " AND "))
(def join-spaced #(join % " "))
(def join-comma #(join % ", "))
(def join-dot #(join % "."))
(def join-lf #(join % "\n" ))
(def format-eq #(str %1 " = " %2))
(def format-kv #(str %1 " : "  %2))
(def quote-string #(str "'" (StringUtils/replace % "'" "''") "'"))
(def dquote-string #(str "\"" (StringUtils/replace % "\" " "\"\"") "\""))
(def wrap-parens #(str "(" % ")"))
(def wrap-brackets #(str "{" % "}"))
(def wrap-sqbrackets #(str "[" % "]"))
(def kw->c*const #(-> (name %)
                      StringUtils/upperCase
                      (StringUtils/replaceChars \- \_)))
(def terminate #(str % ";"))

(def sequential-or-set? (some-fn sequential? set?))

(defprotocol CQLEntities
  (cql-identifier [x]
    "Encodes CQL identifiers")
  (cql-value [x]
    "Encodes a CQL value"))

(extend-protocol CQLEntities

  (Class/forName "[B")
  (cql-identifier [x]
    (cql-value (ByteBuffer/wrap x)))
  (cql-value [x]
    (cql-value (ByteBuffer/wrap x)))

  ByteBuffer
  (cql-identifier [x]
    (Hex/toHexString x))
  (cql-value [x]
    (Hex/toHexString x))

  String
  (cql-identifier [x] (dquote-string x))
  (cql-value [x] (quote-string x))

  clojure.lang.Keyword
  (cql-identifier [x] (name x))
  (cql-value [x] (str x))

  Date
  (cql-value [x]
    (.getTime ^Date x))

  InetAddress
  (cql-value [x]
    (.getHostAddress ^InetAddress x))

  ;; Collections are just for cassandra collection types, not to
  ;; generate query parts
  clojure.lang.IPersistentSet
  (cql-value [x]
    (->> (map cql-value x)
         join-comma
         wrap-brackets))

  clojure.lang.IPersistentMap
  (cql-identifier [x]
    (let [[coll k] (first x) ]
      ;; handles foo['bar'] lookups
      (str (cql-identifier coll)
           (wrap-sqbrackets (cql-value k)))))

  (cql-value [x]
    (->> x
         (map (fn [[k v]]
                (format-kv (cql-value k)
                           (cql-value v))))
         join-comma
         wrap-brackets))

  clojure.lang.Sequential
  (cql-value [x]
    (->> (map cql-value x)
          join-comma
          wrap-sqbrackets))

  CQLUserType
  (cql-identifier [x]
    (->> (:value x)
         (map (fn [[k v]]
                (format-kv (cql-identifier k)
                           (cql-value v))))
         join-comma
         wrap-brackets))
  (cql-value [x]
    (->> (:value x)
         (map (fn [[k v]]
                (format-kv (cql-identifier k)
                           (cql-value v))))
         join-comma
         wrap-brackets))


  CQLComposite
  (cql-identifier [c]
    (->> (:value c)
         (map cql-identifier)
         join-comma
         wrap-parens))
  (cql-value [c]
    (->> (:value c)
         (map cql-value)
         join-comma
         wrap-parens))


  ;; CQL Function are always safe, their arguments might not be though
  CQLFn
  (cql-identifier [{fn-name :name  args :args}]
    (str (name fn-name)
         (-> (map cql-identifier args)
             join-comma
             wrap-parens)))
  (cql-value [{fn-name :name  args :args}]
    (str (name fn-name)
         (-> (map cql-value args)
             join-comma
             wrap-parens)))

  CQLRaw
  (cql-identifier [x] (:value x))
  (cql-value [x] (:value x))

  CQLRawPreparable
  (cql-identifier [x] (:value x))
  (cql-value [x] (:value x))

  clojure.lang.Symbol
  (cql-identifier [x] (str x))
  (cql-value [x] (str x))

  CQLNamespaced
  (cql-identifier [xs]
    (join-dot (map cql-identifier (:value xs))))

  nil
  (cql-value [x] "null")

  Object
  (cql-identifier [x] x)
  (cql-value [x] x))

(def contains-key (fn []))
(def contains (fn []))

(defonce operators
  (let [ops {'= "="
             '> ">"
             '< "<"
             '<= "<="
             '>= ">="
             '+ "+"
             '- "-"
             'contains "CONTAINS"
             'contains-key "CONTAINS KEY"}]
    (reduce-kv
     (fn [m k v]
       (-> m
           (assoc (keyword k) v)
           (assoc (eval k) v)))
     {}
     ops)))

(defn operator?
  [op]
  (get operators op))

(defn option-value
  [x]
  (if (or (number? x)
          (instance? Boolean x))
    x
    (quote-string (name x))))

(defn option-map [m]
  (->> m
       (map (fn [[k v]]
              (format-kv (quote-string (name k))
                         (option-value v))))
       join-comma
       wrap-brackets))


;; secondary index clauses helpers
(defn query-cond-sequential-entry [op column value]
  (let [[column value] (if (sequential? column)
                         [(CQLComposite. column)
                          (CQLComposite. value)]
                         [column value] )
        col-name (cql-identifier column)]
    (if (identical? :in op)
      (str col-name
           " IN "
           (if (sequential-or-set? value)
             (-> (mapv cql-value value)
                 join-comma
                 wrap-parens)
             (cql-value value)))
      (str col-name
           " " (operators op) " "
           (cql-value value)))))

(defn query-cond
  [clauses]
  (->> clauses
       (map (fn [xs]
              (if (= 3 (count xs))
                (query-cond-sequential-entry (first xs)
                                             (second xs)
                                             (last xs))
                (query-cond-sequential-entry = (first xs) (second xs)))))
       join-and))

;; x and y can be an operator or a value
(defn counter [column [x y]]
  (let [identifier (cql-identifier column)]
    (->> (if (operator? x)
           [identifier (operators x) (cql-value y)]
           [(cql-value x) (operators y) identifier])
         join-spaced
         (format-eq identifier))))

(def emit
  { ;; entry clauses
   :select
   (fn [q table]
     (str "SELECT "
          (emit-row (assoc q :from table)
                    [:columns :from :where :order-by :limit :allow-filtering])))

   :insert
   (fn [q table]
     (str "INSERT INTO " (cql-identifier table) " "
          (emit-row q [:values :if-exists :using])))

   :update
   (fn [q table]
     (str "UPDATE " (cql-identifier table) " "
          (emit-row q [:using :set-columns :where :if :if-exists])))

   :delete
   (fn [{:keys [columns] :as q} table]
     (str "DELETE "
          (-> (if (identical? :* columns) (dissoc q :columns) q)
              (assoc :from table)
              (emit-row [:columns :from :using :where :if]))))

   :drop-index
   (fn [q index]
     (str "DROP INDEX "
          (emit-row (assoc q :index index) [:if-exists :index])))

   :drop-table
   (fn [q table]
     (str "DROP TABLE "
          (emit-row (assoc q :table table) [:if-exists :table])))

   :drop-column-family
   (fn [q cf]
     (str "DROP COLUMNFAMILY "
          (emit-row (assoc q :cf cf) [:if-exists :cf])))

   :drop-keyspace
   (fn [q keyspace]
     (str "DROP KEYSPACE "
          (emit-row (assoc q :ks keyspace) [:if-exists :ks])))

   :use-keyspace
   (fn [q ks]
     (str "USE " (cql-identifier ks)))

   :truncate
   (fn [q ks]
     (str "TRUNCATE " (cql-identifier ks)))

   :grant
   (fn [q perm]
     (str "GRANT "
          (emit-row (assoc q :perm perm)
                    [:perm :resource :user])))

   :revoke
   (fn [q perm]
     (str "REVOKE "
          (emit-row (assoc q :perm perm) [:perm :resource :user])))

   :create-index
   (fn [{:keys [custom with]
         :as q}
        column]
     (str "CREATE "
          (when custom "CUSTOM ")
          "INDEX "
          (emit-row q [:if-exists :index-name :on])
          " " (wrap-parens (cql-identifier column))
          (when (and custom with)
            (str " " ((emit :with) q with)))))

   :create-trigger
   (fn [{:keys [table using] :as q} name]
     (str "CREATE TRIGGER " (cql-identifier name) " "
          (emit-row q [:on :using])))

   :drop-trigger
   (fn [q name]
     (str "DROP TRIGGER " (cql-identifier name) " "
          (emit-row q [:on])))

   :create-user
   (fn [q user]
     (str "CREATE USER " (cql-identifier user) " "
          (emit-row q [:password :superuser])))

   :alter-user
   (fn [q user]
     (str "ALTER USER " (cql-identifier user) " "
          (emit-row q [:password :superuser])))

   :drop-user
   (fn [q user]
     (str "DROP USER " (cql-identifier user)
          (when-let [exists (emit-row q [:if-exists])]
            (str " " exists))))

   :list-users
   (constantly "LIST USERS")

   :perm
   (fn [q perm]
     (let [raw-perm (kw->c*const perm)]
       (str "PERMISSION" (when (= "ALL" raw-perm) "S") " " raw-perm)))

   :list-perm
   (fn [q perm]
     (str "LIST "
          (emit-row (assoc q :perm perm) [:perm :resource :user :recursive])))

   :create-table
   (fn [q table]
     (str "CREATE TABLE "
          (emit-row (assoc q :table table)
                    [:if-exists :table :column-definitions :with])))

   :create-type
   (fn [q type]
     (str "CREATE TYPE "
          (emit-row (assoc q :type type)
                    [:if-exists :type :column-definitions])))

   :alter-table
   (fn [q table]
     (str "ALTER TABLE " (cql-identifier table) " "
          (emit-row q [:alter-column :add-column :rename-column :drop-column :with])))

   :alter-type
   (fn [q type]
     (str "ALTER TYPE " (cql-identifier type) " "
          (emit-row q [:alter-column :add-column :rename-column :drop-column])))

   :alter-columnfamily
   (fn [q cf]
     (str "ALTER COLUMNFAMILY " (cql-identifier cf) " "
          (emit-row q [:alter-column :add-column :rename-column :drop-column :with])))

   :alter-keyspace
   (fn [q ks]
     (str "ALTER KEYSPACE " (cql-identifier ks) " "
          (emit-row q [:with])))

   :create-keyspace
   (fn [q ks]
     (str "CREATE KEYSPACE "
          (emit-row (assoc q :ks ks) [:if-exists :ks :with])))

   :resource
   (fn [q resource]
     ((emit :on) q resource))

   :user
   (fn [q user]
     (cond
      (contains? q :list-perm)
      ((emit :of) q user)

      (contains? q :revoke)
      ((emit :from) q user)

      (contains? q :grant)
      ((emit :to) q user)))

   :on
   (fn [q on]
     (str "ON " (cql-identifier on)))

   :to
   (fn [q to]
     (str "TO " (cql-identifier to)))

   :of
   (fn [q on]
     (str "OF " (cql-identifier on)))

   :from
   (fn [q table]
     (str "FROM " (cql-identifier table)))

   :into
   (fn [q table]
     (str "INTO " (cql-identifier table)))

   :columns
   (fn [q columns]
     (if (sequential? columns)
       (join-comma (map cql-identifier columns))
       (cql-identifier columns)))

   :where
   (fn [q clauses]
     (str "WHERE " (query-cond clauses)))

   :if
   (fn [q clauses]
     (str "IF " (query-cond clauses)))

   :if-exists
   (fn [q b]
     (str "IF " (when (not b) "NOT ") "EXISTS"))

   :order-by
   (fn [q columns]
     (->> columns
          (map (fn [col-values] ;; Values are a pair of col and order
                 (join-spaced (map cql-identifier col-values))))
          join-comma
          (str "ORDER BY ")))

   :primary-key
   (fn [q primary-key]
     (->> (if (sequential? primary-key)
            (->> primary-key
                 (map (fn [pk]
                        (if (sequential? pk)
                          (->  (map cql-identifier pk)
                               join-comma
                               wrap-parens)
                          (cql-identifier pk))))
                 join-comma)
            (cql-identifier primary-key))
          wrap-parens
          (str "PRIMARY KEY ")))

   :column-definitions
   (fn [q column-definitions]
     (->> column-definitions
          (mapv (fn [[k & xs]]
                  (if (identical? :primary-key k)
                    ((:primary-key emit) q (first xs))
                    (join-spaced (map cql-identifier (cons k xs))))))
          join-comma
          wrap-parens))

   :limit
   (fn [q limit]
     (str "LIMIT " (cql-value limit)))

   :values
   (fn [q x]
     (str (wrap-parens (join-comma (map #(cql-identifier (first %)) x)))
          " VALUES "
          (wrap-parens (join-comma (map #(cql-value (second %)) x)))))

   :set-columns
   (fn [q values]
     (->> values
          (map (fn [[k v]]
                 (if (and (sequential? v)
                          (some operator? v))
                   (counter k v)
                   (format-eq (cql-identifier k)
                              (cql-value v)))))
          join-comma
          (str "SET ")))

   :using
   (fn [q args]
     (str "USING "
          (if (coll? args)
            (->> args
                 (map (fn [[n value]]
                        (str (-> n name StringUtils/upperCase)
                             " " (cql-value value))))
                 join-and)
            (option-value args))))

   :compact-storage
   (fn [q v]
     (when v "COMPACT STORAGE"))

   :allow-filtering
   (fn [q v]
     (when v "ALLOW FILTERING"))

   :alter-column
   (fn [q [identifier type]]
     (format "ALTER %s TYPE %s"
             (cql-identifier identifier)
             (cql-identifier type)))

   :rename-column
   (fn [q [old-name new-name]]
     (format "RENAME %s TO %s"
             (cql-identifier old-name)
             (cql-identifier new-name)))

   :add-column
   (fn [q [identifier type]]
     (format "ADD %s %s"
             (cql-identifier identifier)
             (cql-identifier type)))

   :drop-column
   (fn [q identifier]
     (str "DROP " (cql-identifier identifier)))

   :clustering-order
   (fn [q columns]
     (->> columns
          (map (fn [col-values] ;; Values are a pair of col and order
                 (join-spaced (map cql-identifier col-values))))
          join-comma
          wrap-parens
          (str "CLUSTERING ORDER BY ")))

   :with
   (fn [q value-map]
     (->> (for [[k v] value-map]
            (if-let [with-entry (k emit)]
              (with-entry q v)
              (format-eq (cql-identifier k)
                         (if (map? v)
                           (option-map v)
                           (option-value v)))))
          join-and
          (str "WITH ")))

   :password
   (fn [q pwd]
     ;; not sure if its a cql-id or cql-val
     (str "WITH PASSWORD " (cql-identifier pwd)))

   :superuser
   (fn [q superuser?]
     (if superuser? "SUPERUSER" "NOSUPERUSER"))

   :recursive
   (fn [q recursive]
     (when-not recursive "NORECURSIVE"))

   :index-column
   (fn [q index-column]
     (wrap-parens (cql-identifier index-column)))

   :batch
   (fn [{:keys [logged counter]
         :as q} queries]
     (str "BEGIN"
          (when-not logged " UNLOGGED")
          (when counter " COUNTER")
          " BATCH "
          (when-let [using (:using q)]
            (str ((emit :using) q using) " \n"))
          (->> queries
               (remove nil?)
               (map emit-query)
               join-lf)
          "\n APPLY BATCH"))

   :queries
   (fn [q queries]
     (->> (str "\n" (join-lf (map emit-query queries)) "\n")))})

(def emit-catch-all (fn [q x] (cql-identifier x)))

(def entry-clauses #{:select :insert :update :delete :use-keyspace :truncate
                     :drop-index :drop-table :drop-keyspace :drop-columnfamily
                     :create-index :create-trigger :drop-trigger :grant :revoke
                     :create-user :alter-user :drop-user :list-users :list-perm
                     :batch :create-table :alter-table :alter-columnfamily
                     :alter-keyspace :create-keyspace :create-type :alter-type})

(defn find-entry-clause
  "Finds entry point key from query map"
  [qmap]
  (some entry-clauses (keys qmap)))

(defn emit-row
  [row template]
  (->> template
       (map (fn [token]
              (when (contains? row token)
                ((get emit token emit-catch-all) row (token row)))))
       (remove nil?)
       (join-spaced)))

(defn emit-query [query]
  (let [entry-point (find-entry-clause query)]
    (terminate ((emit entry-point) query (entry-point query)))))

(defn ->raw
  "Compiles a hayt query into its raw/string value"
  [query]
  (emit-query query))
