package com.downsuma;

import static spark.Spark.*;

import java.io.OutputStream;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        OkHttpClient httpClient = new OkHttpClient();
        ChecksumService checksumService = new ChecksumService(httpClient);
        DownloadManager downloadManager = new DownloadManager(httpClient, checksumService);

        port(Config.PORT);

        get("/download", (req, res) -> {
            String url = req.queryParams("url");
            if (url == null || url.isBlank()) {
                res.status(400);
                return "Missing required query parameter: url";
            }

            log.info("Download request for: {}", url);

            try {
                res.type("application/octet-stream");

                // Derive a filename from the URL for Content-Disposition
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                if (fileName.isBlank()) fileName = "download";
                res.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

                OutputStream out = res.raw().getOutputStream();
                downloadManager.streamToClient(url, out);
                out.flush();
                return null;

            } catch (DownloadException e) {
                log.error("Download failed for {}: {}", url, e.getMessage());
                res.status(502);
                return "Download failed: " + e.getMessage();
            } catch (Exception e) {
                log.error("Unexpected error for {}: {}", url, e.getMessage(), e);
                res.status(500);
                return "Internal server error";
            }
        });

        log.info("Downsuma server started on port {}", Config.PORT);
        log.info("Cache directory: {}", Config.CACHE_DIR.toAbsolutePath());
        log.info("Max concurrent downloads: {}", Config.MAX_CONCURRENT_DOWNLOADS);
    }
}
