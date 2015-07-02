package jresp;

import jresp.protocol.Ary;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ClientTest {
    private Client client;
    private Connection con;

    private CountDownLatch latch;

    private List<RespType> results = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        client = new Client("localhost", 6379);
        con = client.makeConnection(result -> {
            results.add(result);
            latch.countDown();
        });
        latch = new CountDownLatch(1);
        con.write(Arrays.asList(flushDB()));
        latch.await();
        results.clear();
    }

    @After
    public void teardown() {
        client.stop();
    }

    private Ary command(String name, RespType... options) {
        List<RespType> elements = new ArrayList<>(options.length + 1);
        elements.add(new BulkStr(name));
        elements.addAll(Arrays.asList(options));

        return new Ary(elements);
    }

    private RespType ping() {
        return command("PING");
    }

    private RespType flushDB() {
        return command("FLUSHDB");
    }

    private RespType get(String key) {
        return command("GET", new BulkStr(key));
    }

    private RespType set(String key, String value) {
        return command("SET", new BulkStr(key), new BulkStr(value));
    }

    private RespType sadd(String key, String value) {
        return command("SADD", new BulkStr(key), new BulkStr(value));
    }

    private RespType smembers(String key) {
        return command("SMEMBERS", new BulkStr(key));
    }

    private RespType hgetall(String key) {
        return command("HGETALL", new BulkStr(key));
    }

    private void await() {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Tests a single PING to a Redis server
     */
    @Test
    public void testPing() throws Exception {
        latch = new CountDownLatch(1);

        con.write(Arrays.asList(ping()));

        await();
        RespType result = results.get(0);

        assertEquals("PONG", result.unwrap());
    }

    @Test
    public void testPings() throws Exception {
        latch = new CountDownLatch(2);

        con.write(Arrays.asList(ping(), ping()));

        await();

        RespType result1 = results.get(0);
        RespType result2 = results.get(1);

        assertEquals("PONG", result1.unwrap());
        assertEquals("PONG", result2.unwrap());
    }

    @Test
    public void thousandPingTest() throws Exception {
        int numPings = 1000;

        latch = new CountDownLatch(numPings);

        con.write(IntStream.range(0, numPings).mapToObj(x -> ping()).collect(Collectors.toList()));

        await();

        results.forEach(result -> assertEquals("PONG", result.unwrap()));
    }

    @Test
    public void getSetTest() throws Exception {
        int ops = 6;
        latch = new CountDownLatch(ops);

        con.write(Arrays.asList(set("A", "1"), set("B", "x"), set("C", "This is the third string")));
        con.write(Arrays.asList(get("A"), get("B"), get("C")));

        await();

        assertEquals("1", results.get(3).unwrap());
        assertEquals("x", results.get(4).unwrap());
        assertEquals("This is the third string", results.get(5).unwrap());
    }

    @Test
    public void arrayResponses() throws Exception {
        int ops = 4;
        latch = new CountDownLatch(ops);

        con.write(Arrays.asList(sadd("D", "1"), sadd("D", "2"), sadd("D", "3")));
        con.write(Arrays.asList(smembers("D")));

        await();

        assertEquals(Arrays.asList("1", "2", "3"), results.get(3).unwrap());
    }

    @Test
    public void nonExistantKeys() throws Exception {
        int ops = 1;
        latch = new CountDownLatch(ops);

        con.write(Arrays.asList(get("NO-SUCH-KEY")));

        await();

        assertNull(results.get(0).unwrap());
    }

    @Test
    public void emptyResults() throws Exception {
        int ops = 1;
        latch = new CountDownLatch(ops);

        con.write(Arrays.asList(hgetall("NO-SUCH-KEY")));

        await();

        assertEquals(Arrays.asList(), results.get(0).unwrap());
    }
}