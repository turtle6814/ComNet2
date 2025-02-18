import java.nio.file.Paths;
import java.util.concurrent.Executors;

public class HttpServer {

    public static void main(String[] args) throws Exception {
        var server = new SimpleHttpServer(Executors.newFixedThreadPool(10), 8080, 10_000);

        String baseDirectory = Paths.get("files").toAbsolutePath().toString();

        HttpRequestHandler fileHandler = new FileHttpRequestHandler(baseDirectory);
//        server.start(r -> {
//
//            var body = """
//                    {
//                        "id": 1,
//                        "url": "%s"
//                    }
//                    """.formatted(r.url())
//                    .getBytes(StandardCharsets.UTF_8);
//
//            var headers = Map.of("Content-type", List.of("application/json"),
//                    "Content_Length", List.of(String.valueOf(body.length)));
//            return new HttpResponse(200, headers, body);
//        });
        System.err.println("");
        server.start(fileHandler);
    }
}
