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
