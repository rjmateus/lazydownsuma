package com.downsuma;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and verifies SHA-256 checksums for upstream URLs.
 * <p>
 * Lookup order:
 * 1. Local checksums.properties mapping file.
 * 2. Remote .sha256 file (appends .sha256 to the upstream URL).
 */
public class ChecksumService {

    private static final Logger log = LoggerFactory.getLogger(ChecksumService.class);
    private final Properties localChecksums = new Properties();
    private final OkHttpClient httpClient;

    public ChecksumService(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        loadLocalChecksums();
    }

    private void loadLocalChecksums() {
        Path path = Config.CHECKSUMS_FILE;
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                localChecksums.load(in);
                log.info("Loaded {} checksum entries from {}", localChecksums.size(), path);
            } catch (IOException e) {
                log.warn("Failed to load checksums file: {}", e.getMessage());
            }
        } else {
            log.info("No local checksums file found at {}", path);
        }
    }

    /**
     * Returns the expected SHA-256 hex string for the given URL, or null if unavailable.
     */
    public String getExpectedChecksum(String url) {
        // 1. Check local mapping
        String local = localChecksums.getProperty(url);
        if (local != null && !local.isBlank()) {
            log.debug("Checksum for {} found in local mapping", url);
            return local.strip().toLowerCase();
        }

        // 2. Fetch remote .sha256
        String sha256Url = url + ".sha256";
        Request request = new Request.Builder().url(sha256Url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                // .sha256 files typically contain: "<hash>  <filename>" or just the hash
                String body = response.body().string().strip();
                String hash = body.split("\\s+")[0].toLowerCase();
                log.debug("Fetched remote checksum for {}: {}", url, hash);
                return hash;
            }
        } catch (IOException e) {
            log.warn("Failed to fetch checksum from {}: {}", sha256Url, e.getMessage());
        }

        log.warn("No checksum available for {}", url);
        return null;
    }

    /**
     * Computes the SHA-256 hex digest of a file.
     */
    public static String computeFileChecksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[Config.BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
