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

(defn- open-connection
  "Given a map containing connection details to a redis instance, returns a
   channel through which requests can be made.  Closing the channel will
   shutdown"
  [redis]
  (let [redis      (merge default-redis redis)
        connection @(tcp/client redis)
        in-stream  (io/decode-stream connection protocol/resp-frame)
        in-c       (a/chan)
        cmd-ch     (a/chan)]
    (stream/connect in-stream in-c)
    (a/go-loop [cmd (a/<! cmd-ch)]
      (if (nil? cmd)
        (stream/close! connection)
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
                  (a/>! ret-c (if r r :nil)))
                (recur (a/<! written-c))))
            (a/close! ret-c))
          (recur (a/<! cmd-ch)))))
    cmd-ch))

(defn make-pool
  "Make a connection pool to a specific redis thing"
  [redis]
  (pool/make-pool (reify pool/ConnectionFactory
                    (test-con [this con] true)
                    (new-con [this] (open-connection redis)))))

(def ^:dynamic *pipe* nil)

(defn- coerce-to-string [param]
  (cond
   (string? param) param
   (keyword? param) (name param)
   :else (str param)))

(defn send-cmd [pool command & params]
  (let [full-cmd (concat command (map coerce-to-string params))]
    (if *pipe*
      (a/put! *pipe* full-cmd)
      (let [cmd-ch (pool/get-connection pool)
            ret-c  (a/chan 1)
            cmds   (a/chan)]
        (a/put! cmd-ch {:ret-c ret-c
                        :cmds  cmds})
        (a/put! cmds full-cmd)
        (a/close! cmds)
        ret-c))))

(defmacro pipelined [pool & body]
  `(binding [*pipe* (a/chan)]
     (let [cmd-ch# (pool/get-connection ~pool)
           ch#     (a/chan pipelined-buffer-size)]
       (a/put! cmd-ch# {:ret-c ch#
                        :cmds  *pipe*})
       ~@body
       (a/close! *pipe*)
       ch#)))
