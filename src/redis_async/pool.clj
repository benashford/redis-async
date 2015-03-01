(ns redis-async.pool)

;; Redis connection pool

(defprotocol ConnectionFactory
  (test-con [this con])
  (new-con [this]))

(defprotocol Pool
  (get-connection [this]))

(defrecord ConnectionPool [connection-factory connections]
  Pool
  (get-connection [this]
    (loop []
      (dosync
       (let [con (when-not (empty? @connections)
                   (rand-nth @connections))]
         (cond
          (nil? con)
          (let [con (new-con connection-factory)]
            (alter connections conj con)
            con)

          (test-con connection-factory con)
          con

          :else
          (do
            (alter connections #(remove #{con} %))
            (recur))))))))

(defn make-pool [con-fac]
  (ConnectionPool. con-fac (ref [])))
