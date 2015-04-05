(ns redis-async.scripting-test
  (:require [redis-async.scripting :refer :all]
            [redis-async.test-helpers :refer :all]
            [clojure.test :refer :all]))

(defscript test-script
  "redis.call('incr', KEYS[1])
   return redis.call('get', KEYS[1])")

(defscript test-script-2 (from "redis_async/scripting-test-ts2.lua"))

(use-fixtures :once redis-connect)

(deftest defscript-test
  (is (= "1" (get-with-redis test-script ["SCRIPT-TEST-1"] []))))

(deftest descript-from-test
  (is (= "1" (get-with-redis test-script-2 ["SCRIPT-TEST-2"]))))
