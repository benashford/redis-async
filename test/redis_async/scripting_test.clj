(ns redis-async.scripting-test
  (:require [redis-async.scripting :refer :all]
            [redis-async.test-helpers :refer :all]
            [clojure.test :refer :all]))

(defscript test-script
  "redis.call('incr', KEYS[1])
   return redis.call('get', KEYS[1])")

(use-fixtures :once redis-connect)

(deftest defscript-test
  (is (= "1" (get-with-redis test-script ["SCRIPT-TEST-1"] []))))
