# redis-async

[![Build Status](https://travis-ci.org/benashford/redis-async.svg)](https://travis-ci.org/benashford/redis-async)

Development for an asynchronous Redis client for Clojure.  Currently under development, not yet ready for use.

## Availability

Will be available via Clojars when it reaches the appropriate levels of completeness and stability.

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
(require '[redis-async.client :refer :all])

;; Build a connection pool

(def p (make-pool {:hostname "localhost" :port 6379}))

;; Set a value

(<!! (set p "KEY1" "VALUE-A"))

;; Get a value

(<!! (get p "KEY1"))

;; Will show "VALUE-A"

```

Each Redis command has an equivalent function in `redis-async.client`.  These can be called passing the connection pool as the first parameter.

#### Client functions

Each function that implements a Redis command returns a channel, from which the result of that command can be read.  These can be read like any other `core.async` channel, or one of the convenience functions/macros can be used instead; the main difference between the convenience options and anything else is that they ensure conventions are in place (e.g. it allows a Redis operation to return nil, usually you cannot send nil through a `core.async` channel).

##### Exceptions to the rule

The vast majority of Redis commands are simple request/response commands.  There are a number however with behave differently, in that they either: a) return an arbitrary/infinite number of results, or b) are blocking.  These are implemented seperately with slightly different semantics.

TODO: details.

#### Other client functions

The convenience functions for dealing with channels follow the same naming convention as `core.async` namely using a single `!` for those which work in a `go` block, and a double `!!` for those outside of a `go` block.

`<!` and `<!!` simply read a value from a channel, but will substitute nils correctly, this means that you cannot use nil to understand when the end of the channel has been reached, you need to know how many values to expect, which is trivial in most circumstances.

`wait!` and `wait!!` throw away the value, but can be used to ensure that the action has happened before proceeding.

`faf` is "fire-and-forget", just move on.  This will ensure the channel is fully consumed, but doesn't wait.

## Why not just use ...?

TBC: list of similarities and differences with other libraries

## Performance

### Implicit pipelining

Unlike most other Redis clients, `redis-async` will automatically pipeline multiple commands issued within a similar frame of time into a single request/response cycle.  It does so in a way that does not improve the worst case, but significantly improves the best case.

TODO: examples.

## Still to-do

1. Implicit pipelining examples.
2. Document commands that don't make sense in an async context.
1. The 'monitor' command.
2. Document the 'monitor' command.
3. Pattern of cmds channel on cmd-ch channel is repeated.  Should be DRY'ed.
4. Make all return channels the correct size
2. Pub/sub commands.
3. Transaction commands.
4. Test coverage.
5. Gloss - test finite-frame rather than producing specific frames of arbitrary size.
5. Publish to Clojars
6. Ensure backpressure is handled correctly.
7. Documentation.
8. Redis authentication.
9. Scripting support.
11. Ensure efficient pipelining (at present it's sending each command one-by-one anyway)
14. Create Clojure 1.7 version using transducers
15. Benchmarking
16. Split pipelined commands into multiple packages when exceeding limits, and/or add a buffered channel of the right size.

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
