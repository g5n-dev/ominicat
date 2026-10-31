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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Embedded, configurable WebSocket container. Netty owns socket I/O; each accepted session
 * owns its own microkernel pipeline and worker. Instances are single-use.
 */
public final class OminiCatServer implements AutoCloseable {
    private static final int CLOSE_TIMEOUT_SECONDS = 2;
    private final RuntimeConfig config;
    private final PluginRegistry plugins;
    private final byte[] token;
    private final FrameCodec codec = new FrameCodec();
    private final RuntimeSnapshot runtimeSnapshot;
    private final ActivityJournal activityJournal = new ActivityJournal();
    private final NioEventLoopGroup acceptors;
    private final NioEventLoopGroup ioWorkers;
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final AtomicInteger admittedSessions = new AtomicInteger();
    private final AtomicInteger pipelineWorkers = new AtomicInteger();
    private final AtomicInteger openConnections = new AtomicInteger();
    private final AtomicLong pendingOutputBytes = new AtomicLong();
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
        runtimeSnapshot = new RuntimeSnapshot(config, token != null);
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
                            if (closed.get() || !reserve(openConnections, config.maxConnections())) {
                                rejected.increment();
                                channel.close();
                                return;
                            }
                            channel.closeFuture().addListener(ignored -> openConnections.decrementAndGet());
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

    /** Best-effort resource gauges; an abandoned noncooperative worker retains its admission slot. */
    public Resources resources() {
        return new Resources(openConnections.get(), activeSessions.get(), admittedSessions.get(),
                pipelineWorkers.get(), pendingOutputBytes.get());
    }

    /** Counts owned resources, including workers whose socket closed before processor cleanup finished. */
    public record Resources(int connections, int activeSessions, int admittedSessions,
                            int pipelineWorkers, long pendingOutputBytes) { }

    private static boolean reserve(AtomicInteger count, int limit) {
        while (true) {
            int current = count.get();
            if (current >= limit) return false;
            if (count.compareAndSet(current, current + 1)) return true;
        }
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

    private PipelineSession session(io.ominicat.core.FrameSink output, ActivitySession activity) {
        List<Processor> processors = new ArrayList<>();
        try {
            var builder = Pipeline.builder();
            for (int index = 0; index < config.stages().size(); index++) {
                RuntimeConfig.Stage stage = config.stages().get(index);
                Processor processor = plugins.create(stage.type(), stage.options());
                processors.add(processor);
                Processor decorated = EnvelopeProcessor.wrap(processor);
                builder.add(activity == null ? decorated : activity.wrap(decorated, index, stage.type()));
            }
            return builder.build().openSession(new SessionOptions(config.queueCapacity()), output);
        } catch (Throwable failure) {
            for (int index = processors.size() - 1; index >= 0; index--) {
                Processor processor = processors.get(index);
                try { processor.close(); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Processor initialization failed", failure);
        }
    }

    private void preflight() {
        List<Processor> instances = new ArrayList<>();
        List<Throwable> problems = new ArrayList<>();
        int rejectedStage = -1;
        int stageIndex = 0;
        try {
            for (RuntimeConfig.Stage stage : config.stages()) {
                instances.add(plugins.create(stage.type(), stage.options()));
                stageIndex++;
            }
        } catch (Throwable rejected) {
            problems.add(rejected);
            rejectedStage = stageIndex;
        } finally {
            for (int index = instances.size() - 1; index >= 0; index--) {
                try { instances.get(index).close(); }
                catch (Throwable cleanupFailure) {
                    if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                    problems.add(cleanupFailure);
                    if (rejectedStage < 0) rejectedStage = index;
                }
            }
        }
        for (Throwable problem : problems) {
            if (problem instanceof Error fatal) {
                for (Throwable other : problems) if (other != fatal) fatal.addSuppressed(other);
                throw fatal;
            }
        }
        if (!problems.isEmpty()) throw configurationError(rejectedStage);
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

    private boolean allowedHost(Channel connection, FullHttpRequest request) {
        if (token != null) return true;
        Channel listening = connection.parent() == null ? connection : connection.parent();
        if (!(listening.localAddress() instanceof InetSocketAddress address)
                || address.getAddress() == null || !address.getAddress().isLoopbackAddress()) return true;
        List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
        if (hosts.size() != 1) return false;
        String authority = hosts.get(0);
        String host;
        String suffix;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0 || !authority.substring(1, end).equals("::1")) return false;
            host = "::1";
            suffix = authority.substring(end + 1);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            suffix = colon < 0 ? "" : authority.substring(colon);
        }
        if (!suffix.isEmpty() && (!suffix.startsWith(":") || !decimal(suffix.substring(1), 65535, false))) return false;
        if (host.equalsIgnoreCase("localhost") || host.equals("::1")) return true;
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) return false;
        for (String octet : octets) if (!decimal(octet, 255, true)) return false;
        return true;
    }

    private static boolean decimal(String value, int maximum, boolean canonical) {
        if (value.isEmpty() || value.length() > 5 || canonical && value.length() > 1 && value.charAt(0) == '0') return false;
        int parsed = 0;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') return false;
            parsed = parsed * 10 + digit - '0';
        }
        return parsed <= maximum;
    }

    private String metrics() {
        return "# HELP ominicat_active_sessions Current WebSocket sessions.\n# TYPE ominicat_active_sessions gauge\nominicat_active_sessions " + activeSessions.get()
                + "\n# TYPE ominicat_sessions_total counter\nominicat_sessions_total " + sessionsTotal.sum()
                + "\n# TYPE ominicat_admitted_sessions gauge\nominicat_admitted_sessions " + admittedSessions.get()
                + "\n# TYPE ominicat_connections gauge\nominicat_connections " + openConnections.get()
                + "\n# TYPE ominicat_live_pipeline_workers gauge\nominicat_live_pipeline_workers " + pipelineWorkers.get()
                + "\n# TYPE ominicat_pending_output_bytes gauge\nominicat_pending_output_bytes " + pendingOutputBytes.get()
                + "\n# TYPE ominicat_frames_in_total counter\nominicat_frames_in_total " + framesIn.sum()
                + "\n# TYPE ominicat_frames_out_total counter\nominicat_frames_out_total " + framesOut.sum()
                + "\n# TYPE ominicat_frames_rejected_total counter\nominicat_frames_rejected_total " + rejected.sum()
                + "\n# TYPE ominicat_session_failures_total counter\nominicat_session_failures_total " + failures.sum()
                + "\n# TYPE ominicat_bytes_out_total counter\nominicat_bytes_out_total " + bytesOut.sum() + "\n";
    }

    private final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            if (!request.decoderResult().isSuccess()) { respond(context, HttpResponseStatus.BAD_REQUEST, "text/plain", "Invalid HTTP request"); return; }
            if (!allowedHost(context.channel(), request)) {
                rejected.increment();
                respond(context, HttpResponseStatus.FORBIDDEN, "text/plain", "Host is not allowed for this loopback listener");
                return;
            }
            String path = new QueryStringDecoder(request.uri()).path();
            if (!request.method().name().equals("GET")) { respond(context, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", "GET required"); return; }
            switch (path) {
                case "/health" -> respond(context, HttpResponseStatus.OK, "application/json", "{\"status\":\"up\"}");
                case "/metrics" -> {
                    if (!authorized(request)) { unauthorized(context); return; }
                    respond(context, HttpResponseStatus.OK, "text/plain; version=0.0.4", metrics());
                }
                case "/api/runtime" -> {
                    if (!authorized(request)) { unauthorized(context); return; }
                    if (!sameOrigin(request)) {
                        respond(context, HttpResponseStatus.FORBIDDEN, "text/plain", "Cross-origin runtime access denied");
                        return;
                    }
                    try {
                        var counters = new RuntimeSnapshot.Counters(sessionsTotal.sum(), framesIn.sum(), framesOut.sum(),
                                rejected.sum(), failures.sum(), bytesOut.sum());
                        respond(context, HttpResponseStatus.OK, "application/json", runtimeSnapshot.json(resources(), counters));
                    } catch (IOException | RuntimeException unavailable) {
                        respond(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", "Runtime snapshot unavailable");
                    }
                }
                case "/api/activity" -> {
                    if (!authorized(request)) { unauthorized(context); return; }
                    if (!sameOrigin(request)) {
                        respond(context, HttpResponseStatus.FORBIDDEN, "text/plain", "Cross-origin activity access denied");
                        return;
                    }
                    var values = new QueryStringDecoder(request.uri()).parameters().get("after");
                    long after = 0;
                    try {
                        if (values != null) {
                            if (values.size() != 1 || !values.get(0).matches("[0-9]{1,19}")) throw new IllegalArgumentException();
                            after = Long.parseLong(values.get(0));
                        }
                    } catch (IllegalArgumentException invalid) {
                        respond(context, HttpResponseStatus.BAD_REQUEST, "text/plain", "Invalid activity cursor");
                        return;
                    }
                    try { respond(context, HttpResponseStatus.OK, "application/json", activityJournal.json(after)); }
                    catch (IOException unavailable) {
                        respond(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", "Activity snapshot unavailable");
                    }
                }
                case "/" -> resource(context, "/web/index.html", "text/html; charset=utf-8");
                case "/console.css" -> resource(context, "/web/console.css", "text/css; charset=utf-8");
                case "/console.js" -> resource(context, "/web/console.js", "application/javascript; charset=utf-8");
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
            var observation = new QueryStringDecoder(request.uri()).parameters().get("observe");
            if (observation != null && (observation.size() != 1
                    || !(observation.get(0).equals("1") || observation.get(0).equals("0")))) {
                respond(context, HttpResponseStatus.BAD_REQUEST, "text/plain", "Invalid observation setting"); return;
            }
            var factory = new WebSocketServerHandshakerFactory("ws://localhost/ws", "ominicat", true, config.maxFrameBytes());
            WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel()).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (!reserve(admittedSessions, config.maxSessions())) {
                rejected.increment();
                respond(context, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Session capacity reached"); return;
            }
            activeSessions.incrementAndGet();
            WsHandler socket = new WsHandler(observation != null && observation.get(0).equals("1") ? activityJournal.openSession() : null);
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

    /** Only constant, explicitly routed assets are served; request paths never become resource paths. */
    private static void resource(ChannelHandlerContext context, String path, String contentType) {
        try (var input = OminiCatServer.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing web resource");
            respond(context, HttpResponseStatus.OK, contentType, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            respond(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain", "Console asset unavailable");
        }
    }

    private final class WsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final ActivitySession activity;
        private final AtomicLong pendingBytes = new AtomicLong();
        private final AtomicBoolean terminating = new AtomicBoolean();
        private final AtomicBoolean socketInactive = new AtomicBoolean();
        private final AtomicBoolean workerComplete = new AtomicBoolean();
        private final AtomicBoolean admissionReleased = new AtomicBoolean();
        private final Object outputLock = new Object();
        private volatile PipelineSession pipeline;
        private volatile Channel channel;
        private volatile ScheduledFuture<?> closeDeadline;
        private volatile boolean endAccepted;
        private volatile boolean cancellationRequested;

        private WsHandler(ActivitySession activity) { this.activity = activity; }

        private void open(Channel connection) {
            channel = connection;
            if (terminating.get() || !connection.isActive()) return;
            try {
                pipeline = session(this::sendOutput, activity);
                pipelineWorkers.incrementAndGet();
                pipeline.completion().whenComplete((ignored, failure) -> {
                    pipelineWorkers.decrementAndGet();
                    workerComplete.set(true);
                    if (activity != null) activity.record("session_settled", failure != null
                            && !(cancellationRequested && cancellationOnly(failure)) ? "failed"
                            : endAccepted && !cancellationRequested ? "drained" : "closed_or_cancelled");
                    releaseAdmission();
                    if (failure != null && !terminating.get()) {
                        failures.increment();
                        terminate(1011, "Processor failed");
                    } else if (!terminating.get()) {
                        terminate(1000, "Pipeline complete");
                    }
                });
            } catch (Throwable failure) {
                if (activity != null) activity.record("session_settled", "initialization_failed");
                workerComplete.set(true);
                releaseAdmission();
                failures.increment();
                terminate(1011, "Pipeline initialization failed");
            }
        }

        @Override protected void channelRead0(ChannelHandlerContext context, WebSocketFrame incoming) {
            if (incoming instanceof CloseWebSocketFrame close) {
                beginClose(close.retain());
            } else if (incoming instanceof PingWebSocketFrame ping) {
                sendFrame(new PongWebSocketFrame(ping.content().retain()), ping.content().readableBytes());
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
                        if (activity != null) activity.record("interrupt", frame, "requested");
                        sendOutput(frame);
                    } else if (payload instanceof Frame.Control control && control.signal() == Frame.Signal.END) {
                        if (submit(frame)) { endAccepted = true; pipeline.finish(); }
                        else {
                            rejected.increment();
                            send(codec.error("overloaded", "Session input queue is full or closed; retry end after draining"));
                        }
                    } else if (!submit(frame)) {
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

        private boolean submit(Frame frame) {
            boolean accepted = pipeline.trySubmit(frame);
            if (activity != null) activity.record(accepted ? "ingress_accepted" : "ingress_rejected", frame,
                    accepted ? "accepted" : "rejected");
            return accepted;
        }

        private void sendOutput(Frame frame) throws IOException {
            if (activity == null) { send(codec.encode(frame)); return; }
            ActivityJournal.Description description = ActivityJournal.describe(frame);
            String message = codec.encode(frame);
            if (terminating.get()) { activity.transport("transport_output_failed", description, "closed"); return; }
            int size = ByteBufUtil.utf8Bytes(message);
            if (size > config.maxFrameBytes()) {
                activity.transport("transport_output_failed", description, "capacity_rejected");
                rejected.increment(); terminate(1013, "Output capacity reached"); return;
            }
            sendFrame(new TextWebSocketFrame(message), size, description);
        }

        private void send(String message) {
            if (terminating.get()) return;
            int size = ByteBufUtil.utf8Bytes(message);
            if (size > config.maxFrameBytes()) {
                rejected.increment();
                terminate(1013, "Output capacity reached");
                return;
            }
            sendFrame(new TextWebSocketFrame(message), size);
        }

        private void sendFrame(WebSocketFrame frame, int size) {
            sendFrame(frame, size, null);
        }

        private void sendFrame(WebSocketFrame frame, int size, ActivityJournal.Description description) {
            boolean overloaded = false;
            synchronized (outputLock) {
                Channel connection = channel;
                if (connection == null || terminating.get() || !connection.isActive()) {
                    frame.release();
                    if (description != null) activity.transport("transport_output_failed", description, "closed");
                    return;
                }
                if (pendingBytes.get() + size > 4L * config.maxFrameBytes() || !connection.isWritable()) {
                    frame.release();
                    overloaded = true;
                } else {
                    pendingBytes.addAndGet(size);
                    pendingOutputBytes.addAndGet(size);
                    framesOut.increment(); bytesOut.add(size);
                    if (description != null) activity.transport("transport_output_queued", description, "queued");
                    connection.writeAndFlush(frame).addListener(future -> {
                        pendingBytes.addAndGet(-size);
                        pendingOutputBytes.addAndGet(-size);
                        if (description != null) activity.transport(future.isSuccess() ? "transport_output_written" : "transport_output_failed",
                                description, future.isSuccess() ? "netty_write" : "write_failed");
                        if (!future.isSuccess()) terminate(1011, "Output failed");
                    });
                }
            }
            if (overloaded) {
                if (description != null) activity.transport("transport_output_failed", description, "capacity_rejected");
                rejected.increment();
                terminate(1013, "Output capacity reached");
            }
        }

        private void terminate(int status, String reason) {
            beginClose(new CloseWebSocketFrame(status, reason));
        }

        private void beginClose(CloseWebSocketFrame close) {
            Channel connection = channel;
            if (connection == null || !terminating.compareAndSet(false, true)) {
                close.release();
                return;
            }
            cancellationRequested = true;
            PipelineSession active = pipeline;
            if (active != null) active.close();
            synchronized (outputLock) {
                connection.writeAndFlush(close).addListener(ChannelFutureListener.CLOSE);
            }
            try {
                ScheduledFuture<?> deadline = connection.eventLoop().schedule(() -> { connection.close(); },
                        CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                closeDeadline = deadline;
                if (socketInactive.get()) deadline.cancel(false);
            } catch (java.util.concurrent.RejectedExecutionException stopping) { connection.close(); }
        }

        private void releaseAdmission() {
            if (socketInactive.get() && workerComplete.get() && admissionReleased.compareAndSet(false, true)) {
                admittedSessions.decrementAndGet();
            }
        }

        @Override public void channelInactive(ChannelHandlerContext context) {
            cancellationRequested = true;
            terminating.set(true);
            socketInactive.set(true);
            ScheduledFuture<?> deadline = closeDeadline;
            if (deadline != null) deadline.cancel(false);
            activeSessions.decrementAndGet();
            if (pipeline != null) pipeline.close();
            else workerComplete.set(true);
            releaseAdmission();
        }

        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failures.increment();
            if (channel == null) context.close();
            else terminate(1009, "Invalid, oversized, or idle connection");
        }
    }

    /** A cooperative close is not a processor failure; suppressed cleanup errors still are failures. */
    private static boolean cancellationOnly(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) {
            if (++depth > 16 || current.getSuppressed().length != 0 || current.getCause() == null || current.getCause() == current) return false;
            current = current.getCause();
        }
        return current instanceof java.util.concurrent.CancellationException
                && current.getSuppressed().length == 0 && current.getCause() == null;
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
