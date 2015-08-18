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

(ns redis-async.scripting
  (:require [clojure.core.async :as a]
            [redis-async.core :as core]
            [redis-async.client :as client]
            [redis-async.protocol :as protocol]))

(def ^:private misc (atom {}))

(defn script-name->sha [pool script-name]
  (get-in @misc [pool :scripts script-name]))

(defn save-script [pool script-name script-body]
  (if script-body
    (a/go
      (let [result (a/<! (client/script-load pool script-body))]
        (if-not (core/is-error? result)
          (swap! misc assoc-in [pool :scripts script-name] (protocol/->clj result)))
        result))
    (throw (ex-info "No script provided" {:name script-name}))))

(defn call-saved-script [pool sha keys args]
  (apply client/evalsha pool sha (count keys) (concat keys args)))

(defmacro defscript [script-name script-body]
  (let [script-name-str (name script-name)]
    `(defn ~script-name
       ([pool#]
        (~script-name pool# [] []))
       ([pool# keys#]
        (~script-name pool# keys# []))
       ([pool# keys# args#]
        (if-let [sha# (script-name->sha pool# ~script-name-str)]
          (call-saved-script pool# sha# keys# args#)
          (a/go
            (let [sha# (a/<! (save-script pool# ~script-name-str ~script-body))]
              (if (core/is-error? sha#)
                sha#
                (a/<! (call-saved-script pool# (protocol/->clj sha#) keys# args#))))))))))

(defn from
  "Convenience function to load a script into a String so it can be defined with
  defscript"
  [path-to-script]
  (when-let [script-url (clojure.java.io/resource path-to-script)]
    (slurp script-url)))
