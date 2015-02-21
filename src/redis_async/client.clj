(ns redis-async.client
  (:require [clojure.string :as s]
            [redis-async.core :refer :all]))

(defmacro defredis [fn-n]
  (let [cmd (as-> fn-n x
                  (name x)
                  (s/split x #"-")
                  (s/join " " x)
                  (s/upper-case x))]
    `(defn ~fn-n [~'redis & ~'params]
       (apply send-cmd ~'redis ~cmd ~'params))))

;; Connection

(defredis auth)
(defredis echo)
(defredis ping)
(defredis quit)
(defredis select)

;; Server

(doseq [cmd ['bgrewriteaof
             'bgsave
             'command]]
  (eval `(defredis ~cmd)))
