package com.finalweek.upload;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ObjectStorage {
    void put(String objectKey, InputStream input, long size, String contentType);
    void put(String objectKey, Path file, String contentType);
    InputStream get(String objectKey);
    String presignedGet(String objectKey, Duration ttl);
    void delete(String objectKey);
    void deletePrefix(String prefix);
    List<String> listKeys(String prefix);
}
