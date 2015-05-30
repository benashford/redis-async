package jresp.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class Ary implements RespType {
    private List<RespType> payload;

    public Ary(Collection<RespType> payload) {
        this.payload = new ArrayList<>(payload);
    }

    @Override
    public void writeBytes(ByteBuf out) {
        out.writeByte('*');
        out.writeBytes(Resp.longToByteArray(payload.size()));
        out.writeBytes(Resp.CRLF);
        payload.stream().forEach(x -> x.writeBytes(out));
    }

    @Override
    public Object unwrap() {
        return payload.stream().map(RespType::unwrap).collect(Collectors.toList());
    }
}
