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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

class OutgoingBuffer {
    private static final int MAX_MERGED_BUFFER_SIZE = 1460;

    private Deque<ByteBuffer> buffer = new ArrayDeque<>();

    /**
     * All ByteBuffers must be in write mode at this point, they'll get flipped by this buffer
     */
    synchronized void addAll(Collection<ByteBuffer> col) {
        if (buffer.isEmpty() && col.size() == 1) {
            ByteBuffer bb = col.iterator().next();
            if (bb.position() <= MAX_MERGED_BUFFER_SIZE) {
                bb.flip();
                buffer.add(bb);
                return;
            }
        } else {
            ByteBuffer tmp = null;
            if (!buffer.isEmpty()) {
                ByteBuffer last = buffer.removeLast();
                if (last.remaining() <= MAX_MERGED_BUFFER_SIZE) {
                    tmp = ByteBuffer.allocate(MAX_MERGED_BUFFER_SIZE);
                    tmp.put(last);
                }
            }
            for (ByteBuffer bb : col) {
                if ((tmp == null) && (bb.position() <= MAX_MERGED_BUFFER_SIZE)) {
                    tmp = bb;
                } else {
                    bb.flip(); // put in read mode
                    while (bb.hasRemaining()) {
                        if (tmp == null) {
                            tmp = ByteBuffer.allocate(MAX_MERGED_BUFFER_SIZE);
                        }
                        int tmpRemaining = tmp.remaining();
                        if (tmpRemaining == 0) {
                            tmp.flip();
                            buffer.add(tmp);
                            tmp = null;
                        } else if (bb.remaining() <= tmpRemaining) {
                            tmp.put(bb);
                        } else {
                            byte[] buffer = new byte[tmpRemaining];
                            bb.get(buffer);
                            tmp.put(buffer);
                        }
                    }
                }
            }
            if (tmp != null) {
                tmp.flip();
                buffer.add(tmp);
            }
        }
    }

    public synchronized boolean isEmpty() {
        return buffer.isEmpty();
    }

    public synchronized void addFirst(ByteBuffer bb) {
        buffer.addFirst(bb);
    }

    public synchronized ByteBuffer pop() {
        if (buffer.isEmpty()) {
            return null;
        } else {
            return buffer.pop();
        }
    }
}
