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
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
     * The service thread
     */
    private ConnectionGroup group;

    /**
     * The callback for incoming data
     */
    private Responses responses;

    /**
     * The socket channel
     */
    SocketChannel channel;
    private SelectionKey selectionKey;

    /**
     * The outgoing buffer
     */
    private final OutgoingBuffer outgoing = new OutgoingBuffer();

    /**
     * Decoder
     */
    private RespDecoder decoder = new RespDecoder();

    /**
     * Read buffer
     */
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(5285);  // 1460 - PACKET ESTIMATE

    /**
     * Has this been shutdown
     */
    private boolean shutdown = false;

    /**
     * Constructor
     */
    Connection(String hostname,
               int port,
               ConnectionGroup group) {
        this.hostname = hostname;
        this.port = port;
        this.group = group;
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

        this.selectionKey = group.add(this);

        loginAndSelect();

        this.responses = responses;
    }

    void shutdown() throws IOException {
        shutdown = true;

        if (responses != null) {
            // Tell anything waiting that there will be no more data
            responses.responseReceived(new EndOfResponses());
        }

        channel.close();
    }

    public void stop() throws IOException {
        if (!shutdown) {
            group.remove(this);

            shutdown();
        }
    }

    private void writeInterest(boolean on) {
        int interestOps = selectionKey.interestOps();
        boolean isOn = (interestOps & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE;

        if (isOn && on) {
            return;
        } else if (isOn) {
            selectionKey.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            selectionKey.selector().wakeup();
        } else if (on) {
            selectionKey.interestOps(interestOps | SelectionKey.OP_WRITE);
            selectionKey.selector().wakeup();
        } else {
            return;
        }
    }

    public void write(RespType message) {
        if (shutdown) {
            throw new IllegalStateException("Connection has shutdown");
        }

        Deque<ByteBuffer> out = new ArrayDeque<>();

        message.writeBytes(out);

        synchronized (outgoing) {
            outgoing.addAll(out);
            writeInterest(true);
        }
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

        synchronized (outgoing) {
            if (outgoing.isEmpty()) {
                writeInterest(false);
            }
        }
    }

    void readTick() throws IOException {
        try {
            int bytes = channel.read(readBuffer);
            if (bytes < 0) {
                // This socket is closed, there will be no more data
                shutdown();
            } else {
                readBuffer.flip();
                List<RespType> out = new ArrayList<>();
                decoder.decode(readBuffer, out);
                out.forEach(responses::responseReceived);
            }
        } finally {
            readBuffer.clear();
        }
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

    public boolean isShutdown() {
        return shutdown;
    }
}

