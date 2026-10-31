package io.ominicat.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.ominicat.core.Frame;
import io.ominicat.core.FrameEnvelope;
import io.ominicat.core.EnvelopeProcessor;
import io.ominicat.core.Pipeline;
import io.ominicat.core.PipelineSession;
import io.ominicat.core.Processor;
import io.ominicat.core.SessionOptions;
import io.ominicat.core.spi.PluginRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Embedded, configurable WebSocket container. Netty owns socket I/O; each accepted session
 * owns its own microkernel pipeline and virtual worker. Instances are single-use.
 */
public final class OminiCatServer implements AutoCloseable {
    private final RuntimeConfig config;
    private final PluginRegistry plugins;
    private final byte[] token;
    private final FrameCodec codec = new FrameCodec();
    private final NioEventLoopGroup acceptors;
    private final NioEventLoopGroup ioWorkers;
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final LongAdder sessionsTotal = new LongAdder();
    private final LongAdder framesIn = new LongAdder();
    private final LongAdder framesOut = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private volatile Channel listener;

    public OminiCatServer(RuntimeConfig config, PluginRegistry plugins) {
        this.config = Objects.requireNonNull(config, "config");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        for (RuntimeConfig.Stage stage : config.stages()) {
            if (!plugins.types().contains(stage.type())) {
                throw new IllegalArgumentException("Unknown processor type '" + stage.type() + "'; installed: " + plugins.types());
            }
        }
        if (config.tokenEnv() == null) {
            token = null;
        } else {
            String secret = System.getenv(config.tokenEnv());
            if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Required bearer-token environment variable is missing: " + config.tokenEnv());
            token = secret.getBytes(StandardCharsets.UTF_8);
        }
        acceptors = new NioEventLoopGroup(1);
        ioWorkers = new NioEventLoopGroup(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())));
    }

    /** Binds the listener. Throws on invalid configuration or bind failure. */
    public OminiCatServer start() throws InterruptedException {
        if (closed.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("Server is single-use");
        try {
            preflight();
            listener = new ServerBootstrap().group(acceptors, ioWorkers).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(2 * config.maxFrameBytes(), 4 * config.maxFrameBytes()))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            connections.add(channel);
                            channel.pipeline().addLast(new ReadTimeoutHandler(config.idleTimeoutSeconds()));
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast(new HttpObjectAggregator(16_384));
                            channel.pipeline().addLast(new HttpHandler());
                        }
                    }).bind(config.host(), config.port()).sync().channel();
            return this;
        } catch (InterruptedException failure) {
            close();
            throw failure;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        } catch (Throwable failure) {
            close();
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Unable to start OminiCat listener", failure);
        }
    }

    public int port() {
        Channel channel = listener;
        if (channel == null) throw new IllegalStateException("Server has not started");
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** Waits until the listening channel closes. */
    public void awaitTermination() throws InterruptedException {
        Channel channel = listener;
        if (channel == null) throw new IllegalStateException("Server has not started");
        channel.closeFuture().sync();
    }

    /** Closes sockets, cancels session workers, then releases event loops. Safe to repeat. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Channel channel = listener;
        if (channel != null) channel.close().awaitUninterruptibly();
        connections.close().awaitUninterruptibly();
        acceptors.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
        ioWorkers.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private PipelineSession session(io.ominicat.core.FrameSink output) {
        List<Processor> processors = new ArrayList<>();
        try {
            var builder = Pipeline.builder();
            for (RuntimeConfig.Stage stage : config.stages()) {
                Processor processor = plugins.create(stage.type(), stage.options());
                processors.add(processor);
                builder.add(EnvelopeProcessor.wrap(processor));
            }
            return builder.build().openSession(new SessionOptions(config.queueCapacity()), output);
        } catch (RuntimeException failure) {
            for (Processor processor : processors.reversed()) {
                try { processor.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private void preflight() {
        List<Processor> instances = new ArrayList<>();
        IllegalArgumentException rejectedConfiguration = null;
        int stageIndex = 0;
        try {
            for (RuntimeConfig.Stage stage : config.stages()) {
                instances.add(plugins.create(stage.type(), stage.options()));
                stageIndex++;
            }
        } catch (RuntimeException rejected) {
            rejectedConfiguration = configurationError(stageIndex);
        } finally {
            for (int index = instances.size() - 1; index >= 0; index--) {
                try { instances.get(index).close(); }
                catch (Exception cleanupFailure) {
                    if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (rejectedConfiguration == null) rejectedConfiguration = configurationError(index);
                }
            }
        }
        if (rejectedConfiguration != null) throw rejectedConfiguration;
    }

    private IllegalArgumentException configurationError(int index) {
        RuntimeConfig.Stage stage = config.stages().get(index);
        List<String> keys = stage.options().keySet().stream()
                .filter(key -> key.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")).sorted().toList();
        // Factory exceptions can contain option or credential values; do not attach their causes.
        return new IllegalArgumentException("Configuration rejected by stage #" + (index + 1)
                + " ('" + stage.type() + "'); option keys: " + keys
                + ". Check required options, numeric limits and referenced environment variables.");
    }

    private boolean authorized(FullHttpRequest request) {
        if (token == null) return true;
        String value = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (value != null && value.startsWith("Bearer ")) {
            return MessageDigest.isEqual(token, value.substring(7).getBytes(StandardCharsets.UTF_8));
        }
        String protocols = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (protocols == null || protocols.length() > 8192) return false;
        for (String protocol : protocols.split(",")) {
            String candidate = protocol.trim();
            if (candidate.startsWith("bearer.")) {
                try { return MessageDigest.isEqual(token, Base64.getUrlDecoder().decode(candidate.substring(7))); }
                catch (IllegalArgumentException ignored) { return false; }
            }
        }
        return false;
    }

    private static boolean sameOrigin(FullHttpRequest request) {
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin == null) return true; // Non-browser clients authenticate with the bearer header.
        try {
            URI parsed = URI.create(origin);
            return ("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                    && parsed.getRawUserInfo() == null && parsed.getRawQuery() == null && parsed.getRawFragment() == null
                    && (parsed.getRawPath() == null || parsed.getRawPath().isEmpty())
                    && parsed.getRawAuthority() != null
                    && parsed.getRawAuthority().equalsIgnoreCase(request.headers().get(HttpHeaderNames.HOST));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private String metrics() {
        return "# HELP ominicat_active_sessions Current WebSocket sessions.\n# TYPE ominicat_active_sessions gauge\nominicat_active_sessions " + activeSessions.get()
                + "\n# TYPE ominicat_sessions_total counter\nominicat_sessions_total " + sessionsTotal.sum()
                + "\n# TYPE ominicat_frames_in_total counter\nominicat_frames_in_total " + framesIn.sum()
                + "\n# TYPE ominicat_frames_out_total counter\nominicat_frames_out_total " + framesOut.sum()
                + "\n# TYPE ominicat_frames_rejected_total counter\nominicat_frames_rejected_total " + rejected.sum()
                + "\n# TYPE ominicat_session_failures_total counter\nominicat_session_failures_total " + failures.sum()
                + "\n# TYPE ominicat_bytes_out_total counter\nominicat_bytes_out_total " + bytesOut.sum() + "\n";
    }

    private final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            if (!request.decoderResult().isSuccess()) { respond(context, HttpResponseStatus.BAD_REQUEST, "text/plain", "Invalid HTTP request"); return; }
            String path = new QueryStringDecoder(request.uri()).path();
            if (!request.method().name().equals("GET")) { respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", "GET required"); return; }
            switch (path) {
                case "/health" -> respond(context, HttpResponseStatus.OK, "application/json", "{\"status\":\"up\"}");
                case "/metrics" -> {
                    if (!authorized(request)) { unauthorized(context); return; }
                    respond(context, HttpResponseStatus.OK, "text/plain; version=0.0.4", metrics());
                }
                case "/" -> {
                    try (var input = OminiCatServer.class.getResourceAsStream("/web/index.html")) {
                        if (input == null) throw new IOException("Missing web resource");
                        respond(context, HttpResponseStatus.OK, "text/html; charset=utf-8", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException failure) {
                        respond(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", "Demo unavailable");
                    }
                }
                case "/ws" -> upgrade(context, request);
                default -> respond(context, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found");
            }
        }

        private void upgrade(ChannelHandlerContext context, FullHttpRequest request) {
            if (!authorized(request)) { unauthorized(context); return; }
            if (!sameOrigin(request)) { respond(context, HttpResponseStatus.FORBIDDEN, "text/plain", "Cross-origin WebSocket access denied"); return; }
            if (!"websocket".equalsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE))) {
                respond(context, HttpResponseStatus.BAD_REQUEST, "text/plain", "WebSocket upgrade required"); return;
            }
            var factory = new WebSocketServerHandshakerFactory("ws://localhost/ws", "ominicat", true, config.maxFrameBytes());
            WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel()).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (activeSessions.incrementAndGet() > config.maxSessions()) {
                activeSessions.decrementAndGet();
                rejected.increment();
                respond(context, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Session capacity reached"); return;
            }
            WsHandler socket = new WsHandler(handshaker);
            context.pipeline().addLast("websocket-aggregate", new WebSocketFrameAggregator(config.maxFrameBytes()));
            context.pipeline().addLast("websocket-session", socket);
            handshaker.handshake(context.channel(), request).addListener(future -> {
                if (!future.isSuccess()) { context.close(); return; }
                context.pipeline().remove(this);
                sessionsTotal.increment();
                socket.open(context.channel());
            });
        }

        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { context.close(); }
    }

    private final class WsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final WebSocketServerHandshaker handshaker;
        private final AtomicLong pendingBytes = new AtomicLong();
        private final AtomicBoolean terminating = new AtomicBoolean();
        private PipelineSession pipeline;
        private Channel channel;

        private WsHandler(WebSocketServerHandshaker handshaker) { this.handshaker = handshaker; }

        private void open(Channel connection) {
            channel = connection;
            if (terminating.get() || !connection.isActive()) return;
            try {
                pipeline = session(frame -> send(codec.encode(frame)));
                pipeline.completion().whenComplete((ignored, failure) -> {
                    if (failure != null && !terminating.get()) {
                        failures.increment();
                        terminate(1011, "Processor failed");
                    } else if (!terminating.get()) {
                        terminate(1000, "Pipeline complete");
                    }
                });
            } catch (RuntimeException failure) {
                failures.increment();
                terminate(1011, "Pipeline initialization failed");
            }
        }

        @Override protected void channelRead0(ChannelHandlerContext context, WebSocketFrame incoming) {
            if (incoming instanceof CloseWebSocketFrame close) {
                terminating.set(true);
                handshaker.close(context.channel(), close.retain());
            } else if (incoming instanceof PingWebSocketFrame ping) {
                context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            } else if (incoming instanceof PongWebSocketFrame) {
                // ReadTimeoutHandler already records this activity.
            } else if (incoming instanceof TextWebSocketFrame text) {
                if (pipeline == null || terminating.get()) return;
                try {
                    Frame frame = codec.decode(text.text());
                    framesIn.increment();
                    Frame payload = frame instanceof FrameEnvelope envelope ? envelope.frame() : frame;
                    if (payload instanceof Frame.Control control && control.signal() == Frame.Signal.INTERRUPT) {
                        pipeline.interrupt();
                        send(codec.encode(frame));
                    } else if (payload instanceof Frame.Control control && control.signal() == Frame.Signal.END) {
                        if (pipeline.trySubmit(frame)) pipeline.finish();
                        else {
                            rejected.increment();
                            send(codec.error("overloaded", "Session input queue is full or closed; retry end after draining"));
                        }
                    } else if (!pipeline.trySubmit(frame)) {
                        rejected.increment();
                        send(codec.error("overloaded", "Session input queue is full or closed"));
                    }
                } catch (IllegalArgumentException | IOException invalid) {
                    rejected.increment();
                    send(codec.error("invalid_frame", "Invalid frame fields or encoding"));
                }
            } else {
                terminate(1003, "JSON text frames required");
            }
        }

        private void send(String message) {
            Channel connection = channel;
            if (connection == null || terminating.get() || !connection.isActive()) return;
            int size = ByteBufUtil.utf8Bytes(message);
            if (size > config.maxFrameBytes() || pendingBytes.addAndGet(size) > 4L * config.maxFrameBytes() || !connection.isWritable()) {
                rejected.increment();
                terminate(1013, "Output capacity reached");
                return;
            }
            framesOut.increment();
            bytesOut.add(size);
            connection.writeAndFlush(new TextWebSocketFrame(message)).addListener(future -> {
                pendingBytes.addAndGet(-size);
                if (!future.isSuccess()) terminate(1011, "Output failed");
            });
        }

        private void terminate(int status, String reason) {
            Channel connection = channel;
            if (connection != null && terminating.compareAndSet(false, true)) {
                connection.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override public void channelInactive(ChannelHandlerContext context) {
            terminating.set(true);
            activeSessions.decrementAndGet();
            if (pipeline != null) pipeline.close();
        }

        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failures.increment();
            if (channel == null) context.close();
            else terminate(1009, "Invalid, oversized, or idle connection");
        }
    }

    private static void unauthorized(ChannelHandlerContext context) {
        var response = response(HttpResponseStatus.UNAUTHORIZED, "text/plain", "Bearer token required");
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void respond(ChannelHandlerContext context, HttpResponseStatus status, String contentType, String body) {
        context.writeAndFlush(response(status, contentType, body)).addListener(ChannelFutureListener.CLOSE);
    }

    private static DefaultFullHttpResponse response(HttpResponseStatus status, String contentType, String body) {
        var content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set("X-Content-Type-Options", "nosniff");
        HttpUtil.setContentLength(response, content.readableBytes());
        return response;
    }
}
