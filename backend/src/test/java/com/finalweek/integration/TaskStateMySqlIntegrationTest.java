package com.finalweek.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TaskStateMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("finalweek_it").withUsername("finalweek").withPassword("finalweek_test");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @Test
    void competingConsumerAndCancellationUseSingleDatabaseWinnerAndCheckpointIsUnique() throws Exception {
        var userId = UUID.randomUUID(); var courseId = UUID.randomUUID();
        var materialId = UUID.randomUUID(); var taskId = UUID.randomUUID();
        seed(userId, courseId, materialId, taskId);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claim = () -> update("update background_task set status='PROCESSING', " +
                    "delivery_attempt_count=delivery_attempt_count+1, processing_owner='consumer-a', " +
                    "processing_lease_until=date_add(current_timestamp(6), interval 5 minute) " +
                    "where id=? and execution_round=0 and delivery_attempt_count < 3 and status in " +
                    "('PENDING_PUBLISH','PUBLISH_FAILED','QUEUED','RETRYING')", taskId);
            var first = executor.submit(claim); var second = executor.submit(claim);
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally { executor.shutdownNow(); }

        assertThat(update("update background_task set status='CANCELLED' where id=? and status='QUEUED'", taskId)).isZero();
        assertThat(queryInt("select delivery_attempt_count from background_task where id=?", taskId)).isOne();
        assertThat(queryString("select processing_owner from background_task where id=?", taskId))
                .isEqualTo("consumer-a");

        assertThat(update("update background_task set processing_lease_until=date_sub(current_timestamp(6), interval 1 second) " +
                "where id=?", taskId)).isOne();
        var recovery = "update background_task set status='PENDING_PUBLISH', processing_owner=null, " +
                "processing_lease_until=null where id=? and execution_round=0 and status='PROCESSING' " +
                "and processing_owner='consumer-a' and processing_lease_until <= current_timestamp(6)";
        assertThat(update(recovery, taskId)).isOne();
        assertThat(update(recovery, taskId)).isZero();
        assertThat(queryString("select status from background_task where id=?", taskId))
                .isEqualTo("PENDING_PUBLISH");

        insertCheckpoint(taskId, UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertCheckpoint(taskId, UUID.randomUUID()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void targetMigrationsCreateMockExamSnapshotAndCleanupSchema() throws Exception {
        assertThat(queryScalar("select count(*) from information_schema.tables where table_schema=database() " +
                "and table_name in ('mock_exam','mock_exam_question','mock_exam_question_source','mock_exam_object_cleanup')"))
                .isEqualTo(4);
        assertThat(queryScalar("select count(*) from information_schema.columns where table_schema=database() " +
                "and table_name='mock_exam' and column_name in " +
                "('knowledge_version_id','quality_policy_version','history_similarity_threshold','error_message'," +
                "'pdf_template_version','formula_policy_version','paper_object_key','answer_object_key')")).isEqualTo(8);
        assertThat(queryScalar("select count(*) from information_schema.columns where table_schema=database() " +
                "and table_name='background_task' and column_name in ('processing_owner','processing_lease_until')"))
                .isEqualTo(2);
        assertThat(queryScalar("select count(*) from flyway_schema_history where success=true")).isEqualTo(15);
    }

    private static void seed(UUID userId, UUID courseId, UUID materialId, UUID taskId) throws Exception {
        try (var connection = connection()) {
            var now = java.sql.Timestamp.from(Instant.now());
            try (var sql = connection.prepareStatement("insert into user_account(id,email,status,created_at,updated_at) values(?,?,?,?,?)")) {
                sql.setBytes(1, bytes(userId)); sql.setString(2, userId + "@example.com"); sql.setString(3, "ACTIVE");
                sql.setTimestamp(4, now); sql.setTimestamp(5, now); sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("insert into course(id,user_id,name,deleted,outline_generation_seq,created_at,updated_at) values(?,?,?,false,0,?,?)")) {
                sql.setBytes(1, bytes(courseId)); sql.setBytes(2, bytes(userId)); sql.setString(3, "Integration");
                sql.setTimestamp(4, now); sql.setTimestamp(5, now); sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("insert into material(id,course_id,original_filename,object_key,content_hash,size_bytes,media_type,material_type,status,deleted,created_at,updated_at) values(?,?,?,?,?,1,'text/plain','NOTES','QUEUED',false,?,?)")) {
                sql.setBytes(1, bytes(materialId)); sql.setBytes(2, bytes(courseId)); sql.setString(3, "it.txt");
                sql.setString(4, "materials/it/original"); sql.setString(5, "a".repeat(64));
                sql.setTimestamp(6, now); sql.setTimestamp(7, now); sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("insert into background_task(id,user_id,course_id,material_id,business_id,task_type,visible_in_global_drawer,status,current_stage,publish_attempt_count,delivery_attempt_count,api_attempt_count,manual_retry_count,execution_round,business_key,created_at,updated_at) values(?,?,?,?,?,'PARSE_MATERIAL',true,'QUEUED','UPLOADED',1,0,0,0,0,?,?,?)")) {
                sql.setBytes(1, bytes(taskId)); sql.setBytes(2, bytes(userId)); sql.setBytes(3, bytes(courseId));
                sql.setBytes(4, bytes(materialId)); sql.setBytes(5, bytes(materialId));
                sql.setString(6, "PARSE_MATERIAL:" + materialId);
                sql.setTimestamp(7, now); sql.setTimestamp(8, now); sql.executeUpdate();
            }
        }
    }

    private static void insertCheckpoint(UUID taskId, UUID checkpointId) throws Exception {
        try (var connection = connection(); var sql = connection.prepareStatement("insert into task_checkpoint(id,task_id,stage,status,completed_at) values(?,?,'UPLOADED','COMPLETED',?)")) {
            sql.setBytes(1, bytes(checkpointId)); sql.setBytes(2, bytes(taskId));
            sql.setTimestamp(3, java.sql.Timestamp.from(Instant.now())); sql.executeUpdate();
        }
    }
    private static int update(String statement, UUID id) throws Exception { try (var connection = connection(); var sql = connection.prepareStatement(statement)) {
        sql.setBytes(1, bytes(id)); return sql.executeUpdate(); } }
    private static int queryInt(String statement, UUID id) throws Exception { try (var connection = connection(); var sql = connection.prepareStatement(statement)) {
        sql.setBytes(1, bytes(id)); try (var result = sql.executeQuery()) { result.next(); return result.getInt(1); } } }
    private static String queryString(String statement, UUID id) throws Exception {
        try (var connection = connection(); var sql = connection.prepareStatement(statement)) {
            sql.setBytes(1, bytes(id));
            try (var result = sql.executeQuery()) { result.next(); return result.getString(1); }
        }
    }
    private static int queryScalar(String statement) throws Exception { try (var connection = connection(); var sql = connection.prepareStatement(statement);
            var result = sql.executeQuery()) { result.next(); return result.getInt(1); } }
    private static Connection connection() throws SQLException { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
