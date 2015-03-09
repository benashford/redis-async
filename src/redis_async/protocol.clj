;; Copyright 2015 Ben Ashford
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns redis-async.protocol
  (:require [gloss.core :refer :all]))

;; Utility functions

(defn seq->str [byte-v]
  (String. (byte-array byte-v)))

(defn str->seq [^String in-str]
  (into [] (.getBytes in-str)))

;; Resp types

(defprotocol RespType
  (get-type [this])
  (->clj [this]))

(defprotocol ToResp
  (->resp [this]))

(defrecord Str [string]
  RespType
  (get-type [this] :str)
  (->clj [this] string)
  ToResp
  (->resp [this] this))

(defrecord Err [bytes]
  RespType
  (get-type [this] :err)
  (->clj [this]
    (let [msg (seq->str bytes)]
      (ex-info (str "Error from Redis:" msg) {:type  :redis
                                              :msg   msg
                                              :bytes bytes})))
  ToResp
  (->resp [this] this))

(defrecord Int [value]
  RespType
  (get-type [this] :int)
  (->clj [this] value)
  ToResp
  (->resp [this] this))

(defrecord BulkStr [bytes]
  RespType
  (get-type [this] :bulk-str)
  (->clj [this] (when bytes (seq->str bytes)))
  ToResp
  (->resp [this] this))

(defrecord Ary [values]
  RespType
  (get-type [this] :ary)
  (->clj [this] (map ->clj values))
  ToResp
  (->resp [this] this))

(extend-protocol ToResp
  String
  (->resp [this] (->BulkStr (str->seq this)))
  Long
  (->resp [this] (->Int this))
  clojure.lang.IPersistentCollection
  (->resp [this] (->Ary (map ->resp this)))
  nil
  (->resp [this] (->BulkStr nil)))

;; Gloss codecs for resp get-types

(defcodec resp-type
  (enum :byte {:str \+ :err \- :int \: :bulk-str \$ :ary \*}))

(def resp-str
  (compile-frame (string :utf-8 :delimiters ["\r\n"])
                 (fn [str] (:string str))
                 (fn [str] (->Str str))))

(def resp-err
  (compile-frame (repeated :byte :delimiters ["\r\n"])
                 (fn [err] (:bytes err))
                 (fn [str] (->Err str))))

(def resp-int
  (compile-frame (string-integer :utf-8 :delimiters ["\r\n"])
                 (fn [i] (:value i))
                 (fn [i] (->Int i))))

(def ^:private r-n-bytes (.getBytes "\r\n"))

(defcodec resp-bulk-str
  (header
   (string-integer :utf-8 :delimiters ["\r\n"])
   (fn [str-size]
     (if (< str-size 0)
       (compile-frame []
                      (fn [_] [])
                      (fn [_] (->BulkStr nil)))
       (compile-frame (repeat (+ str-size 2) :byte)
                      (fn [in-str]
                        (concat (:bytes in-str) r-n-bytes))
                      (fn [bytes]
                        (->BulkStr (subvec bytes 0 (- (count bytes) 2)))))))
   (fn [str]
     (if-let [bytes (:bytes str)]
       (count bytes)
       -1))))

(declare resp-frame)

(defcodec resp-ary
  (header
   (string-integer :utf-8 :delimiters ["\r\n"])
   (fn [ary-size]
     (compile-frame
      (if (= ary-size 0)
        []
        (repeat ary-size resp-frame))
      (fn [ary] (:values ary))
      (fn [ary] (->Ary ary))))
   (fn [ary]
     (count (:values ary)))))

(def ^:private resp-frames
  {:str      resp-str
   :err      resp-err
   :int      resp-int
   :bulk-str resp-bulk-str
   :ary      resp-ary})

(defcodec resp-frame
  (header resp-type
          resp-frames
          (fn [data]
            (get-type data))))
