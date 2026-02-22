package beyou.beyouapp.backend.docs.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import beyou.beyouapp.backend.docs.project.entity.ProjectTopic;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;

public interface ProjectTopicRepository extends JpaRepository<ProjectTopic, UUID> {
    @EntityGraph(attributePaths = "contents")
    List<ProjectTopic> findAllByStatusOrderByOrderIndex(ProjectTopicStatus status);

    @EntityGraph(attributePaths = "contents")
    Optional<ProjectTopic> findByKey(String key);
}