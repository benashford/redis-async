# jresp

[![Build Status](https://travis-ci.org/benashford/jresp.svg?branch=master)](https://travis-ci.org/benashford/jresp)

An asynchronous implementation of the [Redis Serialization Protocol (RESP)](http://redis.io/topics/protocol) in Java.

This is *not* a Redis client, although it might become one with time, but rather a library specifically for the protocol to allow Redis clients to be built using it for various JVM languages.

## Requirements

Java 8 or higher.

## What is it?

`jresp` provides implementations for each type specified as part of the Redis Serialization Protocol (RESP), specifically: [simple strings](src/main/java/jresp/protocol/SimpleStr.java), [errors](src/main/java/jresp/protocol/Err.java), [integers](src/main/java/jresp/protocol/Int.java), [bulk strings](src/main/java/jresp/protocol/BulkStr.java), and [arrays](src/main/java/jresp/protocol/Ary.java).  All these implement the `RespType` interface.

It also provides a way of communicating with a Redis server called [`Connection`](src/main/java/jresp/Connection.java).  These are created using the [`Client`](src/main/java/jresp/Client.java).

When creating a `Connection` an object/closure implementing [`Responses`](src/main/java/jresp/Responses.java) needs to be provided.  Messages are written using the `write` method on `Connection`; each response from the server will result in `responseReceived` on the implementation of `Responses`.

A higher-level [`Pool`](src/main/java/jresp/pool/Pool.java) can be used for connection pooling.

Example:

```java
Client c = new Client("localhost", 6379);
Pool p = new Pool(c);
p.getShared().write(new Ary(Arrays.asList(new BulkStr("PING"))), resp -> System.out.println("Got: " + resp));
```

This should print: `Got: PONG`

The final line of that example looks a bit verbose.  This is because communication between client and server takes place using RESP objects; each command to Redis is a RESP array containing one or more bulk strings for the command name and arguments.

JRESP implements implicit pipelining.  Multiple commands are sent by calling `write` multiple times, the commands are serialized and written to a buffer.  The buffer will send data across the wire to Redis when the socket signals it is ready for more data, as much data as is possible, comprising multiple commands, will be sent in one request.

Each response will be an implementation of `RespType`, the exact type will depend on the Redis command called, see the [Redis command documentation](http://redis.io/commands) for details.

Every `RespType` can be converted to the corresponding Java type by calling `unwrap`.  E.g a `BulkStr` becomes a `String`.  This is a recursive process for RESP arrays, the array becomes a `List` and each element of the array has `unwrap` called in turn.

## What isn't it?

`jresp` is not a Redis client.  It has no knowledge of the specific Redis commands or responses.  Nor how to handle the special-case commands like `MONITOR`, it is low-level enough that it doesn't have to worry about all these things, it is just a conduit for passing serialised RESP data from client to server and back.

## What is it for?

There are pre-existing Redis clients for Java, both synchronous and asynchronous.  `jresp` was specifically intended to be the backend of multiple Redis clients for various JVM languages.

As of the time of writing, it is used for [`redis-async`, a async Redis client for Clojure](https://github.com/benashford/redis-async).  I have written about the development of `jresp` and `redis-async` [here](http://benashford.github.io/blog/2015/06/02/java-in-a-polygot-jvm-world/)

## TODO

1. Document pub/sub facilities and connection pool more.
2. Handling of stopped connections pools, etc.
3. Try using a single thread for managing groups.
4. Performance testing.
5. Redis clustering.
6. Create a full Java client (optional).
7. Tests regarding dropped connections.

## Licence

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
