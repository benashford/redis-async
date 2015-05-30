package jresp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import jresp.state.*;

import java.util.List;

public class RespDecoder extends ByteToMessageDecoder {

    private State state = null;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (true) {
            int availableBytes = in.readableBytes();
            if (availableBytes == 0) {
                //
                // We need more bytes
                //
                return;
            }

            if (state == null) {
                //
                // There is no current state, so read the next byte
                //
                char nextChar = (char) in.readByte();
                state = nextState(nextChar);
            }
            if (state.decode(in)) {
                out.add(state.finish());
                state = null;
            } else {
                //
                // We need more bytes
                //
                return;
            }
        }
    }

    public static State nextState(char token) {
        switch (token) {
            case '+':
                return new SimpleStrState();
            case '-':
                return new ErrState();
            case ':':
                return new IntState();
            case '$':
                return new BulkStrState();
            case '*':
                return new AryState();
            default:
                throw new IllegalStateException(String.format("Unknown token %s", token));
        }
    }
}

