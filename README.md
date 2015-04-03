# redis-async

[![Build Status](https://travis-ci.org/benashford/redis-async.svg)](https://travis-ci.org/benashford/redis-async)

Development for an asynchronous Redis client for Clojure.  Currently under development, not yet ready for use.

## Availability

Will be available via Clojars when it reaches the appropriate levels of completeness, stability and performance.

A snapshot of a (recent) development version is available on Clojars:

[![Clojars Project](http://clojars.org/redis-async/latest-version.svg)](http://clojars.org/redis-async)

## Design goals

There are two primary design goals.  First, to be async, using `core.async`, for the purposes of being easily used in an application already heavily based around that.  Second, to be as close to the Redis API as much as possible; it is a low-level API, but a very useful one.

## Features

* Full (when complete) implementation of all Redis commands.
* Support for Lua scripting (not yet started).
* Multiplex many concurrent requests onto a single Redis connection.
* Implicit pipelining.

## How to use

### Simple interactive example

```clojure
(require '[redis-async.core :as redis-async])
(require '[redis-async.client :as client])

;; Build a connection pool

(def p (redis-async/make-pool {:hostname "localhost" :port 6379}))

;; Set a value

(<!! (client/set p "KEY1" "VALUE-A"))

;; Get a value

(<!! (client/get p "KEY1"))

;; Will show "VALUE-A"

```

Each Redis command has an equivalent function in `redis-async.client`.  These can be called passing the connection pool as the first parameter.

### The connection pool

Rather than a traditional connection pool as a means of allowing multiple threads access to the database via multiple connections, this library will multiplex Redis commands from many threads into a single queue (pipelining if possible).  However some commands require a dedicated connecton, these are explained in more detail below.

To create a connection pool, call `make-pool` in `redis-async.core` passing in a map of connection details (host, port, password (if any), and database (defaults to 0)).  An empty map will default to localhost and the default Redis port.

To clean-up a connection pool at the end, call `close-pool` in `redis-async.core`.

This library does not enforce the use of any component systems, but the above was designed to painlessly be used by them.

### Client functions

Each function that implements a Redis command returns a channel, from which the result of that command can be read.  These can be read like any other `core.async` channel, or one of the convenience functions/macros can be used instead; the main difference between the convenience options and anything else is that they ensure conventions are in place (e.g. it allows a Redis operation to return nil, usually you cannot send nil through a `core.async` channel).

#### Exceptions to the rule

The vast majority of Redis commands are simple request/response commands.  There are a number however with behave differently, in that they either: a) return an arbitrary/infinite number of results, or b) are blocking.  These are implemented seperately with slightly different semantics.

##### `MONITOR`

The [`monitor`](http://redis.io/commands/monitor) command will return an infinite (until you tell it to stop) sequence of all activity on a Redis server.

The monitor function returns a channel, if call succeeds this yields a vector containing two channels.  The first channel contains each monitored command, on a line-by-line basis; the second is used to stop monitoring, closing this channel will stop.

##### `BLPOP`, `BRPOP`, and `BRPOPLPUSH`

All these commands are blocking, the Redis server will wait until data becomes available to return.  While you may think blocking operations are an anethema to an async client, it doesn't have to be so.

The Redis protocol defines that such operations will mean a connection cannot be used for any other purpose while it's blocked.  So the implementation of these blocking commands cannot use the single, shared, connection; users should therefore be aware that by using these commands will result in this library creating more connections.  Such additional connections will be reused where appropriate, the number created therefore will be the maximum of the number of concurrent blocking connections.

On the other hand, with an async client, such "blocking" operations will never block a thread so can be used for triggering/co-ordinating asynchronous code.  For example:

```clojure
redis-async.client> (def p (make-pool {}))
#'redis-async.client/p
redis-async.client> (a/go (let [[list msg] (<! (blpop p "LIST1" 0))] (println "*** Got" msg "from" li
st)))
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@49bc5015>
redis-async.client> ;; time passes
redis-async.client> (rpush p "LIST1" "Some Data")
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@155de840>

;; Std-out then shows:
*** Got Some Data from LIST1
```

### Other client functions

The convenience functions for dealing with channels follow the same naming convention as `core.async` namely using a single `!` for those which work in a `go` block, and a double `!!` for those outside of a `go` block.

`<!` and `<!!` simply read a value from a channel, but will substitute nils correctly, this means that you cannot use nil to understand when the end of the channel has been reached, you need to know how many values to expect, which is trivial in most circumstances.

`wait!` and `wait!!` throw away the value, but can be used to ensure that the action has happened before proceeding.

`faf` is "fire-and-forget", just move on.  This will ensure the channel is fully consumed, but doesn't wait.

## Why not just use ...?

TBC: list of similarities and differences with other libraries

## Performance

TODO: some benchmarks

### Implicit pipelining

Unlike most other Redis clients, `redis-async` will automatically pipeline multiple commands issued within a similar frame of time into a single request/response cycle.  It does so in a way that does not harm the worst case, but significantly improves the best case.

For instance, many commands in immediate succession, without waiting for results, will be sent together:

```clojure
(require '[redis-async.core :as redis-async])
(require '[redis-async.client :as client])

(def p (redis-async/make-pool {})) ;; hostname and port defaults will connect to a local Redis.

(let [c1 (client/set p "X" "TEST")
      c2 (client/set p "Y" "TEST2")
      c3 (client/get p "X")
      c4 (client/get p "Y")]
  (println (client/<!! c3))
  (println (client/<!! c4)))
```

This prints the expected outcome:

```
TEST
TEST2
```

The data sent between client and server, as captured by `ngrep` is as follows:

```
T 127.0.0.1:55817 -> 127.0.0.1:6379 [AP]
*3..$3..SET..$1..X..$4..TEST..*3..$3..SET..$1..Y..$5..TEST2..*2..$3..GET..$1..X..*2..$3..GET..$1
..Y..
##
T 127.0.0.1:6379 -> 127.0.0.1:55817 [AP]
+OK..+OK..$4..TEST..$5..TEST2..
##
```

However, should their be a gap in commands due to: waiting for a response to a previous command, or doing something else entirely; then the commands will be grouped into multiple request/response cycles.  For example:

```clojure
(let [c1 (client/set p "X" 0)
      c2 (client/incr p "X")
      c3 (client/set p "Y" (client/<!! c2))
      c4 (client/get p "Y")]
  (println (client/<!! c4)))
```

This prints the single digit `1`, but `ngrep` shows how it's split the commands into two requests at the point where there's a need to wait for the rest of a previous command:

```
T 127.0.0.1:55817 -> 127.0.0.1:6379 [AP]
*3..$3..SET..$1..X..$1..0..*2..$4..INCR..$1..X..
##
T 127.0.0.1:6379 -> 127.0.0.1:55817 [AP]
+OK..:1..
##
T 127.0.0.1:55817 -> 127.0.0.1:6379 [AP]
*3..$3..SET..$1..Y..$1..1..*2..$3..GET..$1..Y..
##
T 127.0.0.1:6379 -> 127.0.0.1:55817 [AP]
+OK..$1..1..
##
```

## Still to-do

1. Blocking commands (documentation).
2. Pub/sub commands.
3. Transaction commands.
4. Explain RESP objects (e.g. DUMP/RESTORE)
4. Release 0.1.0 version.
5. Performance testing.
6. Test coverage.
7. Redis authentication.
8. Scripting support.
9. Cluster support.
10. Create Clojure 1.7 version using transducers

## License

```
Copyright 2015 Ben Ashford

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
