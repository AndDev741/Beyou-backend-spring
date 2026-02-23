package beyou.beyouapp.backend.docs.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import beyou.beyouapp.backend.docs.api.entity.ApiControllerTopic;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;

public interface ApiControllerTopicRepository extends JpaRepository<ApiControllerTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ApiControllerTopic> findAllByStatusOrderByOrderIndex(ApiControllerStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ApiControllerTopic> findByKey(String key);

    @EntityGraph(attributePaths = "contents")
    @Query("SELECT DISTINCT t FROM ApiControllerTopic t JOIN t.contents c WHERE t.status = :status AND c.locale = :locale AND (c.title ILIKE %:query% OR c.summary ILIKE %:query%)")
    List<ApiControllerTopic> searchByLocaleAndQuery(@Param("status") ApiControllerStatus status, @Param("locale") String locale, @Param("query") String query);
}