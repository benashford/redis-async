(ns redis-async.client
  (:require [clojure.string :as s]
            [redis-async.core :refer :all]))

(defmacro defredis [fn-n]
  (let [cmd (as-> fn-n x
                  (name x)
                  (s/split x #"-")
                  (mapv s/upper-case x))]
    `(defn ~fn-n [~'redis & ~'params]
       (apply send-cmd ~'redis ~cmd ~'params))))

;; Commands

(def connection ['auth 'echo 'ping 'quit 'select])
(def server ['bgrewriteaof
             'bgsave
             'client-kill
             'client-list
             'client-getname
             'client-pause
             'client-setname
             'cluster-slots
             'command
             'command-count
             'command-getkeys
             'command-info
             'config-get
             'config-rewrite
             'config-set
             'config-resetstat
             'dbsize
             'debug-object])

(doseq [range [connection server]
        cmd   range]
  (eval `(defredis ~cmd)))
