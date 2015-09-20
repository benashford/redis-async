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

As of the time of writing, it is used for [`redis-async`, a async Redis client for Clojure](https://github.com/benashford/redis-async).  I have written about the development of `jresp` and `redis-async` [here](http://benashford.github.io/blog/2015/06/02/java-in-a-polygot-jvm-world/), and [here](http://benashford.github.io/blog/2015/09/13/building-an-event-based-redis-client-in-java).

## Performance

So how does it perform compared to existing Java Redis clients?

I have adapted some basic tests which were originally developed for Stefan Wille's [test for a Redis client in Crystal](https://www.stefanwille.com/2015/05/redis-clients-crystal-vs-ruby-vs-c-vs-go/).  In my case I'm only interested in comparing against other Java clients, so I only test against [Jedis](https://github.com/xetorthio/jedis).

DISCLAIMER: this test only measures what it tests, Jedis has more features which are obviously not tested here.

### The test

The test simply sends one million `SET` commands to Redis, timing until the last response is received.  In all cases the test is repeated ten times within the same VM, and the time is taken of the last of the ten iterations.

### A baseline

As a baseline I use Jedis without pipelining.  Doing any kind of bulk Redis operation without Redis is obviously a bad idea, but I wanted a time for it just to show how much faster the pipelined version was.  The source for this test is [here](https://github.com/benashford/redis-client-benchmarks/blob/efe30991812f7fd3b84c44c423860fe0f1be8bfa/Java/JavaJedisPerformance.java#L13-L23).

One million `SET`s took: 44,091ms.

### Pipelined

Next is the same thing pipelined.  JRESP pipelines automatically, so [no specific changes](https://github.com/benashford/redis-client-benchmarks/blob/efe30991812f7fd3b84c44c423860fe0f1be8bfa/JResp/JRespPerformance.java#L23-L42) were needed to enable it; the Jedis test needed to be [slightly modified to make pipelining explicit](https://github.com/benashford/redis-client-benchmarks/blob/efe30991812f7fd3b84c44c423860fe0f1be8bfa/Java/JavaJedisPerformance.java#L25-L37).

One million `SET`s took Jedis: 1,369ms.  The same took JRESP: 1,226ms.

JRESP is approximately (on this one measure alone) 10% faster than Jedis; which, given the asynchronous nature makes sense.  Although until it is tested, it was unknown what, if any, overhead the need to manage the state would add; but it seems that the advantage of asynchronicity outweighs the overhead of state.

What is the advantage of asynchronicity? Jedis will be spending time first queueing, then writing the million commands to an `OutputStream`, and only when that has finished will it start to read and parse the results.  JRESP on the other-hand, will start writing data to the socket pretty much immediately, and start parsing the response when the first response becomes available; in other tests on similar volumes as much as 80% of the responses have been parsed by the time the final command has been written.

### Smaller chunks

The final test was to send one million commands using Jedis, but this time as 1,000 batches of 1,000 commands.  The theory here is to maximise the similarity as much as possible with the default JRESP behaviour.  There is no JRESP equivalent for this test for that reason, it is not possible to control exactly how may individual batches will be used; the batching works on the cycle of the socket becoming available for writing.

On this [final test](https://github.com/benashford/redis-client-benchmarks/blob/efe30991812f7fd3b84c44c423860fe0f1be8bfa/Java/JavaJedisPerformance.java#L39-L53), Jedis completed the million `SET`s in 1,270ms.

This brings performance of the two into the "so close it makes no difference" range.  But the key advantage of JRESP is that the code needs no modification to work like this, it automatically pipelines the commands in an efficient way.

### Conclusion

The biggest take away is that performance of JRESP is quite good.  In practice, if you need a Java Redis client then you wouldn't use JRESP because it's not a full client.  But it does mean it's a solid foundation for Redis clients in other JVM languages.

(I may add full client functionality to JRESP eventually, or maybe use it as the core for specific but distinct project.)

## TODO

1. Ensure that closed connections (i.e. server goes down) are known, and that appropriate signals are delivered upstream.
2. Document pub/sub facilities and connection pool more.
3. Handling of stopped connections pools, etc.
4. Redis clustering.
5. Create a full Java client (optional).
6. Tests regarding dropped connections.

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
