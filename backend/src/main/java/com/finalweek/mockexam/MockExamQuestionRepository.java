package com.finalweek.mockexam;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MockExamQuestionRepository extends JpaRepository<MockExamQuestion, UUID> {
    List<MockExamQuestion> findAllByMockExam_IdOrderByPosition(UUID mockExamId);
}
