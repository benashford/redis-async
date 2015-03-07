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
  (header
   (string-integer :utf-8 :delimiters ["\r\n"])
   (fn [str-size]
     (if (< str-size 0)
       (compile-frame []
                      (fn [_] [])
                      (fn [_] :nil))
       (string :utf-8 :length str-size :suffix "\r\n")))
   (fn [str]
     (count str))))

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
