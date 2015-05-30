package jresp.state;

import jresp.protocol.RespType;
import jresp.protocol.SimpleStr;

public class SimpleStrState extends ScannableState {
    @Override
    public RespType finish() {
        return new SimpleStr(bufferAsString());
    }
}
