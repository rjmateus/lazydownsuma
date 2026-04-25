Generate a web application that allows to download files from this web application server.
The web application must use the spark framework from: https://github.com/perwendel/spark
It should have just one endpoint to download files from.

The files can be stored in a server folder, which is its cache.
If the server has the file cached locally it serves the file directly.
If the file is not present in the cache, it must download it from upstream, and store in the cache.

The application can receive multiple requests for the same file at the same time from different clients. In those cases, it must only download the file from upstream once.
The server must stream the file to clients as chunks arrive from upstream, rather than waiting for the full download to complete. When multiple clients request the same file concurrently, all clients receive the stream in parallel from a single upstream download.

## URL Mapping
The client specifies the upstream file URL as a query parameter. For example:
`GET /download?url=https://example.com/path/to/file.tar.gz`

## Checksum Verification
File checksum must be verified after download. Two checksum sources are supported:
1. **Checksum mapping file**: A local configuration file (e.g., `checksums.properties`) that maps upstream URLs to their expected SHA-256 checksums. This is useful for testing and for known files.
2. **Remote .sha256 file**: If no entry exists in the mapping file, the server fetches a `.sha256` file from upstream by appending `.sha256` to the original URL (e.g., `https://example.com/file.tar.gz.sha256`).

If checksum verification fails, the cached file must be deleted and the client must receive an error.

## Error Handling
If the upstream download fails mid-stream, all clients currently receiving that file must receive an error and the download fails. The partially cached file must be cleaned up.

## Cache Policy
The cache grows unbounded. No eviction or TTL is implemented at this stage.

## Concurrency
There must be a configurable limit (Java constant) on the maximum number of concurrent upstream downloads to avoid overwhelming the upstream server.

## Security
File path traversal protection is not required at this stage.

## Technology
It must be developed in Java, and using Maven.
The upstream site can be configured in a Java constant, for example.
Standard `java.util.concurrent` for deduplication of concurrent requests. OkHttp or Apache HttpClient for upstream downloads with streaming support.