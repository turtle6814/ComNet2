public interface HttpServerImpl {
    void start(HttpRequestHandler handler);

    void stop();
}
