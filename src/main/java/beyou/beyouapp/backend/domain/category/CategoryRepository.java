package beyou.beyouapp.backend.domain.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<ArrayList<Category>> findAllByUserId(UUID userId);
}
