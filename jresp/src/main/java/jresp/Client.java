package jresp;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * The owner of one-or-more connections.
 */
public class Client {
    private final String hostname;

    private final int port;

    private final EventLoopGroup workers;

    public Client(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;

        workers = new NioEventLoopGroup();
    }

    public Connection makeConnection(Responses responses) {
        Connection con = new Connection(hostname, port, workers);
        con.start(responses);
        return con;
    }

    public void stop() {
        workers.shutdownGracefully();
    }
}
