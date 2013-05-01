(ns persister.sql
  "requires setup of an empty database.
   table(s) will be automatically created."
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]))

(def transaction-lock (java.util.concurrent.locks.ReentrantLock.))

(def ^:private db-url* (atom nil))

(defn ^:private to-vec
  "the serialization/deserialization is very weak with lists. You can't tell
   if the list is a list or is a function call."
  [x]
  (if (seq? x)
    (vec (map to-vec x))
    x))

(defmacro apply-transaction
  "Apply transaction to the root object and commits it unless
    the transaction fails."
  [f & args]
  `(locking transaction-lock
     (let [res# (dosync (~f ~@args))]
       (sql/with-connection @db-url*
         (sql/insert-record :persister 
                            {:text  
                             (format "(%s %s)" '~f (str/join " " (map (comp pr-str to-vec) (list ~@args))))}))
       res#)))

(defn ^:private is-derby?
  [db-url]
  (= (:subprotocol db-url) "derby"))

(defn init-db
  [db-url]
  (swap! db-url* (constantly db-url))
  (sql/with-connection db-url
    (try 
      (sql/create-table :persister 
                        (if (is-derby? db-url) [:id :integer "NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)" "PRIMARY KEY"] 
                                               [:id :serial])
                        (if (is-derby? db-url) [:text "varchar(2048)"] 
                                               [:text :text]))
      (catch Exception _ (println "PERSISTER: db already exists, didn't create tables.")))
    (sql/with-query-results rows
      ["SELECT * from persister order by id"]
      (doseq [row rows]
        (load-string (format "(dosync\n%s\n\n)" (:text row)))))))

(defn reset-db
  "for testing only"
  []
  (sql/with-connection @db-url*
    (sql/drop-table "persister"))) 
    
