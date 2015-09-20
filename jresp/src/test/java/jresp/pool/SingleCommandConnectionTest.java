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
import jresp.protocol.Ary;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

public class SingleCommandConnectionTest extends JRESPTest {
    private SingleCommandConnection con;

    private int runs = 1;

    @Before
    public void setup() throws Exception {
        super.setup();

        Pool pool = new Pool(client);
        con = pool.getShared();
    }

    @Test
    public void setTest() throws Exception {
        int n = 1_000_000;
        for (int i = 0; i < runs; i++) {
            long startTime = System.nanoTime();
            latch = new CountDownLatch(n);
            IntStream.range(0, n).forEach(x -> {
                RespType setAry = new Ary(BulkStr.get("SET"), new BulkStr("foo"), new BulkStr("bar"));

                con.write(setAry, resp -> latch.countDown());
            });
            System.out.printf("Finished writing: %.2f (recd: %d)%n", (System.nanoTime() - startTime) / 1000000.0, latch.getCount());
            await();
            System.out.printf("Took: %.2f%n", (System.nanoTime() - startTime) / 1000000.0);
        }
    }

    @Test
    public void setTest2() throws Exception {
        List<RespType> elements = new ArrayList<>(3);
        elements.add(new BulkStr("SET"));
        elements.add(new BulkStr("foo"));
        elements.add(new BulkStr("bar"));

        RespType setAry = new Ary(elements);

        int n = 1_000_000;
        for (int i = 0; i < runs; i++) {
            long startTime = System.nanoTime();
            latch = new CountDownLatch(n);
            IntStream.range(0, n).forEach(x -> con.write(setAry, resp -> latch.countDown()));
            System.out.printf("Finished writing: %.2f (recd: %d)%n", (System.nanoTime() - startTime) / 1000000.0, latch.getCount());
            await();
            System.out.printf("Took: %.2f%n", (System.nanoTime() - startTime) / 1000000.0);
        }
    }

    @Test
    public void echoTest() throws Exception {
        int n = 1000;
        List<String> responses = new ArrayList<>();
        latch = new CountDownLatch(n);
        IntStream.range(0, n).forEach(x -> {
            List<RespType> elements = new ArrayList<>();
            elements.add(new BulkStr("ECHO"));
            elements.add(new BulkStr(Integer.toString(x)));

            con.write(new Ary(elements), resp -> {
                responses.add((String)resp.unwrap());
                latch.countDown();
            });
        });
        await();
    }
}