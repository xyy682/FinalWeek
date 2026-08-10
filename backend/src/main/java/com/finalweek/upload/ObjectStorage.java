package com.finalweek.upload;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorage {
    void put(String objectKey, InputStream input, long size, String contentType);
    void put(String objectKey, Path file, String contentType);
    InputStream get(String objectKey);
    void delete(String objectKey);
    void deletePrefix(String prefix);
}
