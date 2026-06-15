package net.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class LoginServer extends AbstractServer {
    public static final int WORLD_ID = -1;
    public static final int CHANNEL_ID = -1;
    private Channel channel;

    public LoginServer(int port) {
        super(port);
    }

    @Override
    public void start() {
        NettyServerRuntime runtime = NettyServerRuntime.getInstance();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(runtime.acceptorGroup(), runtime.workerGroup())
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new LoginServerInitializer());

        this.channel = bootstrap.bind(port).syncUninterruptibly().channel();
    }

    @Override
    public void stop() {
        if (channel == null) {
            return;
        }

        channel.close().syncUninterruptibly();
        channel = null;
    }
}
