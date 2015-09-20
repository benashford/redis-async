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

package jresp;

import jresp.protocol.RespType;
import jresp.state.*;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class RespDecoder {

    private SimpleStrState simpleStrDecoder = new SimpleStrState();
    private ErrState errDecoder = new ErrState();
    private IntState intDecoder = new IntState();
    private BulkStrState bulkStrDecoder;

    private State state = null;

    RespDecoder() {
        bulkStrDecoder = new BulkStrState(this);
    }

    protected void decode(ByteBuffer in, Consumer<RespType> out) {
        while (true) {
            int availableBytes = in.remaining();
            if (availableBytes == 0) {
                //
                // We need more bytes
                //
                break;
            }

            if (state == null) {
                //
                // There is no current state, so read the next byte
                //
                char nextChar = (char) in.get();
                state = nextState(nextChar);
            }
            if (state.decode(in)) {
                out.accept(state.finish());
                state = null;
            } else {
                //
                // We need more bytes
                //
                break;
            }
        }
    }

    public State nextState(char token) {
        switch (token) {
            case '+':
                return simpleStrDecoder.reset();
            case '-':
                return errDecoder.reset();
            case ':':
                return intDecoder.reset();
            case '$':
                return bulkStrDecoder.reset();
            case '*':
                return new AryState(this);
            default:
                throw new IllegalStateException(String.format("Unknown token %s", token));
        }
    }

    public IntState intDecoder() {
        return (IntState)intDecoder.reset();
    }
}

