package jresp.state;

import jresp.protocol.Err;
import jresp.protocol.RespType;

public class ErrState extends ScannableState {
    @Override
    public RespType finish() {
        return new Err(bufferAsString());
    }
}
