package com.finalweek.mockexam;

import com.finalweek.upload.ObjectStorage;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MockExamCleanupService {
    private final MockExamObjectCleanupRepository cleanups;
    private final ObjectStorage storage;
    private final MockExamRepository exams;
    private final MockExamQuestionSourceRepository sources;
    private final TransactionTemplate transactions;
    private final MockExamProperties properties;
    public MockExamCleanupService(MockExamObjectCleanupRepository cleanups, ObjectStorage storage,
                                  MockExamRepository exams, MockExamQuestionSourceRepository sources,
                                  PlatformTransactionManager transactionManager, MockExamProperties properties) {
        this.cleanups = cleanups; this.storage = storage; this.exams = exams; this.sources = sources;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }
    @Transactional
    public void enqueue(MockExam exam) {
        enqueueLocked(exam, exam.getPaperObjectKey(), exam.getAnswerObjectKey());
    }
    @Transactional
    public void enqueue(MockExam exam, String paperKey, String answerKey) {
        enqueueLocked(exam, paperKey, answerKey);
    }
    @Transactional
    public void prepareCourseDeletion(UUID courseId) {
        exams.findAllByCourseId(courseId).forEach(exam -> {
            if (exam.getDeletedAt() == null) { exam.delete(); exams.save(exam); }
            enqueueLocked(exam, exam.getPaperObjectKey(), exam.getAnswerObjectKey());
        });
        sources.deleteAllByCourseId(courseId);
    }
    @Scheduled(fixedDelayString = "${finalweek.mock-exam.cleanup-interval:30s}")
    public void cleanDue() {
        transactions.executeWithoutResult(ignored -> enqueueDeletedCourses());
        while (true) {
            var value = transactions.execute(ignored -> claim()); if (value == null) return;
            try { delete(value.getPaperObjectKey()); delete(value.getAnswerObjectKey());
                transactions.executeWithoutResult(ignored -> complete(value.getId())); }
            catch (RuntimeException exception) { transactions.executeWithoutResult(ignored -> fail(value.getId(), exception.getMessage())); }
        }
    }
    private void enqueueDeletedCourses() {
        exams.findCleanupCandidatesForDeletedCourses(PageRequest.of(0, 100)).forEach(exam -> {
            exam.delete(); exams.save(exam);
            enqueueLocked(exam, exam.getPaperObjectKey(), exam.getAnswerObjectKey());
        });
    }
    private void enqueueLocked(MockExam exam, String paperKey, String answerKey) {
        var existing = cleanups.findByMockExamIdForUpdate(exam.getId());
        if (existing.isEmpty()) {
            cleanups.save(new MockExamObjectCleanup(exam, paperKey, answerKey));
            return;
        }
        var cleanup = existing.get();
        if (cleanup.getStatus() == MockExamCleanupStatus.SUCCEEDED
                || cleanup.getStatus() == MockExamCleanupStatus.FAILED) {
            cleanup.requeue(paperKey, answerKey);
            cleanups.save(cleanup);
        }
    }
    protected MockExamObjectCleanup claim() {
        var values = cleanups.findDueForUpdate(Instant.now(), PageRequest.of(0, 1));
        if (values.isEmpty()) return null; var value = values.get(0); value.claim(); return cleanups.save(value);
    }
    protected void complete(UUID id) { cleanups.findById(id).ifPresent(value -> {
        var exam = value.getMockExam();
        if (exam.getDeletedAt() == null) { value.succeed(); cleanups.save(value); return; }
        exams.detachRetriesOf(exam.getId());
        cleanups.delete(value);
        exams.delete(exam);
    }); }
    protected void fail(UUID id, String error) { cleanups.findById(id).ifPresent(value -> {
        value.retry(error, properties.cleanupMaxAttempts()); cleanups.save(value); }); }
    private void delete(String key) { if (key != null && !key.isBlank()) storage.delete(key); }
}
