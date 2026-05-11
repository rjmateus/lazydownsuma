# Downsuma

A caching download proxy server. It exposes a single HTTP endpoint that streams files from an upstream URL to the client, caching them locally on first access. Concurrent requests for the same file are deduplicated — the upstream download happens only once and all waiting clients receive the stream in parallel.

## Requirements

- Java 17+
- Maven 3.6+

## Configuration

Key settings are defined as constants in `src/main/java/com/downsuma/Config.java`:

| Constant                  | Default         | Description                                          |
|---------------------------|-----------------|------------------------------------------------------|
| `PORT`                    | `8080`          | HTTP port the server listens on                      |
| `CACHE_DIR`               | `cache/`        | Local directory used to store cached files           |
| `CHECKSUMS_FILE`          | `checksums.properties` | Local file mapping upstream URLs to SHA-256 checksums |
| `MAX_CONCURRENT_DOWNLOADS`| `4`             | Maximum number of parallel upstream downloads        |
| `BUFFER_SIZE`             | `8192`          | Streaming buffer size in bytes                       |
| `DOWNLOAD_TIMEOUT_MINUTES`| `60`            | Timeout (minutes) waiting for a download to complete |

### Checksum verification

Checksums are verified after every download using one of two sources (in order):

1. **Local mapping file** (`checksums.properties`) — each line has the format:
   ```
   https\://example.com/path/to/file.rpm=<sha256hex>
   ```
2. **Remote `.sha256` file** — if no local entry exists, the server fetches `<url>.sha256` from the upstream server.

If verification fails the cached file is deleted and the client receives a `502` error.

## Build

Compile and package a self-contained (fat) JAR:

```bash
mvn clean package
```

The output JAR is placed at:

```
target/downsuma-1.0-SNAPSHOT.jar
```

## Run

```bash
java -jar target/downsuma-1.0-SNAPSHOT.jar
```

The server starts on port `8080` by default. The `cache/` directory and `checksums.properties` file are resolved relative to the **working directory** from which you launch the JAR.

## API

### `GET /download`

Downloads a file from the specified upstream URL, caching it locally.

**Query parameters**

| Parameter | Required | Description                    |
|-----------|----------|--------------------------------|
| `url`     | Yes      | Full URL of the file to download |

**Response**

- `200 OK` — file stream with `Content-Disposition: attachment; filename="<filename>"`
- `400 Bad Request` — missing `url` parameter
- `502 Bad Gateway` — upstream download failed or checksum mismatch
- `500 Internal Server Error` — unexpected server error

---

### Examples

**Download an RPM package:**

```bash
curl -O -J "http://localhost:8080/download?url=https://example.com/pub/repos/file.rpm"
```

**Download a tar archive and save with a custom name:**

```bash
curl -o myarchive.tar.gz "http://localhost:8080/download?url=https://example.com/releases/v1.0/app.tar.gz"
```

**Download using wget:**

```bash
wget "http://localhost:8080/download?url=https://example.com/path/to/file.iso"
```

**Check that the server returns an error for a missing `url` parameter:**

```bash
curl -i "http://localhost:8080/download"
# HTTP/1.1 400 Bad Request
# Missing required query parameter: url
```

**Check that the server returns an error when the upstream URL is unreachable:**

```bash
curl -i "http://localhost:8080/download?url=https://nonexistent.example.com/file.bin"
# HTTP/1.1 502 Bad Gateway
# Download failed: ...
```
