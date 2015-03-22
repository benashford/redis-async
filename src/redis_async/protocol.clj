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
  (:import [java.nio ByteBuffer]))

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

(def ^:private byte->mode
  {43 :+
   45 :-})

(def ^:private first-delimiter 13)
(def ^:private second-delimiter 10)

(defn- scan-until-delimiter
  "Scan input - defined as a sequence of ByteBuffers - until the delimiter is
   found, or until the end of the input, whichever happens first.

   If the delimiter is found, it returns the bytes before and after the
   delimiter. If not, the current state is returned instead."
  [[^ByteBuffer first-input & other-input]]
  ;; The first input buffer may be partially consumed, we don't care about that
  ;; so slice at the first unread byte
  (loop [[^ByteBuffer first-input & other-input] (cons (.slice first-input) other-input)
         scanned-bufs                            []]
    (println "SCAN-UNTIL-DELIMITER|input:" (pr-str (cons first-input other-input))
             "scanned-bufs:" (pr-str scanned-bufs))
    (assert (not (nil? first-input)))
    (let [last-run      (nil? other-input)
          {:keys [^ByteBuffer scanned
                  ^ByteBuffer input
                  end]} (loop [pos       0
                               last-char nil]
                          (println "SUBSCAN|first-input:" (pr-str first-input)
                                   "pos:" pos
                                   "last-char:" last-char)
                          (if (= pos (.limit first-input))
                            (if (= last-char first-delimiter)
                              (let [real-pos (dec pos)]
                                {:scanned (.limit first-input real-pos)
                                 :input   (do
                                            (.position first-input real-pos)
                                            (.slice first-input))})
                              {:scanned first-input}))
                          (let [byte-at-pos (.get first-input)]
                            (if (and (= last-char first-delimiter)
                                     (= byte-at-pos second-delimiter))
                              ;; end of sequence found
                              {:scanned (.limit first-input (dec pos))
                               :input   (.slice first-input)
                               :end     true}
                              ;; carry on
                              (recur (inc pos)
                                     byte-at-pos))))
          [next-input & rest-input] other-input
          scanned-bufs (conj scanned-bufs (do
                                            (.rewind scanned)
                                            scanned))
          input-limit  (.limit input)
          other-input  (cond
                         (nil? input)
                         other-input

                         (= input-limit 0)
                         other-input

                         (and (= input-limit 1)
                              (not (nil? next-input)))
                         (cons (->byte-buffer [input next-input]) rest-input)

                         :else
                         (cons input other-input))]
      (if (or end last-run)
        {:scanned scanned-bufs
         :input   other-input
         :end     end}
        (recur other-input
               scanned-bufs)))))

(defn- process-simple-string
  "Process a simple string, this is an arbitrary number of bytes ending with \r\n"
  [current-state input]
  (let [result (scan-until-delimiter input)]
    [(-> current-state
          (assoc :end (:end result))
          (update-in [:scanned] concat (:scanned result)))
     (:input result)]))

(defn- process-error
  "For most purposes an error is the same as a simple-string"
  [current-state input]
  (process-simple-string current-state input))

(def ^:private process-fns
  {:+ process-simple-string
   :- process-error})

(defn- result-simple-string [{:keys [scanned] :as state}]
  (->Str (byte-streams/convert scanned String)))

(defn- result-error [{:keys [scanned] :as state}]
  (->Err (byte-streams/convert scanned String)))

(def ^:private result-fns
  {:+ result-simple-string
   :- result-error})

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
    [input (if (:end new-state)
             (assoc new-state :result (mode-result mode new-state))
             new-state)]))

(defn decode
  "Raw channel will contain bytes, these are read and written to in-ch which is
   the input channel for higher-level logic."
  [raw-ch in-ch]
  (a/go
    (loop [input '()
           state '({})]
      ;; Debugging - to be deleted
      (println "LOOP STATE: input empty -" (empty? input))
      (clojure.pprint/pprint {:input input :state state})
      ;; Do we have any bytes to read
      (if (empty? input)
        ;; Nope, read some bytes and recur with the same state
        (let [more-input (a/<! raw-ch)]
          (if more-input
            (recur (concat input more-input)
                   state)
            (do
              (println "Closing connection with state:" (pr-str state))
              (a/close! in-ch))))
        (let [[current-state & other-state] state
              mode                          (:mode current-state)]
          (if (not mode)
            ;; discover next mode
            (let [[next-input & other-input] input
                  first-byte                 (.get next-input)
                  _                          (println "FIRST BYTE:" first-byte)
                  next-mode                  (byte->mode first-byte)]
              (recur input (conj other-state {:mode next-mode})))
            ;; we know the mode
            (let [[input current-state] (process-state current-state input)]
              (if (:end current-state)
                (let [result (:result current-state)]
                  (if (empty? other-state)
                    (do
                      (a/>! in-ch result)
                      (recur input other-state))
                    (recur input (let [[next-state remaining-states] other-state]
                                   (cons (update-in next-state :scanned conj result)
                                         remaining-states)))))
                (recur input (conj other-state current-state))))))))))

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
