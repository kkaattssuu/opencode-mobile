package app.opencodemobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ServerConnection {
    static final String BASE = "http://127.0.0.1:4096/";

    static String request(String base, String path, String method, String authorization) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", authorization);
            c.setRequestProperty("Accept", "application/json");
            int status = c.getResponseCode();
            if (status == 204) return "";
            if (status != 200) throw new HttpError(status);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 65536) throw new IOException("Unexpectedly large server response");
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) {
            super("OpenCode returned HTTP " + status);
            this.status = status;
        }
    }

    private ServerConnection() {}
}
