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
  (close-connection [this con])
  (borrow-connection [this])
  (return-connection [this con])
  (close-pool [this]))

(defn- pick-connection
  "Pick a connection from a pool, or open a new one"
  [pool]
  (let [connection-factory (:connection-factory pool)
        connections        (:connections pool)
        con                (when-not (empty? @connections)
                             (rand-nth @connections))]
    (cond
      (nil? con)
      (let [con (new-con connection-factory pool)]
        (alter connections conj con)
        con)

      (test-con connection-factory con)
      con

      :else
      (do
        (close-connection pool con)
        nil))))

(defn- remove-connection [connections con]
  (alter connections #(remove #{con} %)))

(defrecord ConnectionPool [connection-factory connections borrowed-connections]
  Pool
  (get-connection [this]
    (loop []
      (dosync
       (if-let [con (pick-connection this)]
         con
         (recur)))))
  (close-connection [this con]
    (dosync
     (remove-connection connections con)))
  (borrow-connection [this]
    (dosync
     (let [con (new-con connection-factory this)]
       (alter borrowed-connections conj con)
       con)))
  (return-connection [this con]
    (close-con connection-factory con)
    (dosync
     (remove-connection borrowed-connections con)))
  (close-pool [this]
    (doseq [connection @connections]
      (close-con connection-factory connection))))

(defn make-pool [con-fac]
  (->ConnectionPool con-fac (ref []) (ref [])))
