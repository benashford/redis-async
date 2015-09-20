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

package jresp.state;

import jresp.RespDecoder;
import jresp.protocol.BulkStr;
import jresp.protocol.RespType;

import java.nio.ByteBuffer;

public class BulkStrState implements State {
    private static final int DEFAULT_SIZE = 32;
    private static final int MAXIMUM_SIZE = DEFAULT_SIZE * 1000;

    private RespDecoder parent;
    private IntState intState;
    private Integer stringLength = null;
    private byte[] buffer = null;
    private int idx = 0;

    public BulkStrState(RespDecoder parent) {
        this.parent = parent;
        this.intState = parent.intDecoder();
    }

    public BulkStrState reset() {
        intState.reset();
        stringLength = null;
        idx = 0;

        if (buffer != null && buffer.length > MAXIMUM_SIZE) {
            buffer = null;
        }

        return this;
    }

    @Override
    public boolean decode(ByteBuffer in) {
        if (stringLength == null) {
             if (intState.decode(in)) {
                 long len = intState.finishInt();
                 if (len < 0) {
                     stringLength = -1;
                 } else {
                     stringLength = (int)(len + 2); // To account for CRLF
                 }
             } else {
                 return false;
             }
        }
        if (stringLength < 0) {
            return true;
        } else {
            if (buffer == null || buffer.length < stringLength) {
                buffer = new byte[stringLength];
            }
            int diff = stringLength - idx;
            if (diff == 0) {
                return true;
            } else if (diff < 0) {
                throw new IllegalStateException("Got too much data");
            } else {
                int readable = Math.min(diff, in.remaining());
                in.get(buffer, idx, readable);
                idx += readable;
                return readable == diff;
            }
        }
    }

    @Override
    public RespType finish() {
        if (stringLength < 0) {
            return new BulkStr();
        } else {
            int strLen = stringLength - 2; // To account for CRLF
            byte[] result = new byte[strLen];
            System.arraycopy(buffer, 0, result, 0, strLen);
            return new BulkStr(result);
        }
    }
}
