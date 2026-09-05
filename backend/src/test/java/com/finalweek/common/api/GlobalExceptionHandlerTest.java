package com.finalweek.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

    @Test
    void unexpectedFailureKeepsRequestIdInResponseAndLogsThrowable() {
        var request = new MockHttpServletRequest("POST", "/api/v1/courses/course-id/knowledge-versions");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "outline-regression-request-id");
        var exception = new IllegalStateException("serialization failed after task acceptance");
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new GlobalExceptionHandler().unexpected(exception, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().requestId()).isEqualTo("outline-regression-request-id");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains(
                        "POST", "/api/v1/courses/course-id/knowledge-versions", "outline-regression-request-id");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}