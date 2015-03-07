# redis-async

Development for an asynchronous Redis client for Clojure.  Currently under development, not yet ready for use.

## Design goals

There are two primary design goals.  First, to be async, using `core.async`, to be easily used in an application already heavily based around that.  Second, to be as close to the Redis API as much as possible; it is a low-level API, but a very useful one.

## How to use

### Simple example

```clojure
(require '[redis-async.client :refer :all])

;; Build a connection pool

(def p (make-pool {:hostname "localhost" :port 6439}))

;; Set a value

(<!! (set p "KEY1" "VALUE-A"))

;; Get a value

(<!! (get p "KEY1"))

;; Will show "VALUE-A"

```

Each Redis command has an equivalent function in `redis-async.client`.  These can be called passing the connection pool as the first parameter, or alternatively, commands can be pipelined in which case the pool can be omitted because it's used as a parameter to the pipeline instead.

Example of pipelining:

```clojure
;; assumes p is a pool as per previous example

(wait!! (pipelined p (set "X" "TEST1") (set "Y" "TEST2"))

(def ch (pipelined p (get "X") (get "Y")))

(<!! ch) ;; "TEST1"
(<!! ch) ;; "TEST2"
```

Each function that implements a Redis command returns a channel, from which the result of that command can be read.  These can be read like any other `core.async` channel, or one of the convenience functions/macros can be used instead; the main difference between the convenience options and anything else is that they ensure conventions are in place (e.g. it allows a Redis operation to return nil, usually you cannot send nil through a `core.async` channel).

*IMPORTANT* Each channel should be fully consumed, otherwise a connection may become stuck.

## Still to-do

1. Utility readers for idiomatic reading.
2. Use core async channels when writing.
3. Documentation.
4. Scripting support.
5. Handling errors from Redis.
6. The 'monitor' command.
7. Pub/sub commands.
8. Transaction commands.
9. Test coverage.
10. Multiple-connections per client.
11. Testing/handling bad/failed connections.
12. Ensure efficient pipelining (at present it's sending each command one-by-one anyway)
13. Ensure that closed connections remove themselves from the connection pool. (Or ensure that closure via the connection-pool is the only way to do it.)
1. Auto-generate client functions from commands.json
