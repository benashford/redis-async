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
  (:refer-clojure :exclude [send])
  (:require [clojure.core.async :as a]
            [clojure.string :as s]
            [redis-async.protocol :as protocol])
  (:import [jresp Client Responses]
           [jresp.pool Pool SingleCommandConnection]))

;; Defaults

(defn- default-redis []
  {:host (or (System/getenv "REDIS_HOST") "localhost")
   :port 6379})

;; is? functions

(defn is-error? [v]
  (let [klass (class v)]
    (= klass jresp.protocol.Err)))

(defn is-end-of-channel? [v]
  (let [klass (class v)]
    (= klass jresp.protocol.EndOfResponses)))

;; Response handlers

(defn- make-single-response-handler
  "Make a response handler, that writes to a specific channel"
  [ret-c]
  (reify Responses
    (responseReceived [this resp]
      (a/put! ret-c resp)
      (a/close! ret-c))))

(defn make-stream-response-handler
  "Make a response handler that streams to a specific channel"
  [ret-c]
  (reify Responses
    (responseReceived [this resp]
      (if (is-end-of-channel? resp)
        (a/close! ret-c)
        (a/put! ret-c resp)))))

;; Commands

(defn send
  "Send a command to a connection.  Returns a channel which will contain the
   result"
  [^SingleCommandConnection con resp-msg]
  (let [ret-c  (a/chan)
        resp-h (make-single-response-handler ret-c)]
    (.write con resp-msg resp-h)
    ret-c))

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

(defn close-connection
  "Close a dedicated connection once finished"
  [^jresp.Connection con]
  (.stop con))

;; Standard send-command

(def ^:dynamic *trans-con* nil)

(defn send-cmd
  "Send a command to the appropriate pool, will use the shared connection"
  [pool command params]
  (let [con     (or *trans-con* (get-connection pool :shared))
        payload (protocol/->resp (concat command params))]
    (send con payload)))

(defn- finish-transaction [pool con finish-with]
  (let [close-ch (send con (protocol/->resp [finish-with]))]
    (a/go
      (let [result (a/<! close-ch)]
        (finish-connection pool con)
        result))))

(defn do-with-transaction [pool work-f]
  (let [con    (get-connection pool :borrowed)
        init-c (send con
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

;; Pool management

(defn make-pool [connection-info]
  (let [connection-info (merge (default-redis) connection-info)
        {host :host
         port :port}    connection-info
        client          (Client. host port)]
    (if-let [password (:password connection-info)]
      (.setPassword client password))
    (if-let [db (:db connection-info)]
      (.setDb client (int db)))
    (Pool. client)))

(defn close-pool [^Pool pool]
  (.shutdown pool))
