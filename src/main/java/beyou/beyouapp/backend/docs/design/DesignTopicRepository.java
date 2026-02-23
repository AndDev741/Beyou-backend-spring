package beyou.beyouapp.backend.docs.design;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import beyou.beyouapp.backend.docs.design.entity.DesignTopic;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;

public interface DesignTopicRepository extends JpaRepository<DesignTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<DesignTopic> findAllByStatusOrderByOrderIndex(DesignTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<DesignTopic> findByKey(String key);

    @EntityGraph(attributePaths = "contents")
    @Query("SELECT DISTINCT t FROM DesignTopic t JOIN t.contents c WHERE t.status = :status AND c.locale = :locale AND (c.title ILIKE %:query% OR c.summary ILIKE %:query%)")
    List<DesignTopic> searchByLocaleAndQuery(@Param("status") DesignTopicStatus status, @Param("locale") String locale, @Param("query") String query);
}
