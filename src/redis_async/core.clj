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

(defn- write-cmd [connection cmd]
  (let [cmd-lines (cons (str "*" (count cmd))
                        (mapcat (fn [p]
                                  [(str "$" (count p)) p])
                                cmd))
        bytes (io/encode-all protocol/resp-frame-out cmd-lines)]
    (stream/put! connection (io/contiguous bytes))
    true))

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
    (a/go
      (loop [cmd (a/<! cmd-ch)]
        (when cmd
          (let [{:keys [ret-c cmds]} cmd
                written-c            (a/map #(write-cmd connection %)
                                            [cmds]
                                            pipelined-buffer-size)]
            (loop [w (a/<! written-c)]
              (when w
                (let [r (a/<! in-c)]
                  (a/>! ret-c (if r r :nil)))
                (recur (a/<! written-c))))
            (a/close! ret-c))
          (recur (a/<! cmd-ch))))
      (stream/close! connection))
    cmd-ch))

(defn make-pool
  "Make a connection pool to a specific redis thing"
  [redis]
  (pool/make-pool (reify pool/ConnectionFactory
                    (test-con [this con] true)
                    (new-con [this] (open-connection redis)))))

(defn get-connection
  "Returns a connection in the form of a command channel"
  [p]
  (pool/get-connection p))

(def ^:dynamic *pipe* nil)

(defn- coerce-to-string [param]
  (cond
   (string? param) param
   (keyword? param) (name param)
   :else (str param)))

(defn send-cmd [cmd-ch command & params]
  (let [full-cmd (concat command (map coerce-to-string params))]
    (if *pipe*
      (a/put! *pipe* full-cmd)
      (let [ret-c (a/chan)
            cmds  (a/chan)]
        (a/put! cmd-ch {:ret-c ret-c
                        :cmds  cmds})
        (a/put! cmds full-cmd)
        (a/close! cmds)
        ret-c))))

(defn flush-pipe [cmd-ch]
  (let [ch (a/chan)]
    (a/put! cmd-ch {:ret-c ch
                    :cmds  @*pipe*})
    ch))

(defmacro pipelined [cmd-ch & body]
  `(binding [*pipe* (a/chan pipelined-buffer-size)]
     (let [ch# (a/chan pipelined-buffer-size)]
       (a/put! ~cmd-ch {:ret-c ch#
                        :cmds  *pipe*})
       ~@body
       (a/close! *pipe*)
       ch#)))
