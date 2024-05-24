import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileHttpRequestHandler implements HttpRequestHandler {

    private final String baseDirectory;

    public FileHttpRequestHandler(String baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        String urlPath = request.url();
        Path filePath = Paths.get(baseDirectory, urlPath);

        if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
            try {
                byte[] fileContent = Files.readAllBytes(filePath);
                Map<String, List<String>> headers = new HashMap<>();
                headers.put("Content-Type", List.of("text/plain"));
                headers.put("Content-Length", List.of(String.valueOf(fileContent.length)));

                return new HttpResponse(200, headers, fileContent);
            } catch (IOException e) {
                e.printStackTrace();
                return new HttpResponse(500, Map.of("Content-Type", List.of("text/plain")), "Internal Server Error".getBytes());
            }
        } else {
            return new HttpResponse(404, Map.of("Content-Type", List.of("text/plain")), "File Not Found".getBytes());
        }
    }
}
