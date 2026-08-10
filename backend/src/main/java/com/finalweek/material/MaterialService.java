package com.finalweek.material;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseService;
import com.finalweek.upload.ObjectStorage;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MaterialService {
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private final MaterialRepository repository;
    private final CourseService courseService;
    private final ObjectStorage storage;

    public MaterialService(MaterialRepository repository, CourseService courseService, ObjectStorage storage) {
        this.repository = repository;
        this.courseService = courseService;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<Material> list(UUID userId, UUID courseId) {
        courseService.get(userId, courseId);
        return repository.findAllByCourse_IdAndCourse_User_IdAndDeletedFalseOrderByCreatedAtDesc(courseId, userId);
    }

    @Transactional(readOnly = true)
    public Material get(UUID userId, UUID materialId) {
        return repository.findByIdAndCourse_User_IdAndDeletedFalse(materialId, userId).orElseThrow(this::notFound);
    }

    @Transactional
    public void delete(UUID userId, UUID materialId) {
        var material = repository.findOwnedByIdForUpdate(materialId, userId).orElseThrow(this::notFound);
        switch (material.getStatus()) {
            case SUCCEEDED -> throw new BusinessException(HttpStatus.CONFLICT, "MATERIAL_DELETE_FORBIDDEN",
                    "解析成功的资料只能随整门课程删除");
            case PROCESSING, RETRYING, QUEUED -> throw new BusinessException(HttpStatus.CONFLICT,
                    "TASK_NOT_CANCELLABLE", "当前任务状态暂不允许删除资料");
            default -> {
                material.markDeleted();
                var objectKey = material.getObjectKey();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { storage.delete(objectKey); }
                        catch (RuntimeException exception) {
                            log.warn("Material row deleted but object cleanup needs reconciliation materialId={}",
                                    materialId, exception);
                        }
                    }
                });
            }
        }
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在或无权访问");
    }
}
