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

(defn- parse-response-lines [lines]
  (loop [responses      []
         [line & lines] lines]
    (if-not line
      responses
      (let [rest-of-line (subs line 1)]
        (case (first line)
          \+ (recur (conj responses rest-of-line) lines)
          \- (recur (conj responses {:error rest-of-line}) lines)
          \: (recur (conj responses (Long. rest-of-line)) lines)
          \$ (let [[next-line & lines] lines]
               (recur (if (empty? next-line)
                        nil
                        (conj responses next-line))
                      lines))
          \* (let [ary-size (Long. rest-of-line)
                   ary      (parse-response-lines (take ary-size lines))]
               (when (= (count ary) ary-size)
                 (recur (conj responses ary)
                        (drop ary-size lines)))))))))

(defn- parse-responses [^String body num-responses]
  (try
    (when (.endsWith body "\r\n")
      (let [responses (parse-response-lines (s/split body #"\r\n"))]
        (when (= (count responses) num-responses)
          responses)))
    (catch NumberFormatException _ nil)))

(defn- read-response
  "Read from a connection, then write to ret-c"
  [body connection ret-c num-responses]
  (if-let [response (parse-responses body num-responses)]
    (do
      (a/put! ret-c (if (= num-responses 1)
                      (first response)
                      response))
      (a/close! ret-c))
    (-> connection
        stream/take!
        (d/on-realized
         (fn [x]
           (read-response (str body (convert x String))
                          connection
                          ret-c
                          num-responses))
         (fn [x]
           (println "ERROR:" x)
           (a/close! ret-c))))))

(defn- do-cmd [connection {:keys [ret-c cmds]}]
  (doseq [cmd cmds]
    (write-cmd connection cmd))
  (read-response "" connection ret-c (count cmds)))

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

(defn send-cmd [redis command & params]
  (let [cmd-ch   (:command-channel redis)
        full-cmd (cons command params)]
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
