(ns redis-async.protocol
  (:require [gloss.core :refer :all]))

;; unify with real-frame
(def resp-frame-out (string :utf-8 :delimiters ["\r\n"]))

;; Reading

(defcodec resp-type
  (enum :byte {:str \+ :err \- :int \: :bulk-str \$ :ary \*}))

(defcodec resp-str
  (string :utf-8 :delimiters ["\r\n"]))

(defcodec resp-err
  (string :utf-8 :delimiters ["\r\n"]))

(defcodec resp-int
  (string-integer :utf-8 :delimiters ["\r\n"]))

(defcodec resp-bulk-str
  (finite-frame
   (prefix (string :utf-8 :delimiters ["\r\n"])
           (fn [len-str]
             (let [length (Long/parseLong len-str)]
               (if (< length 0)
                 0
                 (+ length 2))))
           str)
   (string :utf-8 :suffix "\r\n")))

(declare resp-frame)

(defcodec resp-ary
  (header
   (string-integer :utf-8 :delimiters ["\r\n"])
   (fn [ary-size]
     (compile-frame
      (if (= ary-size 0)
        []
        (repeat ary-size resp-frame))))
   (fn [ary]
     (count ary))))

(def ^:private resp-frames
  {:str      resp-str
   :err      resp-err
   :int      resp-int
   :bulk-str resp-bulk-str
   :ary      resp-ary})

(defcodec resp-frame
  (header resp-type
          resp-frames
          nil))
