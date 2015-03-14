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
            [gloss.io :as io]
            [redis-async.pool :as pool]
            [redis-async.protocol :as protocol]))

(def ^:private default-redis
  {:host "localhost"
   :port 6379})

(def pipelined-buffer-size 1000)

(defprotocol ConnectionLifecycle
  (start-connection [this])
  (stop-connection [this]))

(defn- process-stream
  "Process the stream specified by the specified connection"
  [con]
  (let [cmd-ch     (:cmd-ch con)
        in-c       (:in-c con)
        connection (:connection con)]
    (a/go-loop [cmd (a/<! cmd-ch)]
      (if (nil? cmd)
        (stop-connection con)
        (do
          (let [{:keys [ret-c cmds]} cmd
                written-c            (a/chan)]
            (a/go-loop [c (a/<! cmds)]
              (if (nil? c)
                (a/close! written-c)
                (do
                  (a/put! written-c true)
                  (->> c
                       (io/encode protocol/resp-frame)
                       io/contiguous
                       (stream/put! connection))
                  (recur (a/<! cmds)))))
            (loop [w (a/<! written-c)]
              (when w
                (let [r (a/<! in-c)]
                  (if r
                    (a/>! ret-c r)
                    (assert r "No result")))
                (recur (a/<! written-c))))
            (a/close! ret-c))
          (recur (a/<! cmd-ch)))))))

(defrecord Connection [pool connection cmd-ch in-c]
  ConnectionLifecycle
  (start-connection [this]
    (let [cmd-ch     (a/chan)
          in-c       (a/chan)
          in-stream  (io/decode-stream connection protocol/resp-frame)]
      (stream/connect in-stream in-c)
      (let [new-con (->Connection pool connection cmd-ch in-c)]
        (process-stream new-con)
        new-con)))
  (stop-connection [this]
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

(def ^:dynamic *pipe* nil)

(defn send-cmd [pool command params]
  (let [full-cmd (protocol/->resp (concat command params))]
    (if *pipe*
      (a/put! *pipe* full-cmd)
      (let [cmd-ch (:cmd-ch (pool/get-connection pool))
            ret-c  (a/chan 1)
            cmds   (a/chan)]
        (a/put! cmd-ch {:ret-c ret-c
                        :cmds  cmds})
        (a/put! cmds full-cmd)
        (a/close! cmds)
        ret-c))))

(defmacro pipelined [pool & body]
  `(binding [*pipe* (a/chan)]
     (let [cmd-ch# (:cmd-ch (pool/get-connection ~pool))
           ch#     (a/chan pipelined-buffer-size)]
       (a/put! cmd-ch# {:ret-c ch#
                        :cmds  *pipe*})
       ~@body
       (a/close! *pipe*)
       ch#)))
