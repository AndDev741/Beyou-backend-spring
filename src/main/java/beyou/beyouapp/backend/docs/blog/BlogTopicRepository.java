package beyou.beyouapp.backend.docs.blog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import beyou.beyouapp.backend.docs.blog.entity.BlogTopic;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicStatus;

public interface BlogTopicRepository extends JpaRepository<BlogTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    @Query("SELECT t FROM BlogTopic t WHERE t.status = :status ORDER BY COALESCE(t.publishedAt, t.createdAt) DESC")
    List<BlogTopic> findAllByStatusOrderByPublishedDesc(@Param("status") BlogTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<BlogTopic> findByKey(String key);

    @EntityGraph(attributePaths = "contents")
    @Query("SELECT DISTINCT t FROM BlogTopic t JOIN t.contents c WHERE t.status = :status AND c.locale = :locale AND (c.title ILIKE %:query% OR c.summary ILIKE %:query%)")
    List<BlogTopic> searchByLocaleAndQuery(@Param("status") BlogTopicStatus status, @Param("locale") String locale, @Param("query") String query);
}
