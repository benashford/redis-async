(ns redis-async.protocol-test
  (:require [redis-async.protocol :refer :all]
            [clojure.test :refer :all]
            [gloss.io :as io]))

(deftest decoding-test
  (testing "simple strings"
    (is (= "TEST"
           (io/decode resp-frame (.getBytes "+TEST\r\n")))))
  (testing "errors"
    (is (= {:error "I AM AN ERROR"}
           (io/decode resp-frame (.getBytes "-I AM AN ERROR\r\n")))))
  (testing "integers"
    (is (= 1 (io/decode resp-frame (.getBytes ":1\r\n"))))
    (is (= 100 (io/decode resp-frame (.getBytes ":100\r\n"))))
    (is (= -10 (io/decode resp-frame (.getBytes ":-10\r\n")))))
  (testing "bulk string"
    (is (= "TEST" (io/decode resp-frame (.getBytes "$4\r\nTEST\r\n"))))
    (is (= "TEST\r\nTEST" (io/decode resp-frame (.getBytes "$10\r\nTEST\r\nTEST\r\n"))))
    (is (= "" (io/decode resp-frame (.getBytes "$0\r\n\r\n"))))
    (is (= :nil (io/decode resp-frame (.getBytes "$-1\r\n")))))
  (testing "arrays"
    (is (= [] (io/decode resp-frame (.getBytes "*0\r\n"))))
    (is (= [1] (io/decode resp-frame (.getBytes "*1\r\n:1\r\n"))))
    (is (= ["TEST"] (io/decode resp-frame (.getBytes "*1\r\n+TEST\r\n"))))
    (is (= ["TEST"] (io/decode resp-frame (.getBytes "*1\r\n$4\r\nTEST\r\n"))))
    (is (= [nil] (io/decode resp-frame (.getBytes "*1\r\n$-1\r\n"))))
    (is (= [1 ["TEST"]]
           (io/decode resp-frame (.getBytes "*2\r\n:1\r\n*1\r\n$4\r\nTEST\r\n"))))
    (is (= [1 "TEST\r\nTEST" nil "TEST"]
           (io/decode resp-frame
                      (.getBytes "*4\r\n:1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n+TEST\r\n"))))))

(defn- enc [thing]
  (String. (.array (io/contiguous (io/encode resp-frame thing)))))

(deftest encoding-test
  (testing "integers"
    (is (= ":1\r\n" (enc 1)))
    (is (= ":-1\r\n" (enc -1)))
    (is (= ":943\r\n" (enc 943))))
  (testing "strings"
    (is (= "$4\r\nTEST\r\n" (enc "TEST")))
    (is (= "$10\r\nTEST\r\nTEST\r\n" (enc "TEST\r\nTEST")))
    (is (= "$0\r\n\r\n" (enc "")))
    (is (= "$-1\r\n" (enc :nil))))
  (testing "arrays"
    (is (= "*1\r\n:1\r\n" (enc [1])))
    (is (= "*1\r\n:1\r\n" (enc '(1))))
    (is (= "*0\r\n" (enc [])))
    (is (= "*4\r\n:1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n$4\r\nTEST\r\n"
           (enc [1 "TEST\r\nTEST" nil "TEST"])))))
