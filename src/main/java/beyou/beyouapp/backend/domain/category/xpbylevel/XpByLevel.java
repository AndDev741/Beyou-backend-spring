package beyou.beyouapp.backend.domain.category.xpbylevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "xpByLevel")
public class XpByLevel {
    @Id
    @Column
    private int level;
    @Column
    private double xp;
}
