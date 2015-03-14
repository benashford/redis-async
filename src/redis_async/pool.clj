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

(ns redis-async.pool)

;; Redis connection pool

(defprotocol ConnectionFactory
  (test-con [this con])
  (new-con [this pool])
  (close-con [this con]))

(defprotocol Pool
  (get-connection [this])
  (close-pool [this]))

(defrecord ConnectionPool [connection-factory connections]
  Pool
  (get-connection [this]
    (loop []
      (dosync
       (let [con (when-not (empty? @connections)
                   (rand-nth @connections))]
         (cond
          (nil? con)
          (let [con (new-con connection-factory this)]
            (alter connections conj con)
            con)

          (test-con connection-factory con)
          con

          :else
          (do
            (alter connections #(remove #{con} %))
            (recur)))))))
  (close-pool [this]
    (doseq [connection @connections]
      (close-con connection-factory connection))))

(defn make-pool [con-fac]
  (ConnectionPool. con-fac (ref [])))
