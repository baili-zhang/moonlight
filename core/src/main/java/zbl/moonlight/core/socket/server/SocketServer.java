package zbl.moonlight.core.socket.server;

import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import zbl.moonlight.core.executor.Executor;
import zbl.moonlight.core.socket.client.CountDownSync;
import zbl.moonlight.core.socket.interfaces.SocketServerHandler;
import zbl.moonlight.core.socket.response.SocketResponse;
import zbl.moonlight.core.socket.response.WritableSocketResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SocketServer extends Executor<SocketResponse> {
    private static final Logger logger = LogManager.getLogger("SocketServer");

    private final SocketServerConfig config;
    private final ThreadPoolExecutor executor;
    private final Selector selector;

    @Setter
    private SocketServerHandler handler;

    /* 响应的队列 */
    private final ConcurrentHashMap<SelectionKey, SocketContext> contexts
            = new ConcurrentHashMap<>();

    /* IO线程的线程工厂 */
    private class IoThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, config.ioThreadNamePrefix() + threadNumber.getAndIncrement());
        }
    }

    public SocketServer(SocketServerConfig socketServerConfig) throws IOException {
        this.config = socketServerConfig;

        this.executor = new ThreadPoolExecutor(config.coreSize(),
                config.maxPoolSize(),
                config.keepAliveTime(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.blockingQueueSize()),
                new IoThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());

        selector = Selector.open();
    }

    @Override
    protected void doAfterShutdown() {

    }

    @Override
    public void run() {
        if(handler == null) {
            throw new RuntimeException("Handler can not be null.");
        }

        try {
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(new InetSocketAddress(config.port()), config.backlog());
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

            handler.handleStartupCompleted();

            /* TODO: 实现优雅关机 */
            while (isNotShutdown()) {
                selector.select();
                /* 如果线程被中断，则将线程中断位复位 */
                if(Thread.interrupted()) {
                    logger.info("Socket client has bean interrupted.");
                }

                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectionKeys.iterator();
                CountDownSync latch = new CountDownSync(selectionKeys.size());

                synchronized (selector) {
                    while (iterator.hasNext()) {
                        SelectionKey selectionKey = iterator.next();
                        try {
                            SocketContext context = contexts.get(selectionKey);
                            if(context == null) {
                                context = new SocketContext(selectionKey);
                                contexts.put(selectionKey, context);
                            }
                            executor.execute(new IoEventHandler(context, latch, handler));
                        } catch (RejectedExecutionException e) {
                            latch.countDown();
                            e.printStackTrace();
                        }
                        iterator.remove();
                    }
                }

                /* 从contexts中移除已经取消的selectionKey */
                for (SelectionKey key : contexts.keySet()) {
                    if(!key.isValid()) {
                        SocketAddress address = ((SocketChannel)key.channel()).getRemoteAddress();
                        contexts.remove(key);
                        logger.info("Delete response queue from [responses](map) of {}.", address);
                    }
                }

                /* 从队列中拿出 SocketResponse */
                while (true) {
                    SocketResponse response = poll();
                    if(response == null) {
                        break;
                    }

                    SelectionKey selectionKey = response.selectionKey();
                    /* TODO: context 可能为 null，需要排查原因 */
                    SocketContext context = contexts.get(selectionKey);
                    context.offerResponse(new WritableSocketResponse(response));
                }

                latch.await();

                handler.handleAfterLatchAwait();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
