package com.brimap.seed;

import com.brimap.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Seeds on startup: if-empty (default), always (wipe + reload), never. */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class SeedRunner implements ApplicationRunner {
    private final DataSeeder seeder;
    private final AppProperties props;
    private final com.brimap.audit.AuditService audit;

    @Override
    public void run(ApplicationArguments args) {
        String mode = props.seedMode() == null ? "if-empty" : props.seedMode();
        switch (mode) {
            case "always" -> {
                seeder.reset();
                audit.recordStandalone("system", "SEED", "system", "database", "Dataset loaded on startup (seed-mode=always)");
            }
            case "never" -> log.info("Seeding disabled (app.seed-mode=never)");
            default -> {
                if (seeder.isEmpty()) {
                    seeder.seed();
                    audit.recordStandalone("system", "SEED", "system", "database", "Dataset loaded on first startup");
                }
                else log.info("Database already seeded; set APP_SEED_MODE=always or POST /api/admin/reset to reload");
            }
        }
    }
}
