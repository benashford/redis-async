package jresp.protocol;

import java.io.UnsupportedEncodingException;

public class Resp {
    public static final byte[] CRLF;

    static {
        try {
            CRLF = "\r\n".getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] longToByteArray(long val) {
        try {
            return Long.toString(val).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
