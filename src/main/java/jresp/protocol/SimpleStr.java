package jresp.protocol;

import io.netty.buffer.ByteBuf;

import java.io.UnsupportedEncodingException;

public class SimpleStr implements RespType {
    private String payload;

    public SimpleStr(String str) {
        payload = str;
    }

    @Override
    public void writeBytes(ByteBuf out) {
        try {
            out.writeByte('+');
            out.writeBytes(payload.getBytes("UTF-8"));
            out.writeBytes(Resp.CRLF);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Object unwrap() {
        return payload;
    }
}
