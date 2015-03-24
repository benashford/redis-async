(ns redis-async.protocol-test
  (:refer-clojure :exclude [dec])
  (:require [clojure.core.async :as a]
            [redis-async.protocol :refer :all]
            [clojure.test :refer :all]))

;; utilities

(defn- string->byte-buffer [string]
  (when string
    (byte-streams/convert string java.nio.ByteBuffer)))

(defn- byte-buffer->string [byte-buffer]
  (when byte-buffer
    (byte-streams/convert byte-buffer String)))

(defn- dec
  "Prepare a test string into a raw channel"
  [string]
  (let [raw-c (a/chan)
        bytes (string->byte-buffer string)]
    (a/put! raw-c bytes)
    (a/close! raw-c)
    raw-c))

(defn- decode-one [raw-c]
  (let [in-c (a/chan)]
    (decode raw-c in-c)
    (let [[val ch] (a/alts!! [in-c (a/timeout 1000)])]
      (if (= ch in-c)
        val
        :timeout))))

(defn- str= [a b]
  (byte-streams/bytes= (:bytes a) (:bytes b)))

;; tests

(defn- scan-for-wrapper [input byte-count]
  (let [result (scan-for (string->byte-buffer input) byte-count)]
    (-> result
        (update-in [:scanned] byte-buffer->string)
        (update-in [:input] byte-buffer->string))))

(deftest scan-for-test
  (is (= {:scanned ""
          :input   "ABCDEF"
          :end     true}
         (scan-for-wrapper "ABCDEF" 0)))
  (is (= {:scanned "ABC"
          :input   "DEF"
          :end     true}
         (scan-for-wrapper "ABCDEF" 3)))
  (is (= {:scanned "ABCDEF"
          :input   nil
          :end     true}
         (scan-for-wrapper "ABCDEF" 6)))
  (is (= {:scanned "ABCDEF"
          :input   nil
          :end     false}
         (scan-for-wrapper "ABCDEF" 12))))

(defn- scan-until-delimiter-wrapper [string]
  (let [result (scan-until-delimiter (string->byte-buffer string))]
    (-> result
        (update-in [:scanned] byte-buffer->string)
        (update-in [:input] byte-buffer->string))))

(deftest scan-until-delimiter-test
  (is (= {:scanned ""
          :input   ""
          :end     true}
         (scan-until-delimiter-wrapper "\r\n")))
  (is (= {:scanned ""
          :input   nil
          :end     false}
         (scan-until-delimiter-wrapper "")))
  (is (= {:scanned "ABC"
          :input   nil
          :end     false}
         (scan-until-delimiter-wrapper "ABC")))
  (is (= {:scanned "ABC"
          :input   "\r"
          :end     false}
         (scan-until-delimiter-wrapper "ABC\r")))
  (is (= {:scanned "ABC"
          :input   ""
          :end     true}
         (scan-until-delimiter-wrapper "ABC\r\n")))
  (is (= {:scanned "ABC"
          :input   "DEF"
          :end     true}
         (scan-until-delimiter-wrapper "ABC\r\nDEF")))
  (is (= {:scanned "ABC\rDEF"
          :input   ""
          :end     true}
         (scan-until-delimiter-wrapper "ABC\rDEF\r\n")))
  (is (= {:scanned "ABC\nDEF"
          :input   nil
          :end     false}
         (scan-until-delimiter-wrapper "ABC\nDEF"))))

(defn- process-simple-string-wrapper [current-state input]
  (let [current-state (-> current-state
                          (update-in [:scanned] #(map string->byte-buffer %)))
        input         (string->byte-buffer input)
        [s i]         (process-simple-string current-state input)]
    [(-> s
         (update-in [:scanned] #(map byte-buffer->string %)))
     (byte-buffer->string i)]))

(deftest process-simple-string-test
  (is (= [{:end true :scanned ["ABC"]} ""]
         (process-simple-string-wrapper {} "ABC\r\n")))
  (is (= [{:end false :scanned ["ABC"]} nil]
         (process-simple-string-wrapper {} "ABC")))
  (is (= [{:end true :scanned ["ABC" "DEF"]} ""]
         (process-simple-string-wrapper {:end false :scanned ["ABC"]} "DEF\r\n")))
  (is (= [{:end true :scanned ["ABC"]} "DEF"]
         (process-simple-string-wrapper {} "ABC\r\nDEF")))
  (is (= [{:end false :scanned ["ABC"]} "\r"]
         (process-simple-string-wrapper {} "ABC\r")))
  (is (= [{:end true :scanned ["ABC" ""]} ""]
         (process-simple-string-wrapper {:end false :scanned ["ABC"]} "\r\n"))))

(defn- process-bulk-string-wrapper [current-state input]
  (let [current-state (-> current-state
                          (update-in [:scanned] #(map string->byte-buffer %)))
        input         (string->byte-buffer input)
        [s i]         (process-bulk-string current-state input)]
    [(-> s
         (dissoc :mode)
         (update-in [:scanned] #(map byte-buffer->string %)))
     (byte-buffer->string i)]))

(deftest process-bulk-string-test
  (is (= [{:scanned    []
           :size       3
           :end        false
           :nil-string false
           :got        0}
          "ABC\r\n"]
         (process-bulk-string-wrapper {} "3\r\nABC\r\n")))
  (is (= [{:scanned    ["ABC"]
           :end        true
           :size       3
           :got        3}
          nil]
         (process-bulk-string-wrapper {:scanned []
                                       :size    3
                                       :got     0}
                                      "ABC\r\n")))
  (is (= [{:scanned ["3"]} "\r"]
         (process-bulk-string-wrapper {} "3\r")))
  (is (= [{:scanned    []
           :size       3
           :nil-string false
           :end        false
           :got        0}
          ""]
         (process-bulk-string-wrapper {:scanned ["3"]} "\r\n")))
  (is (= [{:scanned    []
           :size       -1
           :got        0
           :end        true
           :nil-string true}
          ""]
         (process-bulk-string-wrapper {}
                                      "-1\r\n"))))

(defn- process-ary-wrapper [current-state input]
  (let [current-state (-> current-state
                          (update-in [:scanned] #(map string->byte-buffer %)))
        input         (string->byte-buffer input)
        [s i]         (process-ary current-state input)]
    [(-> s
         (dissoc :mode)
         (update-in [:scanned] #(map byte-buffer->string %)))
     (byte-buffer->string i)]))

(deftest process-ary-test
  #_(is (= [{:scanned []
           :size    0}
          ""] (process-ary-wrapper {} "0\r\n"))))

(deftest decoding-test
  (testing "simple strings"
    (is (= (->Str "TEST")
           (decode-one (dec "+TEST\r\n")))))
  (testing "errors"
    (is (= (->Err "I AM AN ERROR")
           (decode-one (dec "-I AM AN ERROR\r\n")))))
  (testing "integers"
    (is (= (->resp 1) (decode-one (dec ":1\r\n"))))
    (is (= (->resp 100) (decode-one (dec ":100\r\n"))))
    (is (= (->resp -10) (decode-one (dec ":-10\r\n")))))
  (testing "bulk string"
    (is (str= (->resp "TEST") (decode-one (dec "$4\r\nTEST\r\n"))))
    (is (str= (->resp "TEST\r\nTEST")
              (decode-one (dec "$10\r\nTEST\r\nTEST\r\n"))))
    (is (str= (->resp "") (decode-one (dec "$0\r\n\r\n"))))
    (is (= (->resp nil) (decode-one (dec "$-1\r\n")))))
  (testing "arrays"
    #_(is (= (->resp []) (decode-one (dec "*0\r\n"))))
    #_(is (= (->resp [1]) (io/decode resp-frame (.getBytes "*1\r\n:1\r\n"))))
    #_(is (= (->resp [(->Str "TEST")])
           (io/decode resp-frame (.getBytes "*1\r\n+TEST\r\n"))))
    #_(is (= (->resp ["TEST"]) (io/decode resp-frame (.getBytes "*1\r\n$4\r\nTEST\r\n"))))
    #_(is (= (->resp [nil]) (io/decode resp-frame (.getBytes "*1\r\n$-1\r\n"))))
    #_(is (= (->resp [1 ["TEST"]])
           (io/decode resp-frame (.getBytes "*2\r\n:1\r\n*1\r\n$4\r\nTEST\r\n"))))
    #_(is (= (->resp [1 "TEST\r\nTEST" nil (->Str "TEST")])
           (io/decode resp-frame
                      (.getBytes "*4\r\n:1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n+TEST\r\n"))))))

(defn- enc [test-value]
  (-> test-value ->resp encode-one (byte-streams/convert String)))

(deftest encoding-test
  (testing "strings"
    (is (= "$4\r\nTEST\r\n" (enc "TEST")))
    (is (= "$10\r\nTEST\r\nTEST\r\n" (enc "TEST\r\nTEST")))
    (is (= "$0\r\n\r\n" (enc "")))
    (is (= "$-1\r\n" (enc nil))))
  (testing "arrays"
    (is (= "*1\r\n$1\r\n1\r\n" (enc ["1"])))
    (is (= "*1\r\n$1\r\n1\r\n" (enc '("1"))))
    (is (= "*0\r\n" (enc [])))
    (is (= "*4\r\n$1\r\n1\r\n$10\r\nTEST\r\nTEST\r\n$-1\r\n$4\r\nTEST\r\n"
           (enc ["1" "TEST\r\nTEST" nil "TEST"])))))

(defn- enc-all [& test-values]
  (-> (map ->resp test-values)
      encode-all
      (byte-streams/convert String)))

(deftest encode-all-test
  (is (= "$4\r\nTEST\r\n$3\r\nING\r\n" (enc-all "TEST" "ING"))))
