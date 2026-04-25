# Downsuma - Implementation Notes

## Project Structure

- **pom.xml** — Maven project with Spark, OkHttp, SLF4J dependencies
- **src/main/java/com/downsuma/Config.java** — Constants (cache dir, max downloads, port, buffer size)
- **src/main/java/com/downsuma/ChecksumService.java** — Loads `checksums.properties` for local lookups, falls back to fetching remote `.sha256` files
- **src/main/java/com/downsuma/DownloadManager.java** — Core logic: cache-or-fetch, per-URL dedup via `ConcurrentHashMap` + `CountDownLatch`, semaphore-limited concurrency, checksum verification, cleanup on failure
- **src/main/java/com/downsuma/DownloadException.java** — Custom exception for download/checksum errors
- **src/main/java/com/downsuma/App.java** — Spark endpoint `GET /download?url=<upstream_url>`
- **checksums.properties** — Sample checksum mapping file (URL → SHA-256 hex)

## Key Design Decisions

- **Wait-then-serve** approach for concurrent clients: the first requester downloads the file, other clients for the same URL wait on a `CountDownLatch`, then all read from the verified cached file. This is simpler and ensures checksum verification completes before any client receives data.
- **Semaphore** limits concurrent upstream downloads to `MAX_CONCURRENT_DOWNLOADS` (default: 4).
- **Atomic move** from `.tmp` to final cache path prevents serving partial or corrupt files.
- **Checksum resolution order**: local `checksums.properties` first, then remote `.sha256` file. If neither is available, verification is skipped with a warning.
- **Cleanup on failure**: temporary files are deleted if the download fails or checksum doesn't match.

## Running

```
mvn package && java -jar target/downsuma-1.0-SNAPSHOT.jar
```

Example request:
```
curl "http://localhost:8080/download?url=https://example.com/file.tar.gz" -o file.tar.gz
```
