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

(defprotocol ConnectionFactory
  ;; Create a new connection for the pool
  (new-con [this pool])
   ;; Close the connection
  (close-con [this con]))

(defprotocol Pool
  ;; Get a useable connection
  (get-connection [this])
  ;; Mark a connection as done
  (close-connection [this con])
  ;; Mark a connection as done, and reusable
  (finish-connection [this con])
  ;; Close all connections
  (close-all [this]))

(defrecord SharedConnectionPool [connection-factory connection]
  Pool
  ;; Returns the shared connection
  (get-connection [this]
    (if connection
      [connection this]
      (let [new-connection (new-con connection-factory this)]
        [new-connection (->SharedConnectionPool connection-factory new-connection)])))
  ;; Removes the shared connection
  (close-connection [this con]
    (if (= con connection)
      (do
        (close-con connection-factory con)
        (->SharedConnectionPool connection-factory nil))
      this))
  ;; A no-op in this circumstance
  (finish-connection [this con] this)
  ;; Close all
  (close-all [this]
    (close-connection this connection)))

(defn make-shared-connection [connection-factory]
  (->SharedConnectionPool connection-factory nil))

(defrecord DedicatedConnectionPool [connection-factory connections]
  Pool
  ;; Creates a new connection from the pool, always, connections are never
  ;; reused
  (get-connection [this]
    (let [new-connection (new-con connection-factory this)]
      [new-connection (->DedicatedConnectionPool connection-factory
                                                 (conj connections new-connection))]))
  ;; Removes the connection from the list of borrowed connections
  (close-connection [this con]
    (if (contains? connections con)
      (do
        (close-con connection-factory con)
        (->DedicatedConnectionPool connection-factory (disj connections con)))
      this))
  ;; An alias for close-connection
  (finish-connection [this con]
    (close-connection this con))
  ;; Close all
  (close-all [this]
    (doseq [con connections]
      (close-con connection-factory con))
    (->DedicatedConnectionPool connection-factory #{})))

(defn make-dedicated-connection [connection-factory]
  (->DedicatedConnectionPool connection-factory #{}))

(defrecord BorrowedConnectionPool [connection-factory
                                   borrowed-connections
                                   pending-connections]
  Pool
  ;; Uses a pending-connection, or creates a new one
  (get-connection [this]
    (if (empty? pending-connections)
      (let [new-connection (new-con connection-factory this)]
        [new-connection
         (->BorrowedConnectionPool connection-factory
                                   (conj borrowed-connections new-connection)
                                   pending-connections)])
      (let [[first-free-con & other-free-cons] pending-connections]
        [first-free-con
         (->BorrowedConnectionPool connection-factory
                                   (conj borrowed-connections first-free-con)
                                   (into #{} other-free-cons))])))
  (close-connection [this con]
    (if (contains? borrowed-connections)
      (do
        (close-con connection-factory con)
        (->BorrowedConnectionPool connection-factory
                                  (disj borrowed-connections con)
                                  pending-connections))
      this))
  (finish-connection [this con]
    (->BorrowedConnectionPool connection-factory
                              (disj borrowed-connections con)
                              (conj pending-connections con)))
  (close-all [this]
    (doseq [con (concat borrowed-connections pending-connections)]
      (close-con connection-factory con))
    (->BorrowedConnectionPool connection-factory #{} #{})))

(defn make-borrowed-connection [connection-factory]
  (->BorrowedConnectionPool connection-factory #{} #{}))
