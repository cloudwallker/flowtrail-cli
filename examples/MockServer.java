import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MockServer {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8099 : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/echo", exchange -> {
            byte[] response = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.createContext("/hello", exchange -> {
            byte[] response = "Hello from the local server".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
        }));
        server.start();
        System.out.println("Mock server: http://127.0.0.1:" + server.getAddress().getPort() + " (Ctrl+C to stop)");
    }
}
