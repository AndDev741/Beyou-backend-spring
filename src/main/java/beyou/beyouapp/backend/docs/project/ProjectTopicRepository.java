package beyou.beyouapp.backend.docs.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import beyou.beyouapp.backend.docs.project.entity.ProjectTopic;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;

public interface ProjectTopicRepository extends JpaRepository<ProjectTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ProjectTopic> findAllByStatusOrderByOrderIndex(ProjectTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ProjectTopic> findByKey(String key);

    @EntityGraph(attributePaths = "contents")
    @Query("SELECT DISTINCT t FROM ProjectTopic t JOIN t.contents c WHERE t.status = :status AND c.locale = :locale AND (c.title ILIKE %:query% OR c.summary ILIKE %:query%)")
    List<ProjectTopic> searchByLocaleAndQuery(@Param("status") ProjectTopicStatus status, @Param("locale") String locale, @Param("query") String query);
}