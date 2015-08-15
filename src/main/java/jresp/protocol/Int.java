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

import java.nio.ByteBuffer;
import java.util.Deque;

public class Int implements RespType {
    private long payload;

    public Int(long payload) {
        this.payload = payload;
    }

    @Override
    public void writeBytes(Deque<ByteBuffer> out) {
        byte[] bytes = Resp.longToByteArray(payload);
        int size = 1 + bytes.length + 2;
        ByteBuffer o = Resp.buffer(out, size);
        o.put((byte) ':');
        o.put(bytes);
        o.put(Resp.CRLF);
    }

    @Override
    public Object unwrap() {
        return payload;
    }
}
