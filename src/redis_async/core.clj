;; Copyright 2015 Ben Ashford
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns redis-async.core
  (:require [clojure.core.async :as a]
            [clojure.string :as s]
            [redis-async.protocol :as protocol])
  (:import [jresp Client]))

;; Defaults

(defn- default-redis []
  {:host (or (System/getenv "REDIS_HOST") "localhost")
   :port 6379})

;; Commands

(defn send!
  "Send a command to a connection.  Returns a channel which will contain the
   result"
  [^jresp.pool.SingleCommandConnection con resp-msg]
  (let [ret-c (a/chan)]
    (.write con resp-msg (fn [resp]
                           (a/put! ret-c resp)))
    ret-c))

;;; TODO - check if still required
(defn- write-error-to [ch t]
  (a/put! ch (protocol/->resp t)))

;;; TODO - check if still required
(defn- drain
  "If a connection has failed, respond to the remaining incoming messages with
   an error."
  [ch t]
  (a/go-loop [cmd (a/<! ch)]
    (when-let [ret-c (:ret-c cmd)]
      (write-error-to ret-c t)
      (a/close! ret-c)
      (recur (a/<! ch)))))

;;; TODO - check if still required
(defn is-error? [v]
  (let [klass (class v)]
    (= klass jresp.protocol.Err)))

;;; TODO - replace with new-style
#_(defn- authenticate
  "If authentication details are specified, send them before anything else on
   this channel."
  [cmd-ch in-c redis]
  (let [password-c (when-let [password (:password redis)]
                     (send! cmd-ch (protocol/->resp ["AUTH" password])))
        select-c   (when-let [db (:db redis)]
                     (send! cmd-ch (protocol/->resp ["SELECT" (str db)])))]
    (when password-c
      (a/take! password-c
               (fn [response]
                 (when (is-error? response)
                   (a/put! in-c response)))))
    (when select-c
      (a/take! select-c
               (fn [response]
                 (when (is-error? response)
                   (a/put! in-c response)))))))

(defn get-connection
  "Get a connection from the pool"
  [^jresp.pool.Pool pool type]
  (case type
    :shared (.getShared pool)
    :dedicated (.getDedicated pool)
    :borrowed (.getBorrowed pool)
    :pub-sub (.getPubSub pool)
    (throw (ex-info (format "Unknown connection type: %s" type) {}))))

(defn finish-connection
  "Return a borrowed connection to the pool"
  [^jresp.pool.Pool pool
   ^jresp.pool.SingleCommandConnection con]
  (.returnBorrowed pool con))

;;; TODO - close connection functions (for borrowed/dedicated)

;; Standard send-command

(def ^:dynamic *trans-con* nil)

(defn send-cmd
  "Send a command to the appropriate pool, will use the shared connection"
  [pool command params]
  (let [con (or *trans-con* (get-connection pool :shared))]
    (send! con
           (protocol/->resp (concat command params)))))

(defn- finish-transaction [pool con finish-with]
  (let [close-ch (send! con (protocol/->resp [finish-with]))]
    (a/go
      (let [result (a/<! close-ch)]
        (finish-connection pool :borrowed con)
        result))))

(defn do-with-transaction [pool work-f]
  (let [con    (get-connection pool :borrowed)
        init-c (send! con
                      (protocol/->resp ["MULTI"]))]
    (try
      (binding [*trans-con* con]
        (work-f))
      (finish-transaction pool con "EXEC")
      (catch Throwable t
        (finish-transaction pool con "DISCARD")
        (throw t)))))

(defmacro with-transaction [pool & body]
  `(do-with-transaction ~pool (fn [] ~@body)))
