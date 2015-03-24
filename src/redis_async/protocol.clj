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
  (:require [clojure.core.async :as a]
            [byte-streams :as byte-streams])
  (:import [java.nio ByteBuffer]
           [io.netty.buffer ByteBuf]))

;; utilities

(defn bytes->str [bytes]
  (String. bytes))

(defn str->bytes [^String string]
  (.getBytes string))

(defn ->byte-buffer [thing]
  (byte-streams/convert thing java.nio.ByteBuffer))

;; Utility constants

(def ^:private deliminator #(->byte-buffer "\r\n"))
(def ^:private dollar #(->byte-buffer "$"))
(def ^:private star #(->byte-buffer "*"))

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
      [(dollar)
       (->byte-buffer (str (count bytes)))
       (deliminator)
       (->byte-buffer bytes)
       (deliminator)]
      [(dollar)
       (->byte-buffer "-1")
       (deliminator)]))
  ToResp
  (->resp [this] this))

(defrecord Ary [values]
  RespType
  (get-type [this] :ary)
  (->clj [this] (map ->clj values))
  (->raw [this]
    (list* (star)
           (->byte-buffer (str (count values)))
           (deliminator)
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

(def ^:private byte->mode
  {43 :str
   45 :err
   58 :int
   36 :bulk-str})

(def ^:private first-delimiter 13)
(def ^:private second-delimiter 10)

(defn scan-for
  "Scan forward a set number of bytes"
  [^ByteBuffer input byte-count]
  {:pre [(not (nil? input))
         (>= byte-count 0)]}
  (let [start-pos       (.position input)
        limit           (.limit input)
        end-pos         (min byte-count limit)
        fully-consumed? (= end-pos limit)
        remaining-input (when-not fully-consumed?
                          (.position input end-pos)
                          (let [ri (.slice input)]
                            (.position input start-pos)
                            (.limit input end-pos)
                            ri))]
    {:scanned input
     :input   remaining-input
     :end     (= (- end-pos start-pos) byte-count)}))

(defn scan-until-delimiter
  "Scan until the delimiter is found"
  [^ByteBuffer input]
  (let [start-pos (.position input)
        limit     (.limit input)]
    (loop [pos       start-pos
           last-char nil]
      (if (>= pos limit)
        (if (= last-char first-delimiter)
          ;; End of stream, but might be half-way through the delimiter
          (let [end-pos         (dec pos)
                remaining-input (do
                                  (.position input end-pos)
                                  (.slice input))
                scanned         (do
                                  (.position input start-pos)
                                  (.limit input end-pos))]
            {:scanned scanned
             :input   remaining-input
             :end     false})
          {:scanned (.position input start-pos)
           :input   nil
           :end     false})
        (let [next-char (.get input)]
          (if (and (= last-char first-delimiter)
                   (= next-char second-delimiter))
            (let [remaining-input (do
                                    (.position input (inc pos))
                                    (.slice input))
                  scanned         (do
                                    (.position input start-pos)
                                    (.limit input (dec pos)))]
              {:scanned scanned
               :input   remaining-input
               :end true})
            (recur (inc pos)
                   next-char)))))))

(defn process-simple-string
  "Process a simple string, this is an arbitrary number of bytes ending with
   delimiter"
  [current-state input]
  (let [result (scan-until-delimiter input)]
    [(-> current-state
         (dissoc :input)
         (assoc :end (:end result))
         (update-in [:scanned] concat (list (:scanned result))))
     (:input result)]))

(defn- process-error
  "For most purposes an error is the same as a simple-string"
  [current-state input]
  (process-simple-string current-state input))

(defn- process-int
  "For most purposes an int is the same as a simple-string"
  [current-state input]
  (process-simple-string current-state input))

(defn- parse-int [scanned]
  (->> (byte-streams/convert scanned String) Integer/parseInt))

(defn process-bulk-string
  "Process a bulk string, this takes the form of a header saying the length of
  the string, followed by the string surrounded by delimiters"
  [current-state input]
  (if-let [size (:size current-state)]
    ;; size is already known, so can read the rest
    (let [needed              (- (+ size 2) (:got current-state))
          result              (scan-for input needed)
          ^ByteBuffer scanned (:scanned result)
          end                 (:end result)]
      (when end (.limit scanned (- (.limit scanned) 2)))
      [(-> current-state
           (assoc :end end)
           (update-in [:scanned] concat [scanned])
           (update-in [:got] + (.remaining scanned)))
       (:input result)])
    ;; size is not yet known, so needs to be determined
    (let [result        (scan-until-delimiter input)
          current-state (-> current-state
                            (assoc :end (:end result))
                            (update-in [:scanned] concat [(:scanned result)]))]
      [(if (:end current-state)
         (let [size       (parse-int (:scanned current-state))
               nil-string (< size 0)]
           {:mode       (:mode current-state)
            :size       size
            :nil-string nil-string
            :end        nil-string
            :got        0})
         (dissoc current-state :end))
       (:input result)])))

(def ^:private process-fns
  {:str      process-simple-string
   :err      process-error
   :int      process-int
   :bulk-str process-bulk-string})

(defn- result-simple-string [{:keys [scanned] :as state}]
  (->Str (byte-streams/convert scanned String)))

(defn- result-error [{:keys [scanned] :as state}]
  (->Err (byte-streams/convert scanned String)))

(defn- result-int [{:keys [scanned] :as state}]
  (->Int (parse-int scanned)))

(defn- result-bulk-string [{:keys [scanned nil-string] :as state}]
  (if nil-string
    (->BulkStr nil)
    (let [^ByteBuffer combined-buffer (->byte-buffer scanned)]
      (->BulkStr (byte-streams/to-byte-array combined-buffer)))))

(def ^:private result-fns
  {:str      result-simple-string
   :err      result-error
   :int      result-int
   :bulk-str result-bulk-string})

(defn- mode-result [mode state]
  (let [result-fn (result-fns mode)]
    (if result-fn
      (result-fn state)
      (println "WARNING: unknown mode -" mode))))

(defn- process-state
  "Process the current state, as much as possible, in a non-blocking manner.
   Returns the new state and any left-over input."
  [{:keys [mode] :as current-state} input]
  (println "PROCESS STATE|current-state:" (pr-str current-state)
           "PROCESS STATE|input:" (pr-str input))
  (let [process-fn        (process-fns mode)
        [new-state input] (if process-fn
                            (process-fn current-state input)
                            (println "WARNING: unknown mode -" mode))]
    (println "PROCESS STATE|new-state:" (pr-str new-state)
             "PROCESS STATE|input:" (pr-str input))
    [input (if (:end new-state)
             (assoc new-state :result (mode-result mode new-state))
             new-state)]))

(defprotocol ToNioByteBuffer
  (->nio [this]))

(extend-protocol ToNioByteBuffer
  ByteBuffer
  (->nio [this] this)
  ByteBuf
  (->nio [this] (.nioBuffer this))
  nil
  (->nio [this] nil))

(defn decode
  "Raw channel will contain bytes, these are read and written to in-ch which is
   the input channel for higher-level logic."
  [raw-ch in-ch]
  (a/go
    (loop [^ByteBuffer input nil
           state             '({})]
      ;; Debugging - to be deleted
      (println "LOOP STATE:")
      (clojure.pprint/pprint {:input input :state state})
      (let [readable-bytes (if input
                             (.remaining input)
                             0)]
        ;; Do we have any bytes to read
        (if (< readable-bytes 2)
          (do
            (println "WAITING FOR NEW INPUT")
            (let [more-input             (a/<! raw-ch)
                  ^ByteBuffer more-input (->nio more-input)]
              (println "NEW INPUT:" (pr-str more-input))
              (if more-input
                (recur (if input
                         (->byte-buffer [input more-input])
                         more-input) ;; join left over input with new input
                       state)
                (do
                  (println "Closing connection with state:" (pr-str state))
                  (a/close! in-ch)))))
          (let [[current-state & other-state] state
                mode                          (:mode current-state)]
            (if (not mode)
              ;; discover next mode
              (let [first-byte (.get input)
                    next-mode  (byte->mode first-byte)]
                (recur input (conj other-state {:mode next-mode})))
              ;; we know the mode
              (let [[input current-state] (process-state current-state input)]
                (println "PROCESSED STATE|input:" (pr-str input))
                (if (:end current-state)
                  (let [result (:result current-state)]
                    (if (empty? other-state)
                      (do
                        (println "--RESP-->" (pr-str result))
                        (a/>! in-ch result)
                        (recur input other-state))
                      (recur input (let [[next-state remaining-states] other-state]
                                     (cons (update-in next-state [:scanned] conj result)
                                           remaining-states)))))
                  (recur input (conj other-state current-state)))))))))))

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
