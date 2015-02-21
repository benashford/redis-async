(ns redis-async.client
  (:require [redis-async.core :refer :all]))

(defn ping [redis]
  (send-cmd redis "PING"))

