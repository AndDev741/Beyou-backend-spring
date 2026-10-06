package beyou.beyouapp.backend.domain.notebook.study;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookStudyOutputRepository extends JpaRepository<NotebookStudyOutput, UUID> {

    List<NotebookStudyOutput> findByPageIdAndKindNotOrderByCreatedAtDesc(UUID pageId, StudyOutputKind kind);

    Optional<NotebookStudyOutput> findFirstByPageIdAndKindOrderByCreatedAtDesc(UUID pageId, StudyOutputKind kind);

    void deleteByPageIdAndKind(UUID pageId, StudyOutputKind kind);
}
