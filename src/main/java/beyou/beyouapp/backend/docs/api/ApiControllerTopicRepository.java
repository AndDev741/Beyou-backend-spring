package beyou.beyouapp.backend.docs.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import beyou.beyouapp.backend.docs.api.entity.ApiControllerTopic;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;

public interface ApiControllerTopicRepository extends JpaRepository<ApiControllerTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ApiControllerTopic> findAllByStatusOrderByOrderIndex(ApiControllerStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ApiControllerTopic> findByKey(String key);
}