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
  (:require [byte-streams :as byte-streams]))

;; utilities

(defn bytes->str [bytes]
  (String. bytes))

(defn str->bytes [^String string]
  (.getBytes string))

(defn ->byte-buffer [thing]
  (byte-streams/convert thing java.nio.ByteBuffer))

;; Utility constants

(def ^:private deliminator (->byte-buffer "\r\n"))
(def ^:private dollar (->byte-buffer "$"))
(def ^:private star (->byte-buffer "*"))

;; Resp types

(defprotocol RespType
  (get-type [this])
  (->clj [this])
  (->raw [this]))

(defprotocol ToResp
  (->resp [this]))

(defrecord Str [string]
  RespType
  (get-type [this] :str)
  (->clj [this] string)
  (->raw [this] nil)
  ToResp
  (->resp [this] this))

(defrecord Err [bytes]
  RespType
  (get-type [this] :err)
  (->clj [this]
    (let [msg (bytes->str bytes)]
      (ex-info (str "Error from Redis: " msg) {:type  :redis
                                               :msg   msg
                                               :bytes bytes})))
  (->raw [this] nil)
  ToResp
  (->resp [this] this))

(defrecord Int [value]
  RespType
  (get-type [this] :int)
  (->clj [this] value)
  (->raw [this] nil)
  ToResp
  (->resp [this] this))

(defrecord BulkStr [bytes]
  RespType
  (get-type [this] :bulk-str)
  (->clj [this] (when bytes (bytes->str bytes)))
  (->raw [this]
    (if bytes
      [dollar
       (->byte-buffer (str (count bytes)))
       deliminator
       (->byte-buffer bytes)
       deliminator]
      [dollar
       (->byte-buffer "-1")
       deliminator]))
  ToResp
  (->resp [this] this))

(defrecord Ary [values]
  RespType
  (get-type [this] :ary)
  (->clj [this] (map ->clj values))
  (->raw [this]
    (list* star
           (->byte-buffer (str (count values)))
           deliminator
           (map (comp ->byte-buffer ->raw) values)))
  ToResp
  (->resp [this] this))

(extend-protocol ToResp
  String
  (->resp [this] (->BulkStr (str->bytes this)))
  Long
  (->resp [this] (->Int this))
  clojure.lang.IPersistentCollection
  (->resp [this] (->Ary (map ->resp this)))
  nil
  (->resp [this] (->BulkStr nil)))

;; Encoding and decoding between raw bytes (via buffers), and types defined
;; above

(defn decode
  "Raw channel will contain bytes, these are read and written to in-ch which is
   the input channel for higher-level logic."
  [raw-ch in-ch])

(defn encode-one [frame]
  (->raw frame))

(defn encode-all
  "Takes multiple frames and produces byte buffer"
  [frames]
  (map encode-one frames))

;; Gloss codecs for resp get-types

#_(defcodec resp-type
  (enum :byte {:str \+ :err \- :int \: :bulk-str \$ :ary \*}))

#_(def resp-str
  (compile-frame (string :utf-8 :delimiters ["\r\n"])
                 (fn [str] (:string str))
                 (fn [str] (->Str str))))

#_(def resp-err
  (compile-frame (repeated :byte :delimiters ["\r\n"])
                 (fn [err] (:bytes err))
                 (fn [str] (->Err str))))

#_(def resp-int
  (compile-frame (string-integer :utf-8 :delimiters ["\r\n"])
                 (fn [i] (:value i))
                 (fn [i] (->Int i))))

#_(def ^:private r-n-bytes (.getBytes "\r\n"))

#_(defcodec resp-bulk-str
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

#_(declare resp-frame)

#_(defcodec resp-ary
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

#_(def ^:private resp-frames
  {:str      resp-str
   :err      resp-err
   :int      resp-int
   :bulk-str resp-bulk-str
   :ary      resp-ary})

#_(defcodec resp-frame
  (header resp-type
          resp-frames
          (fn [data]
            (get-type data))))
