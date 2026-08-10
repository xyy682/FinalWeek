package com.finalweek.upload;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final StorageProperties properties;

    public MinioObjectStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
        } catch (Exception exception) {
            throw new StorageOperationException("无法初始化 MinIO bucket", exception);
        }
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(objectKey)
                    .stream(input, size, null).contentType(contentType).build());
        } catch (Exception exception) {
            throw new StorageOperationException("MinIO 写入失败", exception);
        }
    }

    @Override
    public void put(String objectKey, Path file, String contentType) {
        try (var input = Files.newInputStream(file)) {
            put(objectKey, input, Files.size(file), contentType);
        } catch (IOException exception) {
            throw new StorageOperationException("临时文件读取失败", exception);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new StorageOperationException("MinIO 读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new StorageOperationException("MinIO 删除失败", exception);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        try {
            var objects = client.listObjects(ListObjectsArgs.builder().bucket(properties.bucket())
                    .prefix(prefix).recursive(true).build());
            for (var result : objects) {
                delete(result.get().objectName());
            }
        } catch (Exception exception) {
            throw new StorageOperationException("MinIO 前缀清理失败", exception);
        }
    }
}
