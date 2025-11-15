package beyou.beyouapp.backend.seed;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
public class SeedOrchestrator implements CommandLineRunner {

    private final List<DatabaseSeeder> seeders;

    @Override
    public void run(String... args) throws Exception {

        for(DatabaseSeeder seeder : seeders){
            log.info("[SEEDER] Executing: {}", seeder.getName());
            seeder.seed();
        }

        log.info("[SEEDER] All seeders executed with success!");

    }
}
