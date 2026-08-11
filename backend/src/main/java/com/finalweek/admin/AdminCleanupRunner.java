package com.finalweek.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "finalweek.cleanup.enabled", havingValue = "true")
public class AdminCleanupRunner implements ApplicationRunner {
    private final AdminCleanupService cleanup; private final ObjectMapper mapper; private final ConfigurableApplicationContext context;
    public AdminCleanupRunner(AdminCleanupService cleanup, ObjectMapper mapper, ConfigurableApplicationContext context) {
        this.cleanup = cleanup; this.mapper = mapper; this.context = context;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = !args.containsOption("finalweek.cleanup.dry-run")
                || Boolean.parseBoolean(args.getOptionValues("finalweek.cleanup.dry-run").getFirst());
        System.out.println("FINALWEEK_CLEANUP_STATS=" + mapper.writeValueAsString(cleanup.run(dryRun)));
        SpringApplication.exit(context, () -> 0);
    }
}
