import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLogLevel;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class TestCorruptedWrites {

    private static Server server = null;
    private final static int port = 8089;
    private final static String endpoint = "/test";
    static String message = "event: put\ndata:{'property':'value'}\n\n";

    private static void sleep(int millis){
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @BeforeClass
    public static void initMockServer() throws Exception {
        System.out.println(message.length());

        final ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
                resp.flushBuffer();

                Writer writer = resp.getWriter();
                while (true) {
                    writer.write(message);
                    writer.flush();
                    sleep(300);
                }
            }
        }), endpoint);
        contextHandler.setContextPath("/");

        server = new Server(new QueuedThreadPool(50, 10));
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(contextHandler);
        server.start();
    }

    @Test
    public void testForCommunity() throws InterruptedException, IOException {
        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());

        final Bootstrap bootstrap = new Bootstrap();
        final URI uri = URI.create("http://localhost:" + port + endpoint);

        bootstrap
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .remoteAddress(new InetSocketAddress(uri.getHost(), uri.getPort()))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG));
                        pipeline.addLast("line", new DelimiterBasedFrameDecoder(Integer.MAX_VALUE, Delimiters.lineDelimiter()));
                        pipeline.addLast("string", new StringDecoder());
                        pipeline.addLast("encoder", new HttpRequestEncoder());
                        pipeline.addLast("log", new SimpleChannelInboundHandler<String>(){
                            @Override
                            protected void messageReceived(ChannelHandlerContext ctx, String msg) throws Exception {
                                System.out.println(" ---> " + msg);
                            }
                            @Override
                            public void channelActive(ChannelHandlerContext context) {
                                System.out.println("active!");
                                HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.toString());
                                request.headers().add(HttpHeaders.Names.ACCEPT, "text/event-stream");
                                request.headers().add(HttpHeaders.Names.HOST, uri.getHost());
                                request.headers().add(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost());
                                context.channel().writeAndFlush(request);
                            }
                        });
                    }
                })
                .connect();
        Thread.sleep(500_000);
    }
}
