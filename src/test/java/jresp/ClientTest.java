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

import jresp.protocol.RespType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ClientTest extends JRESPTest {

    private Connection con;

    private List<RespType> results = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        super.setup();

        con = client.makeConnection();
        con.start(result -> {
            results.add(result);
            latch.countDown();
        });
        latch = new CountDownLatch(1);
        con.write(flushDB());
        latch.await();
        results.clear();
    }

    @After
    public void teardown() throws Exception {
        super.teardown();
    }

    /**
     * Tests a single PING to a Redis server
     */
    @Test
    public void testPing() throws Exception {
        latch = new CountDownLatch(1);

        con.write(ping());

        await();
        RespType result = results.get(0);

        assertEquals("PONG", result.unwrap());
    }

    @Test
    public void testPings() throws Exception {
        latch = new CountDownLatch(2);

        con.write(ping());
        con.write(ping());

        await();

        RespType result1 = results.get(0);
        RespType result2 = results.get(1);

        assertEquals("PONG", result1.unwrap());
        assertEquals("PONG", result2.unwrap());
    }

    @Test
    public void thousandPingTest() throws Exception {
        int numPings = 1000;
        int runs = 1;

        for (int n = 0; n < runs; n++) {
            long start = System.nanoTime();
            latch = new CountDownLatch(numPings);

            IntStream.range(0, numPings).forEach(x -> con.write(ping()));

            await();
            System.out.printf("Done in %.2fms%n", (System.nanoTime() - start) / 1000000.0);

            results.forEach(result -> assertEquals("PONG", result.unwrap()));
        }
    }

    @Test
    public void benchmark() throws Exception {
        for (int c = 0; c < 1; c++) {
            int n = 1_000_000;
            latch = new CountDownLatch(n);

            long start = System.nanoTime();
            IntStream.range(0, n).forEach(x -> con.write(set("foo", "bar")));

            System.out.printf("Half way in: %.2fms%n", (System.nanoTime() - start) / 1000000.0);

            await();
            results.clear();
            System.out.printf("Done in: %.2fms%n", (System.nanoTime() - start) / 1000000.0);
        }
    }

    @Test
    public void getSetTest() throws Exception {
        int ops = 6;
        latch = new CountDownLatch(ops);

        con.write(set("A", "1"));
        con.write(set("B", "x"));
        con.write(set("C", "This is the third string"));
        con.write(get("A"));
        con.write(get("B"));
        con.write(get("C"));

        await();

        assertEquals("1", results.get(3).unwrap());
        assertEquals("x", results.get(4).unwrap());
        assertEquals("This is the third string", results.get(5).unwrap());
    }

    @Test
    public void arrayResponses() throws Exception {
        int ops = 4;
        latch = new CountDownLatch(ops);

        con.write(sadd("D", "1"));
        con.write(sadd("D", "2"));
        con.write(sadd("D", "3"));
        con.write(smembers("D"));

        await();

        assertEquals(Arrays.asList("1", "2", "3"), results.get(3).unwrap());
    }

    @Test
    public void nonExistantKeys() throws Exception {
        int ops = 1;
        latch = new CountDownLatch(ops);

        con.write(get("NO-SUCH-KEY"));

        await();

        assertNull(results.get(0).unwrap());
    }

    @Test
    public void emptyResults() throws Exception {
        int ops = 1;
        latch = new CountDownLatch(ops);

        con.write(hgetall("NO-SUCH-KEY"));

        await();

        assertEquals(Arrays.asList(), results.get(0).unwrap());
    }
}