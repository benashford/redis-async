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

package jresp.util;

import jresp.Connection;

import java.util.HashSet;
import java.util.Set;

public class Signaller {
    private Set<Connection> connections = new HashSet<>();

    public synchronized void signal(Connection c) {
        connections.add(c);
        notifyAll();
    }

    public synchronized Set<Connection> reset() {
        while (true) {
            if (connections.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // harmless
                }
            } else {
                Set<Connection> cs = connections;
                connections = new HashSet<>();
                return cs;
            }
        }
    }
}
