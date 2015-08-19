/*
 * Copyright 2015 Ben Ashford
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jresp;

import jresp.protocol.Ary;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JRESPTest {
    protected static final Responses NULL_RESPONSES = response -> {
        // nothing
    };

    protected Client client;
    protected CountDownLatch latch;

    protected void setup() throws Exception {
        client = new Client("localhost", 6379);
        client.setDb(2);
    }

    protected void teardown() throws Exception {
        client.shutdown();
    }

    protected static Ary command(String name, RespType... options) {
        List<RespType> elements = new ArrayList<>(options.length + 1);
        elements.add(new BulkStr(name));
        elements.addAll(Arrays.asList(options));

        return new Ary(elements);
    }

    protected static RespType ping() {
        return command("PING");
    }

    protected static RespType flushDB() {
        return command("FLUSHDB");
    }

    protected static RespType get(String key) {
        return command("GET", new BulkStr(key));
    }

    protected static RespType set(String key, String value) {
        return command("SET", new BulkStr(key), new BulkStr(value));
    }

    protected static RespType sadd(String key, String value) {
        return command("SADD", new BulkStr(key), new BulkStr(value));
    }

    protected static RespType smembers(String key) {
        return command("SMEMBERS", new BulkStr(key));
    }

    protected static RespType hgetall(String key) {
        return command("HGETALL", new BulkStr(key));
    }

    protected static RespType blpop(String key, int timeout) {
        return command("BLPOP", new BulkStr(key), new BulkStr(Integer.toString(timeout)));
    }

    protected static RespType rpush(String key, String value) {
        return command("RPUSH", new BulkStr(key), new BulkStr(value));
    }

    protected static RespType publish(String channel, String payload) {
        return command("PUBLISH", new BulkStr(channel), new BulkStr(payload));
    }

    protected void await() {
        try {
            boolean success = latch.await(5, TimeUnit.SECONDS);
            if (!success) {
                throw new AssertionError("Timed out waiting");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
