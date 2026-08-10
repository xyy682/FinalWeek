package com.finalweek.common.system;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    Map<String, Object> ping() {
        return Map.of("status", "ok", "service", "finalweek-backend", "time", Instant.now());
    }
}

