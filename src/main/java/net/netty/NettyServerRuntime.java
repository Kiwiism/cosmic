package net.netty;

import config.YamlConfig;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NettyServerRuntime {
    private static final Logger log = LoggerFactory.getLogger(NettyServerRuntime.class);
    private static final NettyServerRuntime instance = new NettyServerRuntime();

    private EventLoopGroup acceptorGroup;
    private EventLoopGroup workerGroup;

    private NettyServerRuntime() {
    }

    public static NettyServerRuntime getInstance() {
        return instance;
    }

    public synchronized void start() {
        if (acceptorGroup != null && !acceptorGroup.isShuttingDown()) {
            return;
        }

        acceptorGroup = new NioEventLoopGroup(Math.max(1, YamlConfig.config.server.NETTY_BOSS_THREADS),
                namedFactory("Cosmic-Netty-Acceptor"));
        workerGroup = new NioEventLoopGroup(Math.max(1, YamlConfig.config.server.NETTY_WORKER_THREADS),
                namedFactory("Cosmic-Netty-Worker"));
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return task -> new Thread(task, prefix + "-" + sequence.getAndIncrement());
    }

    public EventLoopGroup acceptorGroup() {
        start();
        return acceptorGroup;
    }

    public EventLoopGroup workerGroup() {
        start();
        return workerGroup;
    }

    public synchronized void stop() {
        if (acceptorGroup == null) {
            return;
        }

        log.info("Stopping shared Netty event loops");
        try {
            acceptorGroup.shutdownGracefully().syncUninterruptibly();
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
        } finally {
            acceptorGroup = null;
            workerGroup = null;
        }
    }
}
