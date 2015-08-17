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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;

public class BorrowedConnectionTest extends JRESPTest {
    private Pool pool;
    private SingleCommandConnection con;

    @Before
    public void setup() throws Exception {
        super.setup();

        pool = new Pool(client);
        con = pool.getBorrowed();
        con.write(flushDB(), NULL_RESPONSES);
    }

    @After
    public void teardown() throws Exception {
        pool.returnBorrowed(con);
        super.teardown();
    }

    @Test
    public void blpopTest() throws Exception {
        latch = new CountDownLatch(1);
        RespType[] responses = new RespType[1];
        con.write(blpop("BLPOPTESTKEY", 0), resp -> {
            responses[0] = resp;
            latch.countDown();
        });
        pool.getShared().write(rpush("BLPOPTESTKEY", "TESTVAL"), NULL_RESPONSES);
        await();

        assertEquals(Arrays.asList("BLPOPTESTKEY", "TESTVAL"), responses[0].unwrap());
    }
}
