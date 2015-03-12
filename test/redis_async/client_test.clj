(ns redis-async.client-test
  (:require [redis-async.client :as client]
            [redis-async.core :as core]
            [redis-async.protocol :as protocol]
            [clojure.test :refer :all]
            [clojure.core.async :as a]))

;; Testing utilities

(defn- make-test-channel [& data]
  (let [c (a/chan)]
    (a/onto-chan c (map protocol/->resp data) true)
    c))

(defn- make-error [msg]
  (->> msg
       protocol/str->seq
       protocol/->Err))

(deftest <!-test
  (is (nil? (a/<!! (a/go (client/<! (make-test-channel nil))))))
  (is (= 1 (a/<!! (a/go (client/<! (make-test-channel 1))))))
  (testing "error handling"
    (is (= {:type :redis
            :msg  "TEST ERROR"}
           (a/<!! (a/go (try
                          (client/<! (make-test-channel (make-error "TEST ERROR")))
                          (catch clojure.lang.ExceptionInfo e
                            (select-keys (ex-data e)
                                         [:type :msg])))))))))

(deftest <!!-test
  (is (nil? (client/<!! (make-test-channel nil))))
  (is (= 1 (client/<!! (make-test-channel 1))))
  (testing "error handling"
    (is (= {:type :redis
            :msg  "TEST ERROR"}
           (try
             (client/<!! (make-test-channel (make-error "TEST ERROR")))
             (catch clojure.lang.ExceptionInfo e
               (select-keys (ex-data e)
                            [:type :msg])))))))

(deftest faf-test
  (is (nil? (a/<!! (client/faf (make-test-channel 1 2 3)))))
  (is (nil? (a/<!! (client/faf (make-test-channel 1 (make-error "TEST ERROR")))))))

(deftest wait!-test
  (is (nil? (a/<!! (a/go (client/wait! (make-test-channel 1))))))
  (testing "error handling"
    (is (= {:type :redis
            :msgs ["ERR A" "ERR B"]}
           (a/<!! (a/go (try
                          (client/wait! (make-test-channel (make-error "ERR A")
                                                           (make-error "ERR B")))
                          (catch clojure.lang.ExceptionInfo e
                            (ex-data e)))))))))

(deftest wait!!-test
  (is (nil? (client/wait!! (make-test-channel 1))))
  (testing "error handling"
    (is (= {:type :redis
            :msgs ["ERR A" "ERR B"]}
           (try
             (client/wait!! (make-test-channel (make-error "ERR A")
                                               (make-error "ERR B")))
             (catch clojure.lang.ExceptionInfo e
               (ex-data e)))))))

;; Testing commands

(def ^:dynamic *redis-pool* nil)

(defmacro is-ok [expr]
  `(is (= "OK" ~expr)))

(defn- load-seed-data
  "A bare-bones set of data for testing, most tests load their own data in
  addition to this set."
  []
  (client/wait!! (client/set *redis-pool* "TEST-STRING" "STRING-VALUE")))

(defn- redis-connect [f]
  (binding [*redis-pool* (core/make-pool {})]
    (is-ok (client/<!! (client/select *redis-pool* "1")))
    (is-ok (client/<!! (client/flushdb *redis-pool*)))
    (load-seed-data)
    (f)
    (core/close-pool *redis-pool*)))

(defn- with-redis [f & params]
  (apply f *redis-pool* params))

(defn- get-with-redis [f & params]
  (client/<!! (apply with-redis f params)))

(use-fixtures :once redis-connect)

(deftest keys-test
  ;; Test a sample of functions of Redis commands in the 'KEYS' category.
  (testing "DEL"
    (is-ok (get-with-redis client/set "TEST-KEY" "TEST-VALUE"))
    (is (= 1 (get-with-redis client/del "TEST-KEY")))
    (is (= 0 (get-with-redis client/del "TEST-KEY-DOESNT-EXIST"))))
  (testing "DUMP and RESTORE"
    (let [pc   (core/pipelined *redis-pool*
                               (client/set "DUMP-RESTORE" "DUMP-RESTORE-VALUE")
                               (client/dump "DUMP-RESTORE")
                               (client/del "DUMP-RESTORE"))
          _    (client/<!! pc)
          dump (a/<!! pc) ;; Dump returns a binary string, so we need the raw
                          ;; version
          _    (client/<!! pc)]
      (is (< 0 (count dump)))
      (client/wait!! (with-redis client/restore "DUMP-RESTORE" 0 dump))
      (is (= "DUMP-RESTORE-VALUE" (get-with-redis client/get "DUMP-RESTORE")))))
  (testing "EXISTS"
    (is (= 1 (get-with-redis client/exists "TEST-STRING"))))
  (testing "EXPIRE"
    (client/wait!! (with-redis client/set "EXPIRE-TEST" "EXPIRE-VALUE"))
    (client/wait!! (with-redis client/expire "EXPIRE-TEST" 1))
    (is (= "EXPIRE-VALUE" (get-with-redis client/get "EXPIRE-TEST")))
    (Thread/sleep 1000)
    (is (nil? (get-with-redis client/get "EXPIRE-TEST"))))
  (testing "EXPIREAT"
    (client/wait!! (with-redis client/set "EXPIREAT-TEST" "EXPIREAT-VALUE"))
    (client/wait!! (with-redis client/expireat "EXPIREAT-TEST"
                     (inc (long (/ (System/currentTimeMillis) 1000)))))
    (is (= "EXPIREAT-VALUE" (get-with-redis client/get "EXPIREAT-TEST")))
    (Thread/sleep 1000)
    (is (nil? (get-with-redis client/get "EXPIREAT-TEST"))))
  (testing "KEYS"
    (is (= ["TEST-STRING"] (get-with-redis client/keys "TEST-ST*"))))
  (testing "OBJECT"
    (is (< 0 (get-with-redis client/object :refcount "TEST-STRING")))
    (is (= "raw" (get-with-redis client/object :encoding "TEST-STRING")))
    (is (< 0 (get-with-redis client/object :idletime "TEST-STRING"))))
  (testing "SORT"
    (client/wait!! (core/pipelined *redis-pool*
                                   (client/sadd "SORT-TEST" "A")
                                   (client/sadd "SORT-TEST" "Z")
                                   (client/sadd "SORT-TEST" "B")
                                   (client/sadd "SORT-TEST" "W")))
    (is (= ["A" "B" "W" "Z"] (get-with-redis client/sort "SORT-TEST" :alpha)))))

(deftest strings-test
  (testing "GET, SET, INCR, INCRBY, DECR, DECRBY"
    (client/wait!! (with-redis client/set "STEST" 1))
    (client/wait!! (with-redis client/incr "STEST"))
    (client/wait!! (with-redis client/incrby "STEST" 100))
    (client/wait!! (with-redis client/decr "STEST"))
    (client/wait!! (with-redis client/decrby "STEST" 10))
    (is (= "91" (get-with-redis client/get "STEST")))))
