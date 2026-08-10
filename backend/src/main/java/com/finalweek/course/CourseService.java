package com.finalweek.course;

import com.finalweek.auth.UserAccountRepository;
import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserAccountRepository userAccountRepository;
    private final FinalWeekProperties properties;

    public CourseService(
            CourseRepository courseRepository,
            UserAccountRepository userAccountRepository,
            FinalWeekProperties properties) {
        this.courseRepository = courseRepository;
        this.userAccountRepository = userAccountRepository;
        this.properties = properties;
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
        return course;
    }

    @Transactional
    public void delete(UUID userId, UUID courseId) {
        var course = courseRepository.findOwnedByIdForUpdate(courseId, userId).orElseThrow(this::notFound);
        // Phase 4 extends this transaction with parse-task row locks and state checks.
        course.markDeleted();
    }

    private String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在或无权访问");
    }
}

