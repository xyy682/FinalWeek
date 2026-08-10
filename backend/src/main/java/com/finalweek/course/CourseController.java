package com.finalweek.course;

import com.finalweek.auth.FinalWeekPrincipal;
import com.finalweek.material.MaterialRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;
    private final MaterialRepository materialRepository;

    public CourseController(CourseService courseService, MaterialRepository materialRepository) {
        this.courseService = courseService;
        this.materialRepository = materialRepository;
    }

    @GetMapping
    List<CourseResponse> list(@AuthenticationPrincipal FinalWeekPrincipal principal) {
        return courseService.list(principal.userId()).stream().map(this::response).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CourseResponse create(
            @AuthenticationPrincipal FinalWeekPrincipal principal,
            @Valid @RequestBody CourseRequest request) {
        return response(courseService.create(principal.userId(), request.name()));
    }

    @GetMapping("/{courseId}")
    CourseResponse get(
            @AuthenticationPrincipal FinalWeekPrincipal principal,
            @PathVariable UUID courseId) {
        return response(courseService.get(principal.userId(), courseId));
    }

    @PatchMapping("/{courseId}")
    CourseResponse rename(
            @AuthenticationPrincipal FinalWeekPrincipal principal,
            @PathVariable UUID courseId,
            @Valid @RequestBody CourseRequest request) {
        return response(courseService.rename(principal.userId(), courseId, request.name()));
    }

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            @AuthenticationPrincipal FinalWeekPrincipal principal,
            @PathVariable UUID courseId) {
        courseService.delete(principal.userId(), courseId);
    }

    record CourseRequest(@NotBlank @Size(max = 100) String name) {}

    private CourseResponse response(Course course) {
        var recentStatus = materialRepository.findTopByCourse_IdAndDeletedFalseOrderByUpdatedAtDesc(course.getId())
                .map(material -> material.getStatus().name()).orElse(null);
        return new CourseResponse(course.getId(), course.getName(),
                materialRepository.countByCourse_IdAndDeletedFalse(course.getId()), recentStatus,
                course.getCreatedAt(), course.getUpdatedAt());
    }

    public record CourseResponse(UUID id, String name, long materialCount, String recentParseStatus,
                                 Instant createdAt, Instant updatedAt) {
    }
}
