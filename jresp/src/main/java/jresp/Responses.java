package jresp;

import jresp.protocol.RespType;

public interface Responses {
    void responseReceived(RespType response);
}
