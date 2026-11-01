package io.adzubla.blocks.error;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends requests to an embedded server, for what MockMvc can't show: it has no servlet error
 * dispatch, so it never reaches Spring Boot's {@code /error} and lets filter exceptions escape.
 */
public final class RealServer {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private RealServer() {
    }

    public static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return send(port, "GET", path, null, null);
    }

    public static HttpResponse<String> send(int port, String method, String path, String contentType, String body)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).method(method,
                body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
