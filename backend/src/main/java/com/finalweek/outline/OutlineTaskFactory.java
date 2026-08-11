package com.finalweek.outline;

import com.finalweek.common.api.BusinessException;
import com.finalweek.course.CourseRepository;
import com.finalweek.task.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutlineTaskFactory {
    static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING_PUBLISH, TaskStatus.PUBLISH_FAILED,
            TaskStatus.QUEUED, TaskStatus.PROCESSING, TaskStatus.RETRYING);
    private final CourseRepository courses;
    private final ParseTaskRepository tasks;
    public OutlineTaskFactory(CourseRepository courses, ParseTaskRepository tasks) {
        this.courses = courses; this.tasks = tasks;
    }
    @Transactional
    public CreatedTask create(UUID userId, UUID courseId) {
        var course = courses.findOwnedByIdForUpdate(courseId, userId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在或无权访问"));
        var active = tasks.findFirstByCourseIdAndTaskTypeAndStatusInOrderByCreatedAtDesc(
                courseId, TaskType.GENERATE_OUTLINE, ACTIVE);
        if (active.isPresent()) return new CreatedTask(active.get(), false);
        var task = tasks.save(new ParseTask(userId, courseId, course.nextOutlineGeneration()));
        courses.save(course); tasks.flush();
        return new CreatedTask(task, true);
    }
    public record CreatedTask(ParseTask task, boolean created) {}
}
