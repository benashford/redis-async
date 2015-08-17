/*
 * Copyright 2015 Ben Ashford
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jresp.protocol;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Deque;

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
    public void writeBytes(Deque<ByteBuffer> out) {
        int size = 1;
        byte[] header;
        if (payload == null) {
            header = Resp.longToByteArray(-1);
        } else {
            header = Resp.longToByteArray(payload.length);
            size += 2 + payload.length;
        }
        size += 2 + header.length;

        ByteBuffer o = Resp.buffer(out, size);
        o.put((byte)'$');
        o.put(header);
        if (payload != null) {
            o.put(Resp.CRLF);
            o.put(payload);
        }
        o.put(Resp.CRLF);
    }

    public byte[] raw() {
        return payload;
    }

    @Override
    public Object unwrap() {
        if (payload == null) {
            return null;
        }
        try {
            return new String(payload, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(payload);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BulkStr) {
            BulkStr bs = (BulkStr)o;
            return Arrays.equals(payload, bs.payload);
        } else {
            return false;
        }
    }
}
