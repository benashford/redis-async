package jresp.state;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;

public class BulkStrState implements State {
    private IntState intState = new IntState();
    private Integer stringLength = null;
    private ByteBuf buffer = Unpooled.directBuffer(1024);

    @Override
    public boolean decode(ByteBuf in) {
        if (stringLength == null) {
             if (intState.decode(in)) {
                 long len = intState.finishInt();
                 if (len < 0) {
                     stringLength = -1;
                 } else {
                     stringLength = (int)(len + 2); // To account for CRLF
                 }
             }
        }
        if (stringLength < 0) {
            return true;
        }
        if (stringLength != null) {
            int diff = stringLength - buffer.writerIndex();
            if (diff == 0) {
                return true;
            } else if (diff < 0) {
                throw new IllegalStateException("Got too much data");
            } else {
                int readable = Math.min(diff, in.readableBytes());
                in.readBytes(buffer, readable);
                return readable == diff;
            }
        }
        return false;
    }

    @Override
    public RespType finish() {
        if (stringLength < 0) {
            return new BulkStr();
        } else {
            int strLen = stringLength - 2; // To account for CRLF
            byte[] result = new byte[strLen];
            buffer.capacity(strLen);
            buffer.getBytes(0, result);
            buffer.release();
            return new BulkStr(result);
        }
    }
}
