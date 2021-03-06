;;;; Simple journal-based persistence for Clojure

;;;; by Sergey Didenko
;;;; last updated Sep 11, 2011

;;;; Copyright (c) Sergey Didenko, 2011. All rights reserved.  The use
;;;; and distribution terms for this software are covered by the Eclipse
;;;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;;; which can be found in the file epl-v10.html at the root of this
;;;; distribution.  By using this software in any fashion, you are
;;;; agreeing to be bound by the terms of this license.  You must not
;;;; remove this notice, or any other, from this software.

(ns  #^{
       :author "Sergey Didenko",
       :doc "Simple journal-based persistence for Clojure

Basics:

See README.

WARNING! Do not use atoms inside transaction handlers.
Atom actions are not rollbacked in a failing dosync block.

Apply-transaction macro uses the smart buffer,
apply-transaction-and-block writes immediately, see their docs.

The module switches the current journal file with the given interval,
to make it easy to backup the live system.

Usage:

(init-db)
...
(apply-transaction transaction-wo-dosync1 param1 param2 param3)
(apply-transaction transaction-wo-dosync2)
(apply-transaction transaction-wo-dosync3 param1)
...
(shutdown-agents)

OR

(init-db)
...
(apply-transaction-and-block transaction-wo-dosync1 param1 param2 param3)
(apply-transaction-and-block transaction-wo-dosync2)
(apply-transaction-and-block transaction-wo-dosync3 param1)
...
(shutdown-agents)


Notes:

- Snapshotting is not yet implemented.

- Currently str function is used to log transaction parameters in a readable way

- Relies on the assumption that messages sent to an agent from a locked area
will save their order when sent from different threads. This concludes from:

1. the agent contract ( messages sent from the same thread are not reordered)
2. all transactions are applied and messages sent in the single locking area
3. the Clojure code does not make any aditional messages reordering
after they are put into agent queues

"}
    persister.core

  (:import (java.io FileOutputStream File PrintWriter OutputStreamWriter
                    BufferedReader InputStreamReader FileInputStream))
  (:require [clojure.string :as string]
            [persister.sql :as sql]))

(def using-db?
  (atom false))

;;; Temporaly adapted from deprecated clojure.contrib.duck-streams
(defn read-lines
  "Like clojure.core/line-seq but opens f with reader.  Automatically
  closes the reader AFTER YOU CONSUME THE ENTIRE SEQUENCE."
  [^String f]
  (let [read-line (fn this [^BufferedReader rdr]
                    (lazy-seq
                     (if-let [line (.readLine rdr)]
                       (cons line (this rdr))
                       (.close rdr))))]
    ;; 
    (read-line
     (BufferedReader. (InputStreamReader. (FileInputStream. (File. f)))))))
    ;; (read-line (reader f))))

(def buffering-agent
  (agent {
          :pending-transactions []
          :buffer-first-transaction-id 0}) )

(def writing-agent (agent {
                                     :fos nil,
                                     :writer nil,
                                     :journal-creation-time nil,
                                     :directory "database"
                                     :file-change-interval 1000 }))

;;; Used to check if there is an ongoing write operation (in this module)
(def io-indicator-lock (java.util.concurrent.locks.ReentrantLock.) )

(def transaction-lock (java.util.concurrent.locks.ReentrantLock.) )

(def transaction-counter (atom 0M))

 ;;; change journal file regularly
(defn- time-to-change-journal-file
  [journal-creation-time interval]
  (not (when journal-creation-time
         (< (- (System/currentTimeMillis) journal-creation-time) interval) )))

(defn- change-journal-file-on-time [agent-state first-transaction-id]
  (let [
        journal-creation-time (:journal-creation-time agent-state)
        writer (:writer agent-state)
        ]
    (if (time-to-change-journal-file journal-creation-time (:file-change-interval agent-state))
      (do
        ;; close the old file
        (when journal-creation-time (.close writer))
        ;; open the new file
        (let [new-creation-time (System/currentTimeMillis)
              filename (str (:directory agent-state) "/" first-transaction-id ".journal")
              fos (FileOutputStream. filename)
              writer (PrintWriter. (OutputStreamWriter. fos "UTF-8"))
              ]
          (assoc agent-state :fos fos :writer writer :journal-creation-time new-creation-time)
          )
        )
      agent-state )))

(defn- to-vec
  "the serialization/deserialization is very weak with lists. You can't tell
   if the list is a list or is a function call."
  [x]
  (if (seq? x)
    (vec (map to-vec x))
    x))

(defn serialized-transaction
  [transaction-id & transaction-params]
  (str "(" (string/join " " (map (comp pr-str to-vec) transaction-params)) ") ;" transaction-id) )

(declare try-flushing-smart-buffer)

(defn- log-to-file [agent-state serialized-transaction first-transaction-id]
  ;; using lock only to indicate that there is an ongoing file operation
  (.lock io-indicator-lock)
  (try
    (let [
          new-agent-state (change-journal-file-on-time agent-state first-transaction-id)
          filename (:filename new-agent-state)
          fos (:fos new-agent-state)
          writer (:writer new-agent-state)
          ]
      (.print writer (str serialized-transaction "\n"))
      (.flush writer)
      (.. fos getFD sync)
      new-agent-state )

    (finally
     (.unlock io-indicator-lock)
     (try-flushing-smart-buffer))))

(defn persist-string [serialized-transaction first-transaction-id]
  (send writing-agent log-to-file serialized-transaction first-transaction-id) )

(defn- log-to-smart-buffer
  [agent-state serialized-transaction first-transaction-id]
  (let [
        ongoing-transaction (.isLocked io-indicator-lock)
        pending-transactions (:pending-transactions agent-state)

        new-buffer-first-transaction-id
        (if (and serialized-transaction (empty? pending-transactions))
          first-transaction-id
          (:buffer-first-transaction-id agent-state))

        new-pending-transactions
        (if serialized-transaction
          (conj  pending-transactions serialized-transaction)
          pending-transactions)
        ]
    (if (or ongoing-transaction (empty? new-pending-transactions ) )
      (assoc agent-state
        :pending-transactions new-pending-transactions
        :buffer-first-transaction-id new-buffer-first-transaction-id)
      (do
        (persist-string (string/join "\n" new-pending-transactions ) new-buffer-first-transaction-id)
        (assoc agent-state :pending-transactions []) ))))

(defn persist-string-in-smart-buffer
  [serialized-transaction first-transaction-id]
  (send buffering-agent log-to-smart-buffer serialized-transaction first-transaction-id) )

(def try-flushing-smart-buffer
  (partial persist-string-in-smart-buffer nil nil))

(defmacro apply-transaction
  "Apply transaction to the root object and write it to disk unless
    the transaction fails. Disk writes are made through the buffering
    agent, so there is a small chance to lose the latest succesfully applied
    transactions on account of disk failure.
    Use apply-transaction-and-block if you want to further reduce the chance of
    the possible loss at the expense of reduced throughput.
    Warning: do not mix the both macros in the same workflow!"
  [transaction-fn & transaction-fn-arg]
  `(if @using-db?
    (sql/apply-transaction ~transaction-fn ~@transaction-fn-arg) 
    (locking transaction-lock
      (let [res# (dosync (~transaction-fn ~@transaction-fn-arg))
            transaction-id# (swap! transaction-counter inc)]
        (persist-string-in-smart-buffer
          (serialized-transaction transaction-id# '~transaction-fn ~@transaction-fn-arg)
          transaction-id#)
        res# ))))

(defmacro apply-transaction-and-block
  "Apply transaction to the root object and block until it is flushed to disk.
    Blocking happens outside the transaction, so there is a really small chance
    that in-memory changes will be visible from other threads considerably
    earlier than disk flush happens.
    Use apply-transaction if you want better throughput at the expense of losing
    more transactions on account of disk failure.
    Warning: do not mix the both macros in the same workflow!"
  [transaction-fn & transaction-fn-arg]
  `(if @using-db?
     (throw (UnsupportedOperationException. ""))
     (locking transaction-lock
       (let [res# (dosync (~transaction-fn ~@transaction-fn-arg))
             transaction-id# (swap! transaction-counter inc)]
         (persist-string
           (serialized-transaction transaction-id# '~transaction-fn ~@transaction-fn-arg)
           transaction-id#)
         (await writing-agent)
         res# ))))

(defn- initialize-wr-agent [agent-state data-directory file-change-interval-in-seconds]
  (assoc agent-state
    :directory data-directory
    :file-change-interval (* 1000 file-change-interval-in-seconds) ))

(defn- db-file-names [data-directory re]
  (map #(BigDecimal. %)
       (filter #(not( nil? %))
               (map #(second (re-matches re (.getName %)))
                    (seq (.listFiles (java.io.File. data-directory))) ))))

(defn- journal-numbers [data-directory]
  (db-file-names data-directory #"(\d+)\.journal$"))

(defn- snapshot-numbers [data-directory]
  (db-file-names data-directory #"(\d+)\.snapshot$"))

;;; 
;;; 
(defn- make-str-join-n
  "returns the function that joins consecutive items into string, decorates it,
and returns sequence ([processed-number-accumulator joined-items-chunk]...)
"
  [n start-str join-str end-str]
  (fn joinn [coll acc-size]
    (lazy-seq
     (when-let [s (seq coll) ]
       (let [tr-list (take n s)
             new-acc-size (+ acc-size (count tr-list))]
         (cons
          [new-acc-size
           (str start-str (apply string/join (cons join-str (list tr-list))) end-str)
           ]
          (joinn (drop n s) new-acc-size ) ))))))

(defn- init-db*
  "Make sure to call it before any apply-transaction* call"
  [data-directory file-change-interval transaction-chunk-size]
  ;; create data directory if it does not exist
  (let [data-dir (File. data-directory )]
    (if (.exists data-dir)
      (when-not
        (.isDirectory data-dir)
        (throw (RuntimeException. (str "\"" data-dir "\" must be a directory"))) )
      (when-not
        (.mkdir data-dir)
        (throw (RuntimeException. (str "Can't create database directory \"" data-dir "\""))) )))
  
  ;; initialize agent
  (send writing-agent initialize-wr-agent data-directory file-change-interval)
  ;; load transactions
  (let [str-join-dosync (make-str-join-n transaction-chunk-size "(dosync\n" "\n" "\n)")]
    (doseq [
            journal-number (sort (journal-numbers data-directory))
            [last-transaction-id chunk-to-load]
            (str-join-dosync
              (read-lines (str data-directory "/" journal-number ".journal"))
              (dec journal-number))
            ]
      (load-string chunk-to-load)
      (reset! transaction-counter last-transaction-id) ))) 
  

(defn init-db
  "Make sure to call it before any apply-transaction* call"
  [& {:keys [db-url data-directory file-change-interval transaction-chunk-size]}]
  (if db-url 
    (do (sql/init-db db-url) (reset! using-db? true))
    (init-db* (or data-directory "database") 
              (or file-change-interval (* 60 15))
              (or transaction-chunk-size 1000))))
