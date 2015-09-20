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

package jresp.protocol;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Deque;

public class Resp {
    public static final byte[] CRLF;

    private static final int CACHED_LONGS = 32;

    public static final byte[][] LONGS = new byte[CACHED_LONGS][];
    private static final byte[] MINUS_ONE = l2ba(-1);

    static {
        try {
            CRLF = "\r\n".getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }

        for (int i = 0; i < CACHED_LONGS; i++) {
            LONGS[i] = l2ba(i);
        }
    }

    private static byte[] l2ba(long val) {
        try {
            return Long.toString(val).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] longToByteArray(long val) {
        if (val == -1) {
            return MINUS_ONE;
        } else if (val < CACHED_LONGS) {
            return LONGS[(int)val];
        } else {
            return l2ba(val);
        }
    }

    /**
     * Pick an existing, or create a new ByteBuffer
     */
    static ByteBuffer buffer(Deque<ByteBuffer> buffers, int size) {
        if (!buffers.isEmpty()) {
            ByteBuffer last = buffers.peekLast();
            if (last.remaining() >= size) {
                return last;
            }
        }
        ByteBuffer newBuffer = ByteBuffer.allocate(Math.max(1460, size));
        buffers.add(newBuffer);
        return newBuffer;
    }
}
