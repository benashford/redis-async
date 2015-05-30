package jresp.protocol;

import io.netty.buffer.ByteBuf;

public class Int implements RespType {
    private long payload;

    public Int(long payload) {
        this.payload = payload;
    }

    @Override
    public void writeBytes(ByteBuf out) {
        out.writeByte(':');
        out.writeBytes(Resp.longToByteArray(payload));
        out.writeBytes(Resp.CRLF);
    }

    @Override
    public Object unwrap() {
        return payload;
    }
}
