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
import java.util.Deque;

public class SimpleStr implements RespType {
    private String payload;

    public SimpleStr(String str) {
        payload = str;
    }

    @Override
    public void writeBytes(Deque<ByteBuffer> out) {
        try {
            byte[] bytes = payload.getBytes("UTF-8");
            int size = 1 + bytes.length + 2;
            ByteBuffer o = Resp.buffer(out, size);

            o.put((byte) '+');
            o.put(bytes);
            o.put(Resp.CRLF);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Object unwrap() {
        return payload;
    }

    @Override
    public int hashCode() {
        return payload.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof SimpleStr) {
            SimpleStr ss = (SimpleStr)o;
            return payload.equals(ss.payload);
        } else {
            return false;
        }
    }
}
