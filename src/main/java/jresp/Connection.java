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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * An individual connection
 */
public class Connection {
    private static final SimpleStr OK = new SimpleStr("OK");

    private static int serialNo = 1;

    final Integer id = serialNo++;

    /**
     * Connection details
     */
    private String hostname;
    private int port;

    private String password;
    private Integer db;

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
     * The outgoing buffer
     */
    private OutgoingBuffer outgoing = new OutgoingBuffer();

    /**
     * Decoder
     */
    private RespDecoder decoder = new RespDecoder();

    /**
     * Read buffer
     */
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(5285);  // 1460 - PACKET ESTIMATE

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

    /**
     * A private convenience function to send a command, only intended to be used to setup a connection, shouldn't
     * be used by applications.
     */
    private RespType sendSync(String... command) throws InterruptedException {
        List<RespType> resps = new ArrayList<>(1);
        CountDownLatch latch = new CountDownLatch(1);

        responses = resp -> {
            resps.add(resp);
            latch.countDown();
        };

        write(new Ary(Stream.of(command).map(BulkStr::new).collect(toList())));
        latch.await();

        responses = null;

        return resps.get(0);
    }

    /**
     * If either/or database or password is specified, ensure they are used when creating connections
     */
    private void loginAndSelect() throws ConnectionException {
        try {
            if (password != null) {
                RespType response = sendSync("AUTH", password);
                if (!response.equals(OK)) {
                    throw new ConnectionException("Invalid password: " + response);
                }
            }

            if (db != null) {
                RespType response = sendSync("SELECT", Integer.toString(db));
                if (!response.equals(OK)) {
                    throw new ConnectionException(String.format("Invalid database: %d (%s)", db, response));
                }
            }
        } catch (InterruptedException e) {
            throw new ConnectionException(e);
        }
    }

    public void start(Responses responses) throws IOException, ConnectionException {
        this.channel = SocketChannel.open(new InetSocketAddress(hostname, port));
        this.channel.configureBlocking(false);
        writeGroup.add(this);
        readGroup.add(this);

        loginAndSelect();

        this.responses = responses;
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

    public void write(RespType message) {
        if (shutdown) {
            throw new IllegalStateException("Connection has shutdown");
        }

        Deque<ByteBuffer> out = new ArrayDeque<>();

        message.writeBytes(out);
        outgoing.addAll(out);

        writeGroup.signal(this);
    }

    void writeTick() throws IOException {
        ByteBuffer buff = outgoing.pop();
        if (buff == null) {
            return;
        }

        channel.write(buff);

        if (buff.hasRemaining()) {
            // Data remaining, so putting at the front of the queue for the next time around
            outgoing.addFirst(buff);
        }

        if (!outgoing.isEmpty()) {
            writeGroup.signal(this);
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

    void setPassword(String password) {
        this.password = password;
    }

    void setDb(Integer db) {
        this.db = db;
    }
}

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
