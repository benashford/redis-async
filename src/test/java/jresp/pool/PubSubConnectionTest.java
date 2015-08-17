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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;

public class PubSubConnectionTest extends JRESPTest {
    private Pool pool;
    private PubSubConnection con;

    @Before
    public void setup() throws Exception {
        super.setup();

        pool = new Pool(client);
        latch = new CountDownLatch(1);
        SingleCommandConnection sharedConnection = pool.getShared();

        sharedConnection.write(flushDB(), resp -> latch.countDown());
        await();

        con = pool.getPubSubChannel();
    }

    @After
    public void teardown() throws Exception {
        super.teardown();
    }

    @Test
    public void pubSubTest() throws Exception {
        List<String> responses = new ArrayList<>();
        latch = new CountDownLatch(3);

        con.subscribe("TEST-CHANNEL", resp -> {
            responses.add((String) resp.unwrap());
            latch.countDown();
        });

        SingleCommandConnection sharedConnection = pool.getShared();
        sharedConnection.write(publish("TEST-CHANNEL", "ahoy"), NULL_RESPONSES);
        sharedConnection.write(publish("TEST-CHANNEL", "slightly longer string"), NULL_RESPONSES);
        sharedConnection.write(publish("TEST-CHANNEL", "1"), NULL_RESPONSES);

        await();

        assertEquals(Arrays.asList("ahoy", "slightly longer string", "1"), responses);
    }
}