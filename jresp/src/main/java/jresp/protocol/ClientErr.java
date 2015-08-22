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

/**
 * Not part of the RESP spec.  Used internally within JRESP and Redis clients based on JRESP to send exceptions
 * to waiting functions.
 */
public class ClientErr implements RespType {
    private Throwable error;

    public ClientErr(Throwable error) {
        if (error == null) {
            throw new NullPointerException("error");
        } else {
            this.error = error;
        }
    }

    public String toString() {
        return String.format("%s[%s]", getClass().getName(), error.getMessage());
    }

    @Override
    public void writeBytes(Deque<ByteBuffer> out) {
        throw new UnsupportedOperationException("This cannot be written anywhere, this is only to be used within the client");
    }

    @Override
    public Object unwrap() {
        return error;
    }
}
