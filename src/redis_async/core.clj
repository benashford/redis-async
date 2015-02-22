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

(def ^:private resp-frame (gloss/string :utf-8 :delimiters ["\r\n"]))

(defn- write-cmd [connection cmd]
  (let [cmd-lines (cons (str "*" (count cmd))
                        (mapcat (fn [p]
                                  [(str "$" (count p)) p])
                                cmd))
        bytes (io/encode-all resp-frame cmd-lines)]
    (stream/put! connection (io/contiguous bytes))))

(defn- parse-response-lines [lines num-responses]
  (loop [responses []
         lines     lines]
    (let [c-resp (count responses)
          more-remaining? (> (- num-responses c-resp) 1)]
      (if (= c-resp num-responses)
        [responses lines]
        (let [line         (first lines)
              rest-of-line (subs line 1)
              lines        (drop 1 lines)]
          (case (first line)
            \+ (recur (conj responses rest-of-line) lines)
            \- (recur (conj responses {:error rest-of-line}) lines)
            \: (recur (conj responses (Long. rest-of-line)) lines)
            \$ (let [next-line       (first lines)
                     remaining-lines (drop 1 lines)]
                 (recur (conj responses next-line)
                        remaining-lines))
            \* (let [ary-size    (Long. rest-of-line)
                     [ary lines] (parse-response-lines lines ary-size)]
                 (recur (conj responses ary)
                        lines))))))))

(defn- parse-responses [lines num-responses]
  (try
    (first (parse-response-lines lines num-responses))
    (catch NumberFormatException _ nil)))

(defn- read-response
  "Read from a connection, then write to ret-c"
  [in-stream ret-c num-responses]
  (let [stream-seq (stream/stream->seq in-stream)]
    (a/put! ret-c (parse-responses stream-seq num-responses)))
  (a/close! ret-c))

(defn- do-cmd [connection in-stream {:keys [ret-c cmds]}]
  (doseq [cmd cmds]
    (write-cmd connection cmd))
  (read-response in-stream ret-c (count cmds)))

(defn open-connection [redis]
  (let [redis      (merge default-redis redis)
        connection @(tcp/client redis)
        in-stream  (io/decode-stream connection resp-frame)
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
        full-cmd (cons command (map coerce-to-string params))]
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
