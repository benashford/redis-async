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
  (:require [clojure.string :as s]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [redis-async.core :refer :all]
            [redis-async.pool :as pool]
            [redis-async.protocol :as protocol]))

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

(defn- is-error? [v]
  (let [klass (class v)]
    (or (= klass redis_async.protocol.Err)
        (= klass redis_async.core.ClientErr))))

(defn- is-str? [v]
  (or (= (class v) redis_async.protocol.Str)
      (= (class v) redis_async.protocol.BulkStr)))

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
                  (map #(protocol/seq->str (:bytes %))))]
    (when-not (empty? errs)
      (throw (ex-info (str "Error(s) from Redis:"
                           (pr-str errs))
                      {:type :redis
                       :msgs errs})))))

(defmacro wait! [expr]
  `(check-wait-for-errors (a/<! (a/into [] ~expr))))

(defmacro wait!! [expr]
  `(check-wait-for-errors (a/<!! (a/into [] ~expr))))

;; Specific commands, the others are auto-generated later

(defn monitor [pool]
  (let [con     (pool/borrow-connection pool)
        cmds    (a/chan)
        close-c (a/chan)
        cmd-ch  (:cmd-ch con)
        in-c    (:in-c con)
        ret-c   (a/chan)
        tmp-c   (a/chan)]
    (a/go
      (a/>! cmd-ch {:ret-c tmp-c
                    :cmds  cmds})
      (a/>! cmds (protocol/->resp ["MONITOR"]))
      (let [ok (a/<! tmp-c)]
        (if (= (protocol/->clj ok) "OK")
          (loop [[v _] (a/alts! [in-c close-c])]
            (if-not v
              (do
                (a/close! ret-c)
                (pool/return-connection pool con))
              (do
                (a/>! ret-c v)
                (recur (a/alts! [in-c close-c])))))
          (do
            (a/close! ret-c)
            (pool/return-connection pool con)))))
    [ret-c close-c]))

;; Commands

(def ^:private overriden-clients
  #{"monitor"})

(defn- load-commands-meta []
  (->> "https://raw.githubusercontent.com/antirez/redis-doc/master/commands.json"
       slurp
       json/decode))

(defn- coerce-to-string [val]
  (cond
   (or (string? val)
       (is-str? val))
   val

   (keyword? val)
   (-> val name s/upper-case)

   :else
   (str val)))

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
      (println "Function:" fn-name)
      (emit-client-fn fn-name summary))))

(let [cmd-meta (load-commands-meta)
      fn-defs  (generate-commands cmd-meta)]
  (doseq [fn-def fn-defs]
    (clojure.core/eval fn-def)))

;; DELETE ME - temporary functions for ad-hoc benchmarking

(defn ping-1000 [p]
  (let [cs (doall (repeatedly 1000 #(ping p)))]
    (mapv #(<!! %) cs)))
