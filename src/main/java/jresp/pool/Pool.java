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

import jresp.Client;
import jresp.Connection;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A connection-pool for JRESP connections.  Since JRESP is asynchronous, most operations can be multiplexed onto one
 * single shared connection.  However there are some Redis commands that are exceptions to this rule.
 */
public class Pool {
    private Client client;

    private SingleCommandConnection shared;

    private PubSubConnection pubSub;

    private Set<SingleCommandConnection> borrowable = new HashSet<>();
    private Set<SingleCommandConnection> borrowed = new HashSet<>();

    public Pool(Client client) {
        this.client = client;
    }

    /**
     * The shared connection is for the majority of Redis commands that return one single response.  JRESP will
     * automatically pipeline such commands for efficiency.
     *
     * Do not use such a connection for any blocking, pub-sub, or any other command that doesn't return one single
     * response.
     *
     * Because this is shared, the connection will be started.
     */
    public synchronized SingleCommandConnection getShared() throws IOException {
        if (shared == null) {
            shared = new SingleCommandConnection(client.makeConnection());
        }

        return shared;
    }

    /**
     * A dedicated connection is one that is not shared, and not reused.  It is used for commands that permanently
     * change the nature of the connection - e.g. MONITOR.
     *
     * The returned connection is not started.  The caller must start it as appropriate.
     */
    public Connection getDedicated() throws IOException {
        return client.makeConnection();
    }

    /**
     * A borrowed connection is one that a borrower has exclusive use of until it is returned.  The borrower must
     * return it to avoid any leaks.  It is used mainly for blocking commands like BLPOP.
     */
    public synchronized SingleCommandConnection getBorrowed() throws IOException {
        if (borrowable.isEmpty()) {
            borrowable.add(new SingleCommandConnection(client.makeConnection()));
        }

        Iterator<SingleCommandConnection> i = borrowable.iterator();
        SingleCommandConnection con = i.next();
        i.remove();

        borrowed.add(con);

        return con;
    }

    /**
     * Return a borrowed connection
     */
    public synchronized void returnBorrowed(SingleCommandConnection con) {
        if (borrowed.remove(con)) {
            borrowable.add(con);
        } else {
            throw new IllegalStateException("This connection was not previously borrowed");
        }
    }

    /**
     * Return a shared pub-sub channel.  A Redis connection that is subscribed to a channel cannot be used for other
     * purposes
     */
    public synchronized PubSubConnection getPubSubChannel() throws IOException {
        if (pubSub == null) {
            pubSub = new PubSubConnection(client.makeConnection());
        }
        return pubSub;
    }
}
