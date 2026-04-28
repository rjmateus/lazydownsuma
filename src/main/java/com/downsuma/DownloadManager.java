package com.downsuma;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.*;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages upstream downloads with:
 * - Per-URL deduplication (only one upstream fetch per URL at a time)
 * - Streaming to multiple waiting clients as data arrives
 * - Semaphore-based limit on concurrent upstream downloads
 * - Post-download checksum verification
 */
public class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);

    private final OkHttpClient httpClient;
    private final ChecksumService checksumService;
    private final Semaphore downloadSemaphore = new Semaphore(Config.MAX_CONCURRENT_DOWNLOADS);

    /**
     * Tracks in-flight downloads. Each entry represents an active upstream download
     * that one or more clients are waiting on.
     */
    private final ConcurrentHashMap<String, ActiveDownload> activeDownloads = new ConcurrentHashMap<>();

    public DownloadManager(OkHttpClient httpClient, ChecksumService checksumService) {
        this.httpClient = httpClient;
        this.checksumService = checksumService;
    }

    /**
     * Returns a cached file path, or streams from upstream (caching along the way).
     * Multiple concurrent callers for the same URL share one upstream download.
     *
     * @param url            upstream URL
     * @param clientOutput   the servlet output stream to write to
     * @throws IOException   on I/O failure
     * @throws DownloadException on checksum mismatch or upstream error
     */
    public void streamToClient(String url, OutputStream clientOutput) throws IOException, DownloadException {
        Path cachedFile = cachePathFor(url);

        // Fast path: already cached and verified
        if (Files.exists(cachedFile)) {
            log.info("Serving cached file for {}", url);
            try (InputStream in = Files.newInputStream(cachedFile)) {
                in.transferTo(clientOutput);
            }
            return;
        }

        // Ensure cache directory exists
        Files.createDirectories(cachedFile.getParent());

        // Join or start an active download
        ActiveDownload download = activeDownloads.computeIfAbsent(url, k -> new ActiveDownload());

        try {
            // The first thread to arrive becomes the downloader
            if (download.becomeDownloader()) {
                try {
                    if (Files.exists(cachedFile)) {
                        log.info("File was cached while waiting for lock, skipping download: {}", url);
                        download.complete();
                    } else {
                        performDownload(url, cachedFile, download);
                    }
                } catch (Exception e) {
                    download.fail(e);
                    throw e;
                } finally {
                    activeDownloads.remove(url);
                }
            }

            // Wait for download completion and stream the result
            download.awaitCompletion();

            // If download failed, propagate
            if (download.getError() != null) {
                Throwable err = download.getError();
                if (err instanceof DownloadException de) throw de;
                if (err instanceof IOException ioe) throw ioe;
                throw new DownloadException("Download failed: " + err.getMessage(), err);
            }

            // Stream the verified cached file to the client
            try (InputStream in = Files.newInputStream(cachedFile)) {
                in.transferTo(clientOutput);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download interrupted", e);
        }
    }

    private void performDownload(String url, Path cachedFile, ActiveDownload download) throws IOException, DownloadException {
        log.info("Downloading from upstream: {}", url);

        try {
            downloadSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Interrupted waiting for download slot", e);
        }

        Path tempFile = cachedFile.resolveSibling(cachedFile.getFileName() + ".tmp");

        try {
            // Resolve expected checksum before starting download
            String expected = checksumService.getExpectedChecksum(url);

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new DownloadException("Upstream returned HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new DownloadException("Upstream returned empty body");
                }

                try (InputStream upstream = body.byteStream();
                     OutputStream fileOut = Files.newOutputStream(tempFile,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                    byte[] buf = new byte[Config.BUFFER_SIZE];
                    int n;
                    while ((n = upstream.read(buf)) != -1) {
                        fileOut.write(buf, 0, n);
                        digest.update(buf, 0, n);
                    }
                }
            }

            // Verify checksum computed during download
            if (expected != null) {
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!expected.equals(actual)) {
                    Files.deleteIfExists(tempFile);
                    throw new DownloadException(
                            "Checksum mismatch for " + url + ": expected " + expected + ", got " + actual);
                }
                log.info("Checksum verified for {}", url);
            } else {
                log.warn("No checksum available for {}, skipping verification", url);
            }

            // Atomically move temp -> cache
            Files.move(tempFile, cachedFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("Cached: {}", cachedFile);
            download.complete();

        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        } finally {
            downloadSemaphore.release();
        }
    }

    /**
     * Derives a local cache path from the URL.
     * Uses URL encoding to create a safe filename.
     */
    Path cachePathFor(String url) {
        String safeName = url.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Truncate if too long for filesystem
        if (safeName.length() > 200) {
            String hash = Integer.toHexString(url.hashCode());
            safeName = safeName.substring(0, 180) + "_" + hash;
        }
        return Config.CACHE_DIR.resolve(safeName);
    }

    /**
     * Tracks state of an in-flight download so multiple clients can wait on it.
     */
    static class ActiveDownload {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean downloaderAssigned = false;
        private volatile Throwable error;

        synchronized boolean becomeDownloader() {
            if (!downloaderAssigned) {
                downloaderAssigned = true;
                return true;
            }
            return false;
        }

        void complete() {
            latch.countDown();
        }

        void fail(Throwable e) {
            this.error = e;
            latch.countDown();
        }

        void awaitCompletion() throws InterruptedException {
            if (!latch.await(Config.DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timed out waiting for download to complete");
            }
        }

        Throwable getError() {
            return error;
        }
    }
}
