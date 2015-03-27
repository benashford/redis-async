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
  (:require [aleph.tcp :as tcp]
            [clojure.core.async :as a]
            [clojure.string :as s]
            [manifold.stream :as stream]
            [redis-async.pool :as pool :refer [close-connection]]
            [redis-async.protocol :as protocol]))

(def ^:private default-redis
  {:host "localhost"
   :port 6379})

(defprotocol ConnectionLifecycle
  (start-connection [this])
  (stop-connection [this]))

(defrecord ClientErr [t]
  protocol/RespType
  (get-type [this] :client-err)
  (->clj [this]
    (let [msg (.getMessage t)]
      (ex-info (str "Error talking to Redis: " msg) {:type  :redis-client
                                                     :msg   msg
                                                     :cause t}))))

(defn- write-error-to [ch t]
  (a/put! ch (->ClientErr t)))

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
  [connection cmd-ch ret-c-c]
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
              (stream/put! connection (->> frames
                                           protocol/encode-all))
              (doseq [ret-c ret-cs]
                (a/>! ret-c-c ret-c))))
          (if @end
            (a/close! ret-c-c)
            (recur)))
        (catch Throwable t
          (a/close! ret-c-c)
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

(defrecord Connection [pool connection cmd-ch in-c]
  ConnectionLifecycle
  (start-connection [this]
    (let [cmd-ch   (a/chan)
          in-raw-c (a/chan pipelineable-size)
          in-c     (a/chan pipelineable-size)]
      (stream/connect connection in-raw-c)
      (protocol/decode in-raw-c in-c)
      (let [new-con (->Connection pool connection cmd-ch in-c)]
        (process-stream new-con)
        new-con)))
  (stop-connection [this]
    (pool/close-connection pool this)
    (stream/close! connection)
    (->Connection pool nil nil nil)))

(defmethod clojure.core/print-method Connection [x writer]
  (.write writer (str (class x) "@" (System/identityHashCode x))))

(defn- make-connection [pool redis]
  (let [redis (merge default-redis redis)
        con   @(tcp/client redis)]
    (->Connection pool con nil nil)))

(defn make-pool
  "Make a connection pool to a specific redis thing"
  [redis]
  (pool/make-pool (reify pool/ConnectionFactory
                    (test-con [this con] true)
                    (new-con [this pool]
                      (->> (make-connection pool redis)
                           start-connection))
                    (close-con [this con] (stop-connection con)))))

(defn close-pool [pool]
  (pool/close-pool pool))

(defn send-cmd [pool command params]
  (let [full-cmd (protocol/->resp (concat command params))]
    (let [cmd-ch (:cmd-ch (pool/get-connection pool))
          ret-c  (a/chan 1)]
      (if (a/put! cmd-ch [full-cmd ret-c])
        ret-c
        (throw (ex-info "Command-channel closed!" {:cmd-ch cmd-ch}))))))
