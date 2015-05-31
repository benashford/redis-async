package jresp.protocol;

import io.netty.buffer.ByteBuf;

import java.io.UnsupportedEncodingException;

public class BulkStr implements RespType {
    private byte[] payload;

    public BulkStr(String s) {
        try {
            payload = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public BulkStr(byte[] s) {
        payload = s;
    }

    public BulkStr() {
        this.payload = null;
    }

    @Override
    public void writeBytes(ByteBuf out) {
        out.writeByte('$');
        if (payload == null) {
            out.writeBytes(Resp.longToByteArray(-1));
        } else {
            out.writeBytes(Resp.longToByteArray(payload.length));
            out.writeBytes(Resp.CRLF);
            out.writeBytes(payload);
        }
        out.writeBytes(Resp.CRLF);
    }

    @Override
    public Object unwrap() {
        try {
            return new String(payload, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
