package beyou.beyouapp.backend.docs.architecture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;

public interface ArchitectureTopicRepository extends JpaRepository<ArchitectureTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ArchitectureTopic> findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ArchitectureTopic> findByKey(String key);

    @EntityGraph(attributePaths = "contents")
    @Query("SELECT DISTINCT t FROM ArchitectureTopic t JOIN t.contents c WHERE t.status = :status AND c.locale = :locale AND (c.title ILIKE %:query% OR c.summary ILIKE %:query%)")
    List<ArchitectureTopic> searchByLocaleAndQuery(@Param("status") ArchitectureTopicStatus status, @Param("locale") String locale, @Param("query") String query);
}
