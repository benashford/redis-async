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
  (:refer-clojure :exclude [time sync keys sort type get set eval send])
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [redis-async.core :refer :all]
            [redis-async.protocol :as protocol])
  (:import [jresp Connection]
           [jresp.pool PubSubConnection]))

;; Internal utilities

(defn- is-str? [v]
  (or (= (class v) jresp.protocol.SimpleStr)
      (= (class v) jresp.protocol.BulkStr)))

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
  (let [^Connection con (get-connection pool :dedicated)
        close-c         (a/chan)
        r-r-c           (a/chan)
        ret-c           (a/chan)]
    (.start con (make-stream-response-handler ret-c))
    (.write con (protocol/->resp ["MONITOR"]))
    (a/go
      (let [ok (a/<! ret-c)]
        (if (= (protocol/->clj ok) "OK")
          (do
            (a/go-loop [[v _] (a/alts! [ret-c close-c])]
              (if-not v
                (do
                  (a/close! r-r-c)
                  (.write con (protocol/->resp ["QUIT"])))
                (do
                  (a/>! r-r-c v)
                  (recur (a/alts! [ret-c close-c])))))
            [r-r-c close-c])
          (do
            (a/close! r-r-c)
            (close-connection con)
            nil))))))

;; Blocking commands

(defn- blocking-command [cmd pool & params]
  (let [con   (get-connection pool :borrowed)
        ret-c (->> params
                   (command->resp cmd)
                   (send con))]
    (a/go
      (let [res (a/<! ret-c)]
        (finish-connection pool con)
        res))))

(def blpop (partial blocking-command "BLPOP"))
(def brpop (partial blocking-command "BRPOP"))
(def brpoplpush (partial blocking-command "BRPOPLPUSH"))

;; Pub-sub

(def ^:private pub-sub-channel-size 16)

(defn subscribe [pool channel]
  (let [^PubSubConnection con (get-connection pool :pub-sub)
        ch                    (a/chan pub-sub-channel-size (drop 1))]
    (.subscribe con channel (make-stream-response-handler ch))
    ch))

(defn unsubscribe [pool channel]
  (let [^PubSubConnection con (get-connection pool :pub-sub)]
    (.unsubscribe con channel)))

(defn psubscribe [pool pattern]
  (let [^PubSubConnection con (get-connection pool :pub-sub)
        ch                    (a/chan pub-sub-channel-size (drop 1))]
    (.psubscribe con pattern (make-stream-response-handler ch))
    ch))

(defn punsubscribe [pool pattern]
  (let [^PubSubConnection con (get-connection pool :pub-sub)]
    (.punsubscribe con pattern)))

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
  (let [last-c (last (map #(echo p (str %)) (range 1000)))]
    (a/<!! last-c)))

(defn ping-1000 [p]
  (let [last-c (last (map (fn [_] (ping p)) (range 1000)))]
    (a/<!! last-c)))

(defn count-and-ping [p]
  (let [cc (a/thread (count-1000 p))
        pc (a/thread (ping-1000 p))]
    (<!! cc)
    (<!! pc)))
