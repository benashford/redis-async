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

import jresp.protocol.ClientErr;
import jresp.protocol.RespType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * An individual connection
 */
public class Connection {
    private static final int BYTE_BUFFER_DEFAULT_SIZE = 16;

    private static int serialNo = 1;

    final Integer id = serialNo++;

    private String hostname;
    private int port;

    /**
     * The service threads
     */
    private ConnectionWriteGroup writeGroup;
    private ConnectionReadGroup readGroup;

    /**
     * The callback for incoming data
     */
    private Responses responses;

    /**
     * The socket channel
     */
    SocketChannel channel;

    /**
     * The buffer of out-going data
     */
    private final Object bufferLock = new Object();
    private ByteBuffer[] buffers = new ByteBuffer[BYTE_BUFFER_DEFAULT_SIZE];
    private int buffersStart = 0;
    private int buffersEnd = 0;

    /**
     * Decoder
     */
    private RespDecoder decoder = new RespDecoder();

    /**
     * Read buffer
     */
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(5285);  // 1460

    private boolean shutdown = false;

    /**
     * Constructor
     */
    Connection(String hostname,
               int port,
               ConnectionWriteGroup writeGroup,
               ConnectionReadGroup readGroup) {
        this.hostname = hostname;
        this.port = port;
        this.writeGroup = writeGroup;
        this.readGroup = readGroup;
    }

    void start(Responses responses) throws IOException {
        this.responses = responses;

        this.channel = SocketChannel.open(new InetSocketAddress(hostname, port));
        this.channel.configureBlocking(false);
        writeGroup.add(this);
        readGroup.add(this);
    }

    void shutdown() throws IOException {
        shutdown = true;

        channel.close();
    }

    public void stop() throws IOException {
        writeGroup.remove(this);
        readGroup.remove(this);

        shutdown();
    }

    private int bufferRemaining() {
        int right = buffersEnd;
        if (right < buffersStart) {
            right += buffers.length;
        }
        return buffers.length - (right - buffersStart);
    }

    private void addToBuffer(ByteBuffer out) {
        buffers[buffersEnd++] = out;
        if (buffersEnd == buffers.length) {
            buffersEnd = 0;
        }
    }

    private void addAndResize(ByteBuffer out) {
        synchronized (bufferLock) {
            int remaining = bufferRemaining();
            if (remaining == 1) {
                // Always leave a gap
                resizeBuffer();
            }
            addToBuffer(out);
        }
    }

    private void resizeBuffer() {
        int size = buffers.length * 2;

        ByteBuffer[] newBuffers = new ByteBuffer[size];
        int length;

        if (buffersEnd >= buffersStart) {
            length = buffersEnd - buffersStart;
        } else {
            length = buffers.length - buffersStart;
        }

        System.arraycopy(buffers, buffersStart, newBuffers, 0, length);

        if (buffersEnd < buffersStart) {
            System.arraycopy(buffers, 0, newBuffers, length, buffersEnd);
            length += buffersEnd;
        }

        buffers = newBuffers;
        buffersStart = 0;
        buffersEnd = length;
    }

    public void write(Collection<RespType> messages) {
        if (shutdown) {
            throw new IllegalStateException("Connection has shutdown");
        }

        Deque<ByteBuffer> out = new ArrayDeque<>();
        for (RespType message : messages) {
            message.writeBytes(out);
            int done = out.size() - 1;
            for (int i = 0; i < done; i++) {
                ByteBuffer bb = out.pop();
                bb.flip();
                addAndResize(bb);
            }
            if (done > 0) {
                writeGroup.signal(this);
            }
        }

        for (ByteBuffer bb : out) {
            bb.flip();
            addAndResize(bb);
        }

        writeGroup.signal(this);
    }

    void writeTick() throws IOException {
        synchronized (bufferLock) {
            int end;
            if (buffersEnd >= buffersStart) {
                end = buffersEnd;
            } else {
                end = buffers.length;
            }
            int length = end - buffersStart;
            long bytes = channel.write(buffers, buffersStart, length);
            //System.out.printf("Sent: %d%n", bytes);

            int i = buffersStart;
            while (i < end && !buffers[i].hasRemaining()) {
                buffers[i++] = null;
                buffersStart++;
                if (buffersStart >= buffers.length) {
                    buffersStart = 0;
                }
            }

            if (buffersStart != buffersEnd) {
                writeGroup.signal(this);
            }
        }
    }

    void readTick() {
        try {
            int bytes = channel.read(readBuffer);
            //System.out.println("Read: " + bytes);
            readBuffer.flip();
            List<RespType> out = new ArrayList<>();
            decoder.decode(readBuffer, out);
            out.forEach(responses::responseReceived);
        } catch (IOException e) {
            responses.responseReceived(new ClientErr(e));
        }
        readBuffer.clear();
    }

    void reportException(Exception e) {
        responses.responseReceived(new ClientErr(e));
    }
}
