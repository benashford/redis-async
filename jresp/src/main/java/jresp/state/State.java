package jresp.state;

import io.netty.buffer.ByteBuf;
import jresp.protocol.RespType;

public interface State {
    boolean decode(ByteBuf in);
    RespType finish();
}
