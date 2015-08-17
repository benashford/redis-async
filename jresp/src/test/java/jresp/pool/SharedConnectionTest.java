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

package jresp.pool;

import jresp.JRESPTest;
import jresp.protocol.RespType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class SharedConnectionTest extends JRESPTest {
    private Pool pool;

    private SingleCommandConnection sharedConnection;

    @Before
    public void setup() throws Exception {
        super.setup();

        pool = new Pool(client);
        sharedConnection = pool.getShared();

        sharedConnection.write(flushDB(), NULL_RESPONSES);
    }

    @After
    public void teardown() throws Exception {
        super.teardown();
    }

    @Test
    public void testGetSet() throws Exception {
        latch = new CountDownLatch(1);
        String[] value = new String[1];

        sharedConnection.write(set("KEY-1", "VALUE-1"), NULL_RESPONSES);
        sharedConnection.write(get("KEY-1"), resp -> {
            value[0] = (String)resp.unwrap();
            latch.countDown();
        });

        await();

        assertEquals("VALUE-1", value[0]);
    }

    @Test
    public void thousandPingTest() throws Exception {
        int numPings = 1000;
        int runs = 1;

        for (int n = 0; n < runs; n++) {
            long start = System.nanoTime();
            latch = new CountDownLatch(numPings);

            List<RespType> results = new ArrayList<>(numPings);

            IntStream.range(0, numPings).forEach(x -> sharedConnection.write(ping(), resp -> {
                results.add(resp);
                latch.countDown();
            }));

            await();
            System.out.printf("Done in %.2fms%n", (System.nanoTime() - start) / 1000000.0);

            results.forEach(result -> assertEquals("PONG", result.unwrap()));
        }
    }
}