package jresp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jresp.protocol.ClientErr;
import jresp.protocol.RespType;

public class RespHandler extends SimpleChannelInboundHandler<RespType> {
    private Responses responses;

    RespHandler(Responses responses) {
        this.responses = responses;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespType msg) throws Exception {
        responses.responseReceived(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        responses.responseReceived(new ClientErr(cause));
        ctx.close();
    }
}
