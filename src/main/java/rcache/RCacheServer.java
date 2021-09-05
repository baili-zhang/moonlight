package rcache;

import rcache.worker.Worker;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class RCacheServer {
    private static final int WORKER_NUMBER = 20;

    private static final int PORT = 7820;

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);

        try {
            while(true) {
                System.out.println("RCache is running, waiting for connect...");

                Socket socket = serverSocket.accept();
                new Thread(new Worker(socket)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            serverSocket.close();
        }

    }
}