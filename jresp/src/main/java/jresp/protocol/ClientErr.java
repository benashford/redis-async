package jresp.protocol;

import io.netty.buffer.ByteBuf;

public class ClientErr implements RespType {
    private Throwable error;

    public ClientErr(Throwable error) {
        this.error = error;
    }

    @Override
    public void writeBytes(ByteBuf out) {
        throw new UnsupportedOperationException("This cannot be written anywhere, this is only to be used within the client");
    }

    @Override
    public Object unwrap() {
        return error;
    }
}
