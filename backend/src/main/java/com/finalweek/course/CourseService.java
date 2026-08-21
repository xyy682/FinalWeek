package com.finalweek.course;

import com.finalweek.auth.UserAccountRepository;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.TaskStatus;
import com.finalweek.material.MaterialRepository;
import com.finalweek.material.MaterialStatus;
import com.finalweek.material.CourseSegmentRepository;
import com.finalweek.upload.ObjectStorage;
import com.finalweek.knowledge.KnowledgeCleanupService;
import com.finalweek.mockexam.MockExamCleanupService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserAccountRepository userAccountRepository;
    private final FinalWeekProperties properties;
    private final BackgroundTaskRepository tasks;
    private final MaterialRepository materials;
    private final CourseSegmentRepository segments;
    private final ObjectStorage storage;
    private final KnowledgeCleanupService knowledge;
    private final MockExamCleanupService mockExamCleanup;

    public CourseService(
            CourseRepository courseRepository,
            UserAccountRepository userAccountRepository,
            FinalWeekProperties properties, BackgroundTaskRepository tasks, MaterialRepository materials,
            CourseSegmentRepository segments, ObjectStorage storage, KnowledgeCleanupService knowledge,
            MockExamCleanupService mockExamCleanup) {
        this.courseRepository = courseRepository;
        this.userAccountRepository = userAccountRepository;
        this.properties = properties;
        this.tasks = tasks; this.materials = materials;
        this.segments = segments; this.storage = storage; this.knowledge = knowledge;
        this.mockExamCleanup = mockExamCleanup;
    }

    @Transactional(readOnly = true)
    public List<Course> list(UUID userId) {
        return courseRepository.findAllByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Course get(UUID userId, UUID courseId) {
        return courseRepository.findByIdAndUserIdAndDeletedFalse(courseId, userId).orElseThrow(this::notFound);
    }

    @Transactional
    public Course create(UUID userId, String name) {
        var user = userAccountRepository.findByIdForUpdate(userId).orElseThrow(this::notFound);
        if (courseRepository.countByUserIdAndDeletedFalse(userId) >= properties.limits().courseLimit()) {
            throw new BusinessException(HttpStatus.CONFLICT, "COURSE_LIMIT_REACHED",
                    "最多只能保留 " + properties.limits().courseLimit() + " 门课程");
        }
        return courseRepository.save(new Course(user, normalize(name)));
    }

    @Transactional
    public Course rename(UUID userId, UUID courseId, String name) {
        var course = courseRepository.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::notFound);
        course.rename(normalize(name));
        return courseRepository.save(course);
    }

    @Transactional
    public void delete(UUID userId, UUID courseId) {
        var course = courseRepository.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::notFound);
        var courseTasks = tasks.lockAllByCourseId(courseId);
        if (courseTasks.stream().anyMatch(task -> task.getStatus() == TaskStatus.PROCESSING
                || task.getStatus() == TaskStatus.RETRYING)) {
            throw new BusinessException(HttpStatus.CONFLICT, "COURSE_TASK_RUNNING", "课程仍有正在执行的任务");
        }
        courseTasks.forEach(task -> {
            if (tasks.cancelUnstarted(task.getId()) == 1 && task.getMaterialId() != null) {
                materials.updateStatus(task.getMaterialId(), MaterialStatus.CANCELLED);
            }
        });
        mockExamCleanup.prepareCourseDeletion(courseId);
        var courseMaterials = materials.findAllByCourse_IdAndDeletedFalse(courseId);
        courseMaterials.forEach(material -> {
            segments.deleteAllByMaterial_Id(material.getId());
            material.markDeleted();
        });
        materials.saveAll(courseMaterials);
        course.markDeleted();
        courseRepository.save(course);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                courseMaterials.forEach(material -> {
                    try { storage.delete(material.getObjectKey()); } catch (RuntimeException ignored) {}
                    try { storage.deletePrefix("derived/" + material.getId() + "/"); } catch (RuntimeException ignored) {}
                });
                knowledge.deleteCourse(courseId);
            }
        });
    }

    private String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在或无权访问");
    }
}
