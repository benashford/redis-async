package jresp.state;

import jresp.protocol.Int;
import jresp.protocol.RespType;

public class IntState extends ScannableState {
    @Override
    public RespType finish() {
        return new Int(finishInt());
    }

    long finishInt() {
        return Long.parseLong(bufferAsString());
    }
}
