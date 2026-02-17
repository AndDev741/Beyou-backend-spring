package beyou.beyouapp.backend.docs.design;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import beyou.beyouapp.backend.docs.design.entity.DesignTopic;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;

public interface DesignTopicRepository extends JpaRepository<DesignTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<DesignTopic> findAllByStatusOrderByOrderIndex(DesignTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<DesignTopic> findByKey(String key);
}
