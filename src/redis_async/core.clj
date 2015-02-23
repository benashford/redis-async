(ns redis-async.core
  (:require [aleph.tcp :as tcp]
            [byte-streams :refer [convert]]
            [clojure.core.async :as a]
            [clojure.string :as s]
            [manifold.deferred :as d]
            [manifold.stream :as stream]
            [gloss.core :as gloss]
            [gloss.io :as io]))

(def ^:private default-redis
  {:host "localhost"
   :port 6379})

;; unify with real-frame
(def ^:private resp-frame-out (gloss/string :utf-8 :delimiters ["\r\n"]))

(defn- write-cmd [connection cmd]
  (let [cmd-lines (cons (str "*" (count cmd))
                        (mapcat (fn [p]
                                  [(str "$" (count p)) p])
                                cmd))
        bytes (io/encode-all resp-frame-out cmd-lines)]
    (stream/put! connection (io/contiguous bytes))))

(defn- parse-responses [lines num-responses]
  (take num-responses lines))

(defn- read-response
  "Read from a connection, then write to ret-c"
  [in-stream ret-c num-responses]
  (let [stream-seq (stream/stream->seq in-stream)
        responses  (parse-responses stream-seq num-responses)]
    (a/put! ret-c (if (> (count responses) 1)
                    responses
                    (if-let [r (first responses)]
                      r
                      [nil]))))
  (a/close! ret-c))

(defn- do-cmd [connection in-stream {:keys [ret-c cmds]}]
  (doseq [cmd cmds]
    (write-cmd connection cmd))
  (read-response in-stream ret-c (count cmds)))

(gloss/defcodec resp-type
  (gloss/enum :byte {:str \+ :err \- :int \: :bulk-str \$ :ary \*}))

(gloss/defcodec resp-str
  (gloss/string :utf-8 :delimiters ["\r\n"]))

(gloss/defcodec resp-err
  (gloss/string :utf-8 :delimiters ["\r\n"]))

(gloss/defcodec resp-int
  (gloss/string-integer :utf-8 :delimiters ["\r\n"]))

(gloss/defcodec resp-bulk-str
  (gloss/finite-frame
   (gloss/prefix (gloss/string :utf-8 :delimiters ["\r\n"])
                 (fn [len-str]
                   (let [length (Long/parseLong len-str)]
                     (if (< length 0)
                       0
                       (+ length 2))))
                 str)
   (gloss/string :utf-8 :suffix "\r\n")))

(declare resp-frame)

(gloss/defcodec resp-ary
  (gloss/header
   (gloss/string-integer :utf-8 :delimiters ["\r\n"])
   (fn [ary-size]
     (gloss/compile-frame
      (if (= ary-size 0)
        []
        (repeat ary-size resp-frame))))
   (fn [ary]
     (count ary))))

(def ^:private resp-frames
  {:str      resp-str
   :err      resp-err
   :int      resp-int
   :bulk-str resp-bulk-str
   :ary      resp-ary})

(gloss/defcodec resp-frame
  (gloss/header
   resp-type
   resp-frames
   nil))

(defn open-connection [redis]
  (let [redis      (merge default-redis redis)
        connection @(tcp/client redis)
        in-stream  (io/decode-stream resp-frame)
        cmd-ch     (a/chan)]
    (a/thread
      (loop [cmd (a/<!! cmd-ch)]
        (if-not cmd
          nil
          (do
            (do-cmd connection in-stream cmd)
            (recur (a/<!! cmd-ch))))))
    (assoc redis
      :command-channel cmd-ch
      ::connection connection)))

(def ^:dynamic *pipe* nil)
(def ^:dynamic *redis* nil)

(defn- coerce-to-string [param]
  (cond
   (string? param) param
   (keyword? param) (name param)
   :else (str param)))

(defn send-cmd [redis command & params]
  (let [cmd-ch   (:command-channel redis)
        full-cmd (concat command (map coerce-to-string params))]
    (if *pipe*
      (swap! *pipe* conj full-cmd)
      (let [ch (a/chan)]
        (a/put! cmd-ch {:ret-c ch
                        :cmds  [full-cmd]})
        ch))))

(defn flush-pipe [redis]
  (let [cmd-ch (:command-channel redis)
        ch     (a/chan)]
    (a/put! cmd-ch {:ret-c ch
                    :cmds  @*pipe*})
    ch))

(defmacro pipelined [redis & body]
  `(binding [*pipe*  (atom [])
             *redis* redis]
     ~@body
     (flush-pipe ~redis)))
