package com.downsuma;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Config {

    private Config() {}

    /** Local directory used as the file cache. */
    public static final Path CACHE_DIR = Paths.get("cache");

    /** Maximum number of concurrent upstream downloads. */
    public static final int MAX_CONCURRENT_DOWNLOADS = 4;

    /** Server port. */
    public static final int PORT = 8080;

    /** Path to the local checksums mapping file (URL -> SHA-256 hex). */
    public static final Path CHECKSUMS_FILE = Paths.get("checksums.properties");

    /** Read buffer size in bytes for streaming. */
    public static final int BUFFER_SIZE = 8192;
}
