(ns redis-async.protocol-test
  (:require [redis-async.protocol :refer :all]
            [clojure.test :refer :all]
            [gloss.io :as io]))

(defn- decode [raw-string]
  (io/decode resp-frame (.getBytes raw-string)))

(deftest decoding-test
  (testing "simple strings"
    (is (= (->Str "TEST")
           (decode "+TEST\r\n"))))
  (testing "errors"
    (is (= (->Err (str->seq "I AM AN ERROR"))
           (decode "-I AM AN ERROR\r\n"))))
  (testing "integers"
    (is (= (->resp 1) (io/decode resp-frame (.getBytes ":1\r\n"))))
    (is (= (->resp 100) (io/decode resp-frame (.getBytes ":100\r\n"))))
    (is (= (->resp -10) (io/decode resp-frame (.getBytes ":-10\r\n")))))
  (testing "bulk string"
    (is (= (->resp "TEST") (io/decode resp-frame (.getBytes "$4\r\nTEST\r\n"))))
    (is (= (->resp "TEST\r\nTEST")
           (io/decode resp-frame (.getBytes "$10\r\nTEST\r\nTEST\r\n"))))
    (is (= (->resp "") (io/decode resp-frame (.getBytes "$0\r\n\r\n"))))
    (is (= (->resp nil) (io/decode resp-frame (.getBytes "$-1\r\n")))))
  (testing "arrays"
    (is (= (->resp []) (io/decode resp-frame (.getBytes "*0\r\n"))))
    (is (= (->resp [1]) (io/decode resp-frame (.getBytes "*1\r\n:1\r\n"))))
    (is (= (->resp [(->Str "TEST")])
           (io/decode resp-frame (.getBytes "*1\r\n+TEST\r\n"))))
    (is (= (->resp ["TEST"]) (io/decode resp-frame (.getBytes "*1\r\n$4\r\nTEST\r\n"))))
    (is (= (->resp [nil]) (io/decode resp-frame (.getBytes "*1\r\n$-1\r\n"))))
    (is (= (->resp [1 ["TEST"]])
           (io/decode resp-frame (.getBytes "*2\r\n:1\r\n*1\r\n$4\r\nTEST\r\n"))))
    (is (= (->resp [1 "TEST\r\nTEST" nil (->Str "TEST")])
           (io/decode resp-frame
                      (.getBytes "*4\r\n:1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n+TEST\r\n"))))))

(defn- enc [thing]
  (String. (.array (->> thing
                        ->resp
                        (io/encode resp-frame)
                        io/contiguous))))

(deftest encoding-test
  (testing "integers"
    (is (= ":1\r\n" (enc 1)))
    (is (= ":-1\r\n" (enc -1)))
    (is (= ":943\r\n" (enc 943))))
  (testing "strings"
    (is (= "$4\r\nTEST\r\n" (enc "TEST")))
    (is (= "$10\r\nTEST\r\nTEST\r\n" (enc "TEST\r\nTEST")))
    (is (= "$0\r\n\r\n" (enc "")))
    (is (= "$-1\r\n" (enc nil))))
  (testing "arrays"
    (is (= "*1\r\n:1\r\n" (enc [1])))
    (is (= "*1\r\n:1\r\n" (enc '(1))))
    (is (= "*0\r\n" (enc [])))
    (is (= "*4\r\n:1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n$4\r\nTEST\r\n"
           (enc [1 "TEST\r\nTEST" nil "TEST"])))))
