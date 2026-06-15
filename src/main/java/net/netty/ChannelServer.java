package net.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class ChannelServer extends AbstractServer {
    private final int world;
    private final int channel;
    private Channel nettyChannel;

    public ChannelServer(int port, int world, int channel) {
        super(port);
        this.world = world;
        this.channel = channel;
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
                .childHandler(new ChannelServerInitializer(world, channel));

        this.nettyChannel = bootstrap.bind(port).syncUninterruptibly().channel();
    }

    @Override
    public void stop() {
        if (nettyChannel == null) {
            return;
        }

        nettyChannel.close().syncUninterruptibly();
        nettyChannel = null;
    }
}
