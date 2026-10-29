package dza.fut.paipo;

public interface StreamResolverStrategy {
    void resolve(String url, StreamResolverCallback callback);
}