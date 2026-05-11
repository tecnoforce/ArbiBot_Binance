package com.arbitrage.websocket;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.NetworkEndpoints;
import com.arbitrage.util.Log;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.channel.ChannelFuture;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NettyWebSocketClient {
    private static final String TAG = "NettyWS";

    private final ApiConfig apiConfig;
    private final PriceUpdateHandler priceHandler;

    private EventLoopGroup group;
    private Channel channel;
    private final ConcurrentLinkedQueue<String> subscriptions = new ConcurrentLinkedQueue<>();
    private volatile boolean connected = false;

    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static int messageCount = 0;

    // Use virtual threads for I/O-bound reconnection tasks
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public NettyWebSocketClient(ApiConfig apiConfig, PriceUpdateHandler priceHandler) {
        this.apiConfig = apiConfig;
        this.priceHandler = priceHandler;
    }

    public void connectAndSubscribe(Iterable<String> symbols) {
        StringBuilder streamParams = new StringBuilder();

        for (String symbol : symbols) {
            if (streamParams.length() > 0) {
                streamParams.append("/");
            }
            streamParams.append(symbol.toLowerCase()).append("@bookTicker");
            subscriptions.add(symbol.toUpperCase());
        }

        String streams = streamParams.toString();
        boolean isTestnet = apiConfig.isTestnet();
        String wsUrl = NetworkEndpoints.buildWebSocketUrl(isTestnet, streams);

        Log.info("Conectando a Netty WebSocket: Binance...");

        try {
            startConnection(wsUrl);
        } catch (Exception e) {
            Log.error(TAG, "Error al conectar: " + e.getMessage());
            scheduleReconnect(symbols);
        }
    }

    private void startConnection(String url) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        boolean useSsl = "wss".equals(scheme);

        int port = uri.getPort();
        if (port == -1) {
            port = useSsl ? 443 : 80;
        }

        group = new NioEventLoopGroup(1);

        final WebSocketClientHandler handler;
        if (useSsl) {
            SslContext sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            handler = new WebSocketClientHandler(
                    WebSocketClientHandshakerFactory.newHandshaker(
                            uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()),
                    priceHandler, () -> handleDisconnect(subscriptions));
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(sslCtx.newHandler(ch.alloc()));
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            p.addLast(new WebSocket08FrameEncoder(true));
                            p.addLast(handler);
                        }
                    });
            channel = bootstrap.connect(uri.getHost(), port).sync().channel();
        } else {
            handler = new WebSocketClientHandler(
                    WebSocketClientHandshakerFactory.newHandshaker(
                            uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()),
                    priceHandler, () -> handleDisconnect(subscriptions));
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            p.addLast(new WebSocket08FrameEncoder(true));
                            p.addLast(handler);
                        }
                    });
            channel = bootstrap.connect(uri.getHost(), port).sync().channel();
        }

        handler.handshakeFuture().sync();
    }

    private void handleDisconnect(Iterable<String> symbols) {
        connected = false;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.error(TAG, "Max intentos de reconexion alcanzados");
            return;
        }
        scheduleReconnect(symbols);
    }

    private void scheduleReconnect(Iterable<String> symbols) {
        long delay = Math.min(500L * (1L << reconnectAttempts), 4000L);
        reconnectAttempts++;

        Log.warn(TAG, "Reintentando conexion (intento " + reconnectAttempts + ") en " + delay + "ms...");

        executor.submit(() -> {
            try {
                Thread.sleep(delay);
                connectAndSubscribe(symbols);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public boolean isConnected() {
        return connected;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void resetMessageCount() {
        messageCount = 0;
    }

    public void close() {
        connected = false;
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        executor.shutdown();
    }

    private static class WebSocketClientHandler extends TextWebSocketFrameHandler {
        private final WebSocketClientHandshaker handshaker;
        private final PriceUpdateHandler priceHandler;
        private final Runnable onDisconnect;
        private ChannelHandlerContext ctx;
        private ChannelFuture handshakeFuture;

        public WebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                      PriceUpdateHandler priceHandler,
                                      Runnable onDisconnect) {
            this.handshaker = handshaker;
            this.priceHandler = priceHandler;
            this.onDisconnect = onDisconnect;
        }

        public ChannelFuture handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshakeFuture = handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            onDisconnect.run();
        }

        @Override
        protected void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
            messageCount++;
            ByteBuf buf = frame.content();
            priceHandler.handlePriceUpdate(buf);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.error(TAG, "Excepcion: " + cause.getMessage());
            ctx.close();
        }
    }
}