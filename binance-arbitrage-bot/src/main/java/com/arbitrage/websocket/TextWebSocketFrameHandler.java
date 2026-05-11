package com.arbitrage.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

public abstract class TextWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame msg) throws Exception {
        if (msg instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) msg);
        } else {
            throw new UnsupportedOperationException("Unsupported frame type: " + msg.getClass().getName());
        }
    }

    protected abstract void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception;
}