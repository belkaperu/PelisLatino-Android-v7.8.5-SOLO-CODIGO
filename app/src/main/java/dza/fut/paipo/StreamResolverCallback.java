package dza.fut.paipo;

public interface StreamResolverCallback {
    void onStreamFound(StreamResult result);
    void onError(String error);
}