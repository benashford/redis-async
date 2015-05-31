(ns redis-async.client-test
  (:require [redis-async.client :as client]
            [redis-async.core :as core]
            [redis-async.protocol :as protocol]
            [redis-async.test-helpers :refer :all]
            [clojure.test :refer :all]
            [clojure.core.async :as a]))

;; Testing utilities

(defn- to-resp [data]
  (if (= (class data) jresp.protocol.Err)
    data
    (protocol/->resp data)))

(defn- make-test-channel [& data]
  (let [c (a/chan)]
    (a/onto-chan c (map to-resp data) true)
    c))

(defn- make-error [msg]
  (jresp.protocol.Err. msg))

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
    (let [result (a/<!! (a/go
                          (try
                            (client/wait! (make-test-channel (make-error "ERR A")
                                                             (make-error "ERR B")))
                            (catch clojure.lang.ExceptionInfo e
                              (ex-data e)))))]
      (is (= :redis (:type result)))
      (is (= ["ERR A" "ERR B"]
             (map #(:msg (ex-data %)) (:msgs result)))))))

(deftest wait!!-test
  (is (nil? (client/wait!! (make-test-channel 1))))
  (testing "error handling"
    (let [result (try
                   (client/wait!! (make-test-channel (make-error "ERR A")
                                                     (make-error "ERR B")))
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
      (is (= :redis (:type result)))
      (is (= ["ERR A" "ERR B"]
             (map #(:msg (ex-data %)) (:msgs result)))))))

;; Testing commands

(use-fixtures :once redis-connect)

(deftest monitor-test
  (client/wait!! (with-redis client/set "T1" "D1"))
  (client/wait!! (with-redis client/set "T2" "D2"))
  (let [[m-ch close-ch] (a/<!! (with-redis client/monitor))]
    (is (= "D1" (get-with-redis client/get "T1")))
    (is (= "D2" (get-with-redis client/get "T2")))
    (is (.contains (client/<!! m-ch) "GET"))
    (is (.contains (client/<!! m-ch) "T2"))
    (a/close! close-ch))
  (is (= "D1" (get-with-redis client/get "T1")))
  (is (= "D2" (get-with-redis client/get "T2"))))

(deftest keys-test
  ;; Test a sample of functions of Redis commands in the 'KEYS' category.
  (testing "DEL"
    (is-ok (get-with-redis client/set "TEST-KEY" "TEST-VALUE"))
    (is (= 1 (get-with-redis client/del "TEST-KEY")))
    (is (= 0 (get-with-redis client/del "TEST-KEY-DOESNT-EXIST"))))
  (testing "DUMP and RESTORE"
    (let [_    (client/faf (with-redis client/set "DUMP-RESTORE" "DUMP-RESTORE-VALUE"))
          dump (a/<!! (with-redis client/dump "DUMP-RESTORE"))
          ;; Dump returns a binary string, so we need the raw
          ;; version
          _    (client/faf (with-redis client/del "DUMP-RESTORE"))]
      (is (< 0 (count (.raw dump))))
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
    (doseq [d ["A" "Z" "B" "W"]]
      (client/faf (with-redis client/sadd "SORT-TEST" d)))
    (is (= ["A" "B" "W" "Z"] (get-with-redis client/sort "SORT-TEST" :alpha)))))

(deftest strings-test
  (testing "GET, SET, INCR, INCRBY, DECR, DECRBY"
    (client/wait!! (with-redis client/set "STEST" 1))
    (client/wait!! (with-redis client/incr "STEST"))
    (client/wait!! (with-redis client/incrby "STEST" 100))
    (client/wait!! (with-redis client/decr "STEST"))
    (client/wait!! (with-redis client/decrby "STEST" 10))
    (is (= "91" (get-with-redis client/get "STEST")))))
