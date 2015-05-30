package jresp.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Defines the five types implemented by the RESP protocol
 */
public interface RespType {
    /**
     * Write the RESP form to the ByteBuf
     */
    void writeBytes(ByteBuf out);

    /**
     * Return the high-level Java equivalent.
     */
    Object unwrap();
}
