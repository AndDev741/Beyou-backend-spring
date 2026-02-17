package beyou.beyouapp.backend.docs.architecture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;

public interface ArchitectureTopicRepository extends JpaRepository<ArchitectureTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ArchitectureTopic> findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ArchitectureTopic> findByKey(String key);
}
