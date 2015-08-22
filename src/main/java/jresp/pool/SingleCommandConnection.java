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

import jresp.Connection;
import jresp.ConnectionException;
import jresp.Responses;
import jresp.protocol.ClientErr;
import jresp.protocol.EndOfResponses;
import jresp.protocol.RespType;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A connection used solely for commands that have a single response to each request
 */
public class SingleCommandConnection {
    private Connection connection;

    private final Deque<Responses> responseQueue = new ArrayDeque<>();

    public SingleCommandConnection(Connection connection) throws IOException, ConnectionException {
        this.connection = connection;
        this.connection.start(this::dispatcher);
    }

    private void dispatcher(RespType resp) {
        if (resp instanceof EndOfResponses) {
            synchronized (responseQueue) {
                responseQueue.forEach(responder -> responder.responseReceived(resp));
            }
        } else {
            Responses respondTo = null;
            synchronized (responseQueue) {
                if (responseQueue.isEmpty()) {
                    if (resp instanceof ClientErr) {
                        // There are no waiting responses, so nowhere to send the response to.
                    } else {
                        // There is a response but nowhere to send it to.
                        throw new IllegalStateException("Got an unexpected response: " + resp);
                    }
                } else {
                    respondTo = responseQueue.pop();
                }
            }
            if (respondTo != null) {
                respondTo.responseReceived(resp);
            }
        }
    }

    public void write(RespType command, Responses responses) {
        synchronized (responseQueue) {
            responseQueue.add(responses);
            connection.write(command);
        }
    }

    public boolean isShutdown() {
        return connection.isShutdown();
    }
}
