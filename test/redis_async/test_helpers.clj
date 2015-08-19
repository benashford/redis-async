(ns redis-async.test-helpers
  (:require [clojure.test :refer :all]
            [redis-async.core :as core]
            [redis-async.client :as client]))

(def ^:dynamic *redis-pool* nil)

(defmacro is-ok [expr]
  `(is (= "OK" ~expr)))

(defn- load-seed-data
  "A bare-bones set of data for testing, most tests load their own data in
  addition to this set."
  []
  (client/wait!! (client/set *redis-pool* "TEST-STRING" "STRING-VALUE")))

(defn redis-connect [f]
  (binding [*redis-pool* (core/make-pool {:db 1})]
    (is-ok (client/<!! (client/flushdb *redis-pool*)))
    (load-seed-data)
    (f)
    (core/close-pool *redis-pool*)))

(defn with-redis [f & params]
  (apply f *redis-pool* params))

(defn get-with-redis [f & params]
  (client/<!! (apply with-redis f params)))
