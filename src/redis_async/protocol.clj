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
  (:import [jresp.protocol Ary BulkStr Int RespType]))

(defprotocol ToResp
  (->resp [this]))

(extend-protocol ToResp
  RespType
  (->resp [this]
    this)
  String
  (->resp [^String this]
    (BulkStr. this))
  Number
  (->resp [^Number this]
    (Int. this))
  clojure.lang.ISeq
  (->resp [^clojure.lang.ISeq this]
    (Ary. (map ->resp this)))
  clojure.lang.PersistentVector
  (->resp [^clojure.lang.PersistentVector this]
    (Ary. (map ->resp this)))
  nil
  (->resp [this]
    (BulkStr.)))

(defn ->clj [^RespType resp-type]
  (let [unwrapped (.unwrap resp-type)]
    (cond
      (= (class resp-type) jresp.protocol.Err)
      (ex-info unwrapped {:type :redis :msg unwrapped})

      (= (class resp-type) jresp.protocol.Ary)
      (vec unwrapped)

      :else
      unwrapped)))
