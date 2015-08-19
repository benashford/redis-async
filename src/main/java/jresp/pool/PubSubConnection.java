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
import jresp.protocol.Ary;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;

import java.io.IOException;
import java.util.*;

public class PubSubConnection {
    private static final BulkStr MESSAGE = new BulkStr("message");
    private static final BulkStr PMESSAGE = new BulkStr("pmessage");
    private static final BulkStr SUBSCRIBE = new BulkStr("subscribe");
    private static final BulkStr PSUBSCRIBE = new BulkStr("psubscribe");
    private static final BulkStr UNSUBSCRIBE = new BulkStr("unsubscribe");
    private static final BulkStr PUNSUBSCRIBE = new BulkStr("punsubscribe");

    private Connection connection;

    private Map<BulkStr, Responses> subscriptions = new HashMap<>();
    private Map<BulkStr, Responses> psubscriptions = new HashMap<>();

    PubSubConnection(Connection connection) throws IOException, ConnectionException {
        this.connection = connection;
        this.connection.start(this::incoming);
    }

    public void subscribe(String channel, Responses responses) throws PubSubException {
        BulkStr channelResp = new BulkStr(channel);
        synchronized (subscriptions) {
            if (subscriptions.containsKey(channelResp)) {
                throw new PubSubException("Already subscribed to: " + channel);
            } else {
                subscriptions.put(channelResp, responses);
            }
        }
        connection.write(new Ary(Arrays.asList(SUBSCRIBE, channelResp)));
    }

    public void psubscribe(String pattern, Responses responses) throws PubSubException {
        BulkStr patternResp = new BulkStr(pattern);
        synchronized (psubscriptions) {
            if (psubscriptions.containsKey(patternResp)) {
                throw new PubSubException("Already subscribed to: " + pattern);
            } else {
                psubscriptions.put(patternResp, responses);
            }
        }
        connection.write(new Ary(Arrays.asList(PSUBSCRIBE, patternResp)));
    }

    /**
     * Processes and routes an incoming message
     */
    public void incoming(RespType rawMessage) {
        List<RespType> message = ((Ary) rawMessage).raw();
        Iterator<RespType> i = message.iterator();
        RespType msgType = i.next();
        if (msgType.equals(MESSAGE)) {
            incomingMessage(i);
        } else if (msgType.equals(PMESSAGE)) {
            incomingPMessage(i);
        } else if (msgType.equals(SUBSCRIBE)) {
            incomingSubscribe(i);
        } else if (msgType.equals(PSUBSCRIBE)) {
            incomingPSubscribe(i);
        } else if (msgType.equals(UNSUBSCRIBE)) {
            incomingUnsubscribe(i);
        } else if (msgType.equals(PUNSUBSCRIBE)) {
            incomingPUnsubscribe(i);
        }
    }

    private void incomingPUnsubscribe(Iterator<RespType> i) {
        // Do nothing yet
    }

    private void incomingUnsubscribe(Iterator<RespType> i) {
        // Do nothing yet
    }

    private void incomingPSubscribe(Iterator<RespType> i) {
        // Do nothing yet
    }

    private void incomingSubscribe(Iterator<RespType> i) {
        // Do nothing yet
    }

    private void incomingPMessage(Iterator<RespType> i) {
        BulkStr pattern = (BulkStr)i.next();
        Responses responses = psubscriptions.get(pattern);
        BulkStr channel = (BulkStr)i.next();

        Ary ary = new Ary(Arrays.asList(channel, i.next()));
        responses.responseReceived(ary);
    }

    private void incomingMessage(Iterator<RespType> i) {
        BulkStr channel = (BulkStr)i.next();
        Responses responses = subscriptions.get(channel);
        responses.responseReceived(i.next());
    }
}
