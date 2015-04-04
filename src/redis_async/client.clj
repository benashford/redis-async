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

(ns redis-async.client
  (:refer-clojure :exclude [time sync keys sort type get set eval])
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [redis-async.core :refer :all]
            [redis-async.pool :as pool]
            [redis-async.protocol :as protocol]))

;; Internal utilities

(defn- is-str? [v]
  (or (= (class v) redis_async.protocol.Str)
      (= (class v) redis_async.protocol.BulkStr)))

(defn- coerce-to-string [val]
  (cond
   (or (string? val)
       (is-str? val))
   val

   (keyword? val)
   (-> val name s/upper-case)

   :else
   (str val)))

;; Useful to enforce conventions

(defn read-value [msg]
  (if-not (nil? msg)
    (let [value (protocol/->clj msg)]
      (if (isa? (class value) clojure.lang.ExceptionInfo)
        (throw value)
        value))
    (throw (ex-info "Expected message, actually nil" {}))))

(defmacro <! [expr]
  `(read-value (a/<! ~expr)))

(defmacro <!! [expr]
  `(read-value (a/<!! ~expr)))

(defn faf
  "Fire-and-forget.  Warning: if no error-callback is defined, all errors are
  ignored."
  ([ch]
   (faf ch (fn [_] nil)))
  ([ch error-callback]
   (a/go-loop [v (a/<! ch)]
     (when v
       (if (is-error? v)
         (error-callback v))
       (recur (a/<! ch))))))

(defn check-wait-for-errors [results]
  (let [errs (->> results
                  (filter #(is-error? %))
                  (map protocol/->clj))]
    (when-not (empty? errs)
      (throw (ex-info (str "Error(s) from Redis:"
                           (pr-str errs))
                      {:type :redis
                       :msgs errs})))))

(defmacro wait! [expr]
  `(check-wait-for-errors (a/<! (a/into [] ~expr))))

(defmacro wait!! [expr]
  `(check-wait-for-errors (a/<!! (a/into [] ~expr))))

;; Prepare custom commands

(defn- command->resp [command args]
  (->> args
       (map coerce-to-string)
       (cons command)
       protocol/->resp))

;; Specific commands, the others are auto-generated later

(defn monitor [pool]
  (let [con     (get-connection pool :dedicated)
        close-c (a/chan)
        ret-c   (a/chan)
        cmd-ch  (:cmd-ch con)
        in-c    (:in-c con)
        quit    (fn []
                  (a/close! ret-c)
                  (a/put! cmd-ch [(protocol/->resp ["QUIT"]) (a/chan 1)])
                  (close-connection pool :dedicated con))]
    (a/go
      (let [ok (a/<! (send! cmd-ch (protocol/->resp ["MONITOR"])))]
        (if (= (protocol/->clj ok) "OK")
          (do
            (a/go-loop [[v _] (a/alts! [in-c close-c])]
              (if-not v
                (do
                  (a/close! ret-c)
                  (finish-connection pool :dedicated con))
                (do
                  (a/>! ret-c v)
                  (recur (a/alts! [in-c close-c])))))
            [ret-c close-c])
          (do
            (a/close! ret-c)
            (finish-connection pool :dedicated con)
            nil))))))

;; Blocking commands

(defn- blocking-command [cmd pool & params]
  (let [con   (get-connection pool :borrowed)
        ret-c (->> params
                   (command->resp cmd)
                   (send! (:cmd-ch con)))]
    (a/go
      (let [res (a/<! ret-c)]
        (finish-connection pool :borrowed con)
        res))))

(def blpop (partial blocking-command "BLPOP"))
(def brpop (partial blocking-command "BRPOP"))
(def brpoplpush (partial blocking-command "BRPOPLPUSH"))

;; Pub-sub

(defn- unsubscribe-from [subs channel]
  (if-let [out-c (subs channel)]
    (do
      (a/close! out-c)
      (dissoc subs channel))
    subs))

(defn- make-pub-sub [pool]
  (let [p       (pool :pub-sub-c)
        [con p] (pool/get-connection p)
        in-c    (:in-c con)
        subs    (atom {})
        psubs   (atom {})]
    (a/go
      (loop [msg (a/<! in-c)]
        (when msg
          (let [[msg-type channel data] (:values msg)
                msg-type                (protocol/->clj msg-type)
                channel                 (protocol/->clj channel)]
            (case msg-type
              "subscribe" nil
              "psubscribe" nil
              "unsubscribe"
              (swap! subs unsubscribe-from channel)
              "punsubscribe"
              (swap! psubs unsubscribe-from channel)
              "message"
              (when-let [out-ch (@subs channel)]
                (a/>! out-ch data))
              "pmessage"
              (when-let [out-ch (@psubs channel)]
                (a/>! out-ch data)))
            (recur (a/<! in-c))))))
    [p
     {:cmd-ch (:cmd-ch con)
      :subs   subs
      :psubs  psubs}]))

(defn- get-pub-sub [pool]
  (let [pub-sub (promise)]
    (swap! pool (fn [pool]
                  (if-let [ps (:pub-sub pool)]
                    (do
                      (deliver pub-sub ps)
                      pool)
                    (let [[p ps] (make-pub-sub pool)]
                      (deliver pub-sub ps)
                      (assoc pool
                             :pub-sub ps
                             :pub-sub-c p)))))
    @pub-sub))

(def ^:private pub-sub-buffer-default 16)

(defn- subscribe-to [subs channels c]
  (reduce (fn [subs channel]
            (assoc subs channel c))
          subs
          channels))

(defn- subscribe-cmd [cmd ch-pool pool & channels]
  (let [pub-sub (get-pub-sub pool)
        ret-c   (a/chan pub-sub-buffer-default)
        subs    (pub-sub ch-pool)]
    (assert (not (nil? subs)))
    (swap! subs subscribe-to channels ret-c)
    (a/put! (:cmd-ch pub-sub) (command->resp cmd channels))
    ret-c))

(def subscribe (partial subscribe-cmd "SUBSCRIBE" :subs))
(def psubscribe (partial subscribe-cmd "PSUBSCRIBE" :psubs))

(defn unsubscribe-cmd [cmd pool & channels]
  (let [pub-sub (get-pub-sub pool)]
    (a/put! (:cmd-ch pub-sub) (command->resp cmd channels))))

(def unsubscribe (partial unsubscribe-cmd "UNSUBSCRIBE"))
(def punsubscribe (partial unsubscribe-cmd "PUNSUBSCRIBE"))

;; All other commands

(def ^:private overriden-clients
  #{"monitor" ;; needs a dedicated connection listing all traffic
    "blpop" "brpop" "brpoplpush" ;; blocking commands
    "subscribe" "unsubscribe" "psubscribe" "punsubscribe" ;; pub-sub
    "multi" "exec" "discard" ;; transactions
    })

(defn- load-commands-meta []
  (->> "commands.json"
       io/resource
       slurp
       json/decode))

(defn- emit-client-fn [fn-n summary]
  (let [cmd  (as-> fn-n x
               (s/split x #"-")
               (mapv s/upper-case x))
        fn-s (symbol fn-n)]
    `(defn ~fn-s
       ~summary
       [& ~'params]
       (let [redis#  (first ~'params)
             params# (->> (drop 1 ~'params)
                          (map coerce-to-string))]
         (send-cmd redis# ~cmd params#)))))

(defn- generate-commands [commands-meta]
  (for [[command-name command-data] commands-meta
        :let [fn-name (-> command-name s/lower-case (s/replace " " "-"))]
        :when (not (overriden-clients fn-name))]
    (let [command-data (clojure.walk/keywordize-keys command-data)
          summary      (command-data :summary)
          args         (command-data :arguments)]
      (emit-client-fn fn-name summary))))

(let [cmd-meta (load-commands-meta)
      fn-defs  (generate-commands cmd-meta)]
  (doseq [fn-def fn-defs]
    (clojure.core/eval fn-def)))

;; DELETE ME - temporary functions for ad-hoc benchmarking

(defn count-1000 [p]
  (let [cs (mapv #(echo p (str %)) (range 1000))]
    (mapv (fn [c i]
            (let [v (<!! c)]
              v))
          cs
          (range))))

(defn ping-1000 [p]
  (let [cs (mapv (fn [_] (ping p)) (range 1000))]
    (mapv (fn [c i]
            (let [v (<!! c)]
              v))
          cs
          (range))))
