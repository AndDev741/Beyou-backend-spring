package beyou.beyouapp.backend.seed.seeders;

import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.seed.DatabaseSeeder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
@Slf4j
public class XpByLevelSeeder implements DatabaseSeeder {

    private final XpByLevelRepository xpByLevelRepository;

    @Override
    public void seed() {

        if(xpByLevelRepository.count() != 0){
            log.info("XpByLevel already generated, skipping...");
            return;
        }

        log.info("Generating xp by levels...");
        int MAX_LEVEL = 100;
        double xp = 0;
        double multFactor = 0.5;

        for (int level = 0; level <= MAX_LEVEL; level++) {
            if(level > 10){
                multFactor = 0.2;
            }
            if (level > 30) {
                multFactor = 0.3;
            }

            XpByLevel xpByLevel = new XpByLevel(level, xp);
            xpByLevelRepository.save(xpByLevel);
            xp = xp + ((level + 1) * 100 * multFactor);

        }

    }

    @Override
    public String getName() {
        return "XpByLevelSeeder";
    }
}
