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
            [redis-async.pool :as pool]
            [redis-async.protocol :as protocol])
  (:import [jresp Client]))

;; Defaults

(def ^:private default-redis
  {:host "localhost"
   :port 6379})

;; Connections

(defprotocol ConnectionLifecycle
  (start-connection [this redis client post-con-f])
  (stop-connection [this]))

(defn- write-error-to [ch t]
  (a/put! ch (protocol/->resp t)))

(defn- drain
  "If a connection has failed, respond to the remaining incoming messages with
   an error."
  [ch t]
  (a/go-loop [cmd (a/<! ch)]
    (when-let [ret-c (:ret-c cmd)]
      (write-error-to ret-c t)
      (a/close! ret-c)
      (recur (a/<! ch)))))

(def ^:private pipelineable-size 16)

(defn- send-commands
  "Asynchronously sends commands to Redis."
  [^jresp.Connection connection cmd-ch ret-c-c]
  (let [end (atom false)]
    (a/go
      (try
        (loop []
          (let [[frames
                 ret-cs] (loop [frames []
                                ret-cs []]
                           (let [[val ch] (if (empty? frames)
                                            [(a/<! cmd-ch) cmd-ch]
                                            (a/alts! [cmd-ch] :default :no-command))]
                             (cond
                               (= val :no-command)
                               [frames ret-cs]

                               (nil? val)
                               (do
                                 (reset! end true)
                                 [frames ret-cs])

                               :else
                               (let [[cmd ret-c] val]
                                 (recur (conj frames cmd)
                                        (conj ret-cs ret-c))))))]
            (when-not (empty? frames)
              (.write connection frames)
              (doseq [ret-c ret-cs]
                (a/>! ret-c-c ret-c))))
          (if @end
            (a/close! ret-c-c)
            (recur)))
        (catch Throwable t
          (a/close! ret-c-c)
          t)))))

(defn- pub-sub-stream
  "Process the stream as used in pub-sub connections"
  [con]
  (let [cmd-ch                 (:cmd-ch con)
        ^jresp.Connection connection (:connection con)]
    (a/go
      (try
        (loop []
          (let [frame (a/<! cmd-ch)]
            (.write connection (list frame)))
          (recur))
        (catch Throwable t
          t)))))

(defn- process-stream
  "Process the stream specified by the specified connection"
  [con]
  (let [cmd-ch     (:cmd-ch con)
        in-c       (:in-c con)
        connection (:connection con)
        ret-c-c    (a/chan pipelineable-size)]
    (a/go
      (try
        (let [c-c (send-commands connection cmd-ch ret-c-c)]
          (loop [ret-c (a/<! ret-c-c)]
            (if (nil? ret-c)
              (do
                (stop-connection con)
                (when-let [send-commands-result (a/<! c-c)]
                  (throw send-commands-result)))
              (do
                (a/>! ret-c (a/<! in-c))
                (a/close! ret-c)
                (recur (a/<! ret-c-c))))))
        (catch Throwable t
          (drain cmd-ch t)
          (stop-connection con)
          (a/close! cmd-ch)
          t)))))

(defn is-error? [v]
  (let [klass (class v)]
    (= klass jresp.protocol.Err)))

(defn send!
  "Send a RESP object to the channel, returns a channel from which the result
   can be read."
  [cmd-ch full-cmd]
  {:pre [(not (nil? cmd-ch))
         (not (nil? full-cmd))]}
  (let [ret-c (a/chan 1)]
    (if (a/put! cmd-ch [full-cmd ret-c])
      ret-c)))

(defn- authenticate
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

(defrecord Connection [pool connection cmd-ch in-c]
  ConnectionLifecycle
  (start-connection [this redis client post-con-f]
    (let [cmd-ch   (a/chan)
          in-c     (a/chan pipelineable-size)
          con      (.makeConnection ^Client client
                                    (proxy [jresp.Responses] []
                                      (responseReceived [resp]
                                        (a/put! in-c resp))))]
      (authenticate cmd-ch in-c redis)
      (let [new-con (->Connection pool con cmd-ch in-c)]
        (when post-con-f
          (post-con-f new-con))
        new-con)))
  (stop-connection [this]
    (pool/close-connection pool this)
    (->Connection pool nil nil nil)))

(defmethod clojure.core/print-method Connection [x writer]
  (.write writer (str (class x) "@" (System/identityHashCode x))))

(defn- make-connection [pool]
  (->Connection pool nil nil nil))

;; Pools

(defn- make-connection-factory [redis & [post-con-f]]
  (let [redis  (merge default-redis redis)
        client (Client. (:host redis) (:port redis))]
    (reify pool/ConnectionFactory
      (new-con [_ pool]
        (-> (make-connection pool)
            (start-connection redis client post-con-f)))
      (close-con [_ con]
        (assert (not (nil? con)))
        (stop-connection con)))))

(defn make-pool
  "A single redis-async connection pool consists of multiple pools depending on
   the type of command which will be run."
  [redis]
  (let [connection-factory (make-connection-factory redis process-stream)]
    (atom {:shared    (pool/make-shared-connection connection-factory)
           :pub-sub-c (pool/make-shared-connection
                       (make-connection-factory redis
                                                pub-sub-stream))
           :borrowed  (pool/make-borrowed-connection connection-factory)
           :dedicated (pool/make-dedicated-connection connection-factory)})))

(defn get-connection [pool type]
  (let [c (promise)]
    (swap! pool (fn [pool]
                  (let [p       (pool type)
                        [con p] (pool/get-connection p)]
                    (deliver c con)
                    (assoc pool type p))))
    @c))

(defn finish-connection [pool type con]
  (swap! pool (fn [pool]
                (let [p (pool type)]
                  (assoc pool type (pool/finish-connection p con))))))

(defn close-connection [pool type con]
  (swap! pool (fn [pool]
                (let [p (pool type)]
                  (assoc pool type (pool/close-connection p con))))))

(defn close-pool [pool]
  (swap! pool (fn [pool]
                (->> (dissoc pool :misc)
                     (map (fn [[k v]]
                            [k (pool/close-all v)]))
                     (into {})))))

;; Standard send-command

(def ^:dynamic *trans-con* nil)

(defn send-cmd [pool command params]
  (let [con (or *trans-con* (get-connection pool :shared))]
    (if-let [ret-c (send! (:cmd-ch con)
                          (protocol/->resp (concat command params)))]
      ret-c
      (throw (ex-info "Command-channel closed!" {:cmd-ch con})))))

(defn- finish-transaction [pool con finish-with]
  (let [close-ch (send! (:cmd-ch con) (protocol/->resp [finish-with]))]
    (a/go
      (let [result (a/<! close-ch)]
        (finish-connection pool :borrowed con)
        result))))

(defn do-with-transaction [pool work-f]
  (let [con    (get-connection pool :borrowed)
        cmd-ch (:cmd-ch con)
        init-c (send! cmd-ch
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
