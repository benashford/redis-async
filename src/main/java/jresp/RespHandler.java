package jresp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
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
        System.out.println("TODO: need actual error handling");
        //
        // TODO - a suitable message should be sent upstream to allow any connection
        // pooling to do the right thing
        //
        cause.printStackTrace();
        ctx.close();
    }
}
