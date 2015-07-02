package jresp.state;

import io.netty.buffer.ByteBuf;
import jresp.RespDecoder;
import jresp.protocol.Ary;
import jresp.protocol.RespType;

import java.util.ArrayList;
import java.util.List;

public class AryState implements State {
    private IntState intState = new IntState();
    private Integer aryLength = null;
    private List<RespType> ary = null;
    private State nextState = null;

    @Override
    public boolean decode(ByteBuf in) {
        if (aryLength == null) {
            if (intState.decode(in)) {
                aryLength = (int)intState.finishInt();
                ary = new ArrayList<>(aryLength);
                if (aryLength == 0) {
                    //
                    // This is an empty array, so there will be no "nextState", so let's short-cut proceedings here
                    //
                    return true;
                }
            }
        }
        if (in.readableBytes() == 0) {
            return false;
        }
        if (nextState == null) {
            nextState = RespDecoder.nextState((char) in.readByte());
        }
        if (nextState.decode(in)) {
            ary.add(nextState.finish());
            nextState = null;
            return ary.size() == aryLength;
        } else {
            return false;
        }
    }

    @Override
    public RespType finish() {
        return new Ary(ary);
    }
}
