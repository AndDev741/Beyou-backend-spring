package beyou.beyouapp.backend.domain.category.xpbylevel;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Service
public class XpByLevelAlgorithm {

    @Autowired
    private XpByLevelRepository xpByLevelRepository;

    public void runXpAlgorithm() {
        int MAX_LEVEL = 100;
        double xp = 0;
        double multFactor = 0.5;

        for (int level = 0; level <= MAX_LEVEL; level++) {
            if (level > 10) {
                multFactor = 0.3;
            }else if(level > 30){
                multFactor = 0.2;
            }

            XpByLevel xpByLevel = new XpByLevel(level, xp);
            xpByLevelRepository.save(xpByLevel);
            xp = xp + ((level + 1) * 100 * multFactor);

        }
    }
}
