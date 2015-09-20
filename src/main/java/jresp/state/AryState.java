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
import jresp.protocol.Ary;
import jresp.protocol.RespType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AryState implements State {
    private RespDecoder parent;

    private IntState intState;
    private Integer aryLength = null;
    private List<RespType> ary = null;
    private State nextState = null;

    public AryState(RespDecoder parent) {
        this.parent = parent;
        this.intState = parent.intDecoder();
    }

    @Override
    public boolean decode(ByteBuffer in) {
        while (true) {
            if (aryLength == null) {
                if (intState.decode(in)) {
                    aryLength = (int) intState.finishInt();
                    ary = new ArrayList<>(aryLength);
                    if (aryLength == 0) {
                        //
                        // This is an empty array, so there will be no "nextState", so let's short-cut proceedings here
                        //
                        return true;
                    }
                }
            }
            if (in.remaining() == 0) {
                return false;
            }
            if (nextState == null) {
                nextState = parent.nextState((char) in.get());
            }
            if (nextState.decode(in)) {
                ary.add(nextState.finish());
                if (ary.size() == aryLength) {
                    return true;
                } else {
                    nextState = null;
                    // Lets go around again, there's (probably more to do).
                }
            } else {
                return false;
            }
        }
    }

    @Override
    public RespType finish() {
        return new Ary(ary);
    }
}
