(ns redis-async.core
  (:require [clojure.string :as s]
            [clojure.core.async :as a]
            [aleph.tcp :as tcp]
            [byte-streams :refer [convert]]
            [manifold.deferred :as d]
            [manifold.stream :as stream]))

(def ^:private default-redis
  {:host "localhost"
   :port 6379})

(defn- write-ln [connection line]
  (let [full-line (str line "\r\n")]
    (stream/put! connection full-line)))

(defn- write-cmd [connection cmd]
  (write-ln connection (str "*" (count cmd)))
  (doseq [p cmd]
    (write-ln connection (str "$" (count p)))
    (write-ln connection p)))

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

(def ^:private max-response-size 10000)

(defn- stream-seq->line-seq [stream-seq & [remains]]
  (let [first-chunk    (first stream-seq)
        ^String string (convert first-chunk String)
        [line & lines] (s/split string #"\r\n")
        lines          (cons (str remains line) lines)]
    (if (.endsWith string "\r\n")
      (concat lines (lazy-seq (stream-seq->line-seq (next stream-seq))))
      (concat (butlast lines)
              (lazy-seq (stream-seq->line-seq (next stream-seq) (last lines)))))))

(defn- read-response
  "Read from a connection, then write to ret-c"
  [connection ret-c num-responses]
  (let [stream-seq (stream/stream->seq connection)
        line-seq   (stream-seq->line-seq stream-seq)]
    (a/put! ret-c (parse-responses line-seq num-responses)))
  (a/close! ret-c))

(defn- do-cmd [connection {:keys [ret-c cmds]}]
  (doseq [cmd cmds]
    (write-cmd connection cmd))
  (read-response connection ret-c (count cmds)))

(defn open-connection [redis]
  (let [redis      (merge default-redis redis)
        connection @(tcp/client redis)
        cmd-ch     (a/chan)]
    (a/go
      (loop [cmd (a/<! cmd-ch)]
        (if-not cmd
          nil
          (do
            (do-cmd connection cmd)
            (recur (a/<! cmd-ch))))))
    (assoc redis
      :command-channel cmd-ch)))

(def ^:dynamic pipe nil)

(defn- coerce-to-string [param]
  (cond
   (string? param) param
   (keyword? param) (name param)
   :else (str param)))

(defn send-cmd [redis command & params]
  (let [cmd-ch   (:command-channel redis)
        full-cmd (cons (s/upper-case command) (map coerce-to-string params))]
    (if pipe
      (swap! pipe conj full-cmd)
      (let [ch (a/chan)]
        (a/put! cmd-ch {:ret-c ch
                        :cmds  [full-cmd]})
        ch))))

(defn flush-pipe [redis]
  (let [cmd-ch (:command-channel redis)
        ch     (a/chan)]
    (a/put! cmd-ch {:ret-c ch
                    :cmds  @pipe})
    ch))

(defmacro pipelined [redis & body]
  `(binding [pipe (atom [])]
     ~@body
     (flush-pipe ~redis)))
