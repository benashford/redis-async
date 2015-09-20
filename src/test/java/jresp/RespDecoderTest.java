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

import jresp.protocol.*;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class RespDecoderTest {
    private RespDecoder decoder;

    private static final Collection<ByteBuffer> encode(RespType respObj) {
        Deque<ByteBuffer> out = new ArrayDeque<>();
        respObj.writeBytes(out);

        out.forEach(x -> x.flip());

        return out;
    }

    private static final Collection<ByteBuffer> encode(Collection<RespType> objs) {
        Deque<ByteBuffer> out = new ArrayDeque<>();
        objs.forEach(obj -> obj.writeBytes(out));

        out.forEach(x -> x.flip());

        return out;
    }

    private final List<RespType> decode(Collection<ByteBuffer> in) {
        List<RespType> out = new ArrayList<>();
        for (ByteBuffer bb : in) {
            decoder.decode(bb, out::add);
        }
        return out;
    }

    @Before
    public void setup() {
        decoder = new RespDecoder();
    }

    @Test
    public void testBulkStr() throws Exception {
        BulkStr bs = new BulkStr("TESTING");
        Collection<ByteBuffer> encoded = encode(bs);

        List<RespType> out = decode(encoded);
        assertEquals("TESTING", out.get(0).unwrap());
    }

    @Test
    public void testErr() throws Exception {
        Err err = new Err("TESTING");
        Collection<ByteBuffer> encoded = encode(err);

        List<RespType> out = decode(encoded);
        assertEquals("TESTING", out.get(0).unwrap());
    }

    @Test
    public void testInt() throws Exception {
        Int i = new Int(999);
        Collection<ByteBuffer> encoded = encode(i);

        List<RespType> out = decode(encoded);
        assertEquals(999L, out.get(0).unwrap());
    }

    @Test
    public void testSimpleString() throws Exception {
        SimpleStr ss = new SimpleStr("TESTING");
        Collection<ByteBuffer> encoded = encode(ss);

        List<RespType> out = decode(encoded);
        assertEquals("TESTING", out.get(0).unwrap());
    }

    @Test
    public void testAry() throws Exception {
        List<RespType> stuff = new ArrayList<>();
        stuff.add(new SimpleStr("TESTING"));
        stuff.add(new Int(4321));
        stuff.add(new BulkStr("This is a whole list of words"));
        Ary ary = new Ary(stuff);
        Collection<ByteBuffer> encoded = encode(ary);

        List<RespType> out = decode(encoded);

        List<Object> expected = new ArrayList<>();
        expected.add("TESTING");
        expected.add(4321L);
        expected.add("This is a whole list of words");

        assertEquals(expected, out.get(0).unwrap());
    }

    @Test
    public void millionOks() throws Exception {
        List<RespType> oks = IntStream.range(0, 1_000_000).mapToObj(x -> new SimpleStr("OK")).collect(Collectors.toList());
        for (int n = 0; n < 1; n++) {
            Collection<ByteBuffer> encoded = encode(oks);

            long startTime = System.nanoTime();
            List<RespType> out = decode(encoded);
            assertEquals(1_000_000, out.size());
            System.out.printf("Elapsed: %.2fms%n", (System.nanoTime() - startTime) / 1_000_000.0);
        }
    }
}