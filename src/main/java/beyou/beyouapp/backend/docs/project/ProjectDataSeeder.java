package beyou.beyouapp.backend.docs.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import beyou.beyouapp.backend.docs.project.entity.ProjectTopic;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicContent;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectDataSeeder implements CommandLineRunner {
    private final ProjectTopicRepository topicRepository;

    @Override
    public void run(String... args) throws Exception {
        if (topicRepository.count() > 0) {
            log.info("Project topics already exist, skipping seed.");
            return;
        }

        Path projectsRoot = Paths.get("beyou-arch-design/projects");
        if (!Files.exists(projectsRoot)) {
            log.warn("Projects root directory not found: {}", projectsRoot.toAbsolutePath());
            return;
        }

        try (var dirStream = Files.newDirectoryStream(projectsRoot)) {
            for (Path projectDir : dirStream) {
                if (Files.isDirectory(projectDir)) {
                    seedProject(projectDir);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read projects directory", e);
        }

        log.info("Project data seeding completed.");
    }

    private void seedProject(Path projectDir) {
        String key = projectDir.getFileName().toString();
        Path topicYaml = projectDir.resolve("topic.yaml");
        if (!Files.exists(topicYaml)) {
            log.warn("Missing topic.yaml in {}", projectDir);
            return;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> topicData = yaml.load(Files.readString(topicYaml));
            String keyFromYaml = (String) topicData.get("key");
            if (keyFromYaml != null && !keyFromYaml.equals(key)) {
                log.warn("Key mismatch: directory={}, yaml={}", key, keyFromYaml);
            }

            int orderIndex = (Integer) topicData.getOrDefault("orderIndex", 1);
            String statusStr = (String) topicData.getOrDefault("status", "ACTIVE");
            ProjectTopicStatus status = ProjectTopicStatus.valueOf(statusStr.toUpperCase());

            String designTopicKey = (String) topicData.get("designTopicKey");
            String architectureTopicKey = (String) topicData.get("architectureTopicKey");
            String repositoryUrl = (String) topicData.get("repositoryUrl");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) topicData.get("tags");

            Optional<ProjectTopic> existing = topicRepository.findByKey(key);
            ProjectTopic topic;
            if (existing.isPresent()) {
                topic = existing.get();
                topic.setOrderIndex(orderIndex);
                topic.setStatus(status);
            } else {
                topic = new ProjectTopic();
                topic.setId(UUID.randomUUID());
                topic.setKey(key);
                topic.setOrderIndex(orderIndex);
                topic.setStatus(status);
                topic.setCreatedAt(Date.valueOf(LocalDate.now()));
                topic.setUpdatedAt(Date.valueOf(LocalDate.now()));
            }

            // Process markdown files
            seedContent(topic, projectDir, "en.md", "en", designTopicKey, architectureTopicKey, repositoryUrl, tags);
            seedContent(topic, projectDir, "pt.md", "pt", designTopicKey, architectureTopicKey, repositoryUrl, tags);

            topicRepository.save(topic);
            log.info("Seeded project topic: {}", key);
        } catch (Exception e) {
            log.error("Failed to seed project {}", key, e);
        }
    }

    private void seedContent(ProjectTopic topic, Path projectDir, String filename, String locale,
                             String designTopicKey, String architectureTopicKey, String repositoryUrl, List<String> tags) {
        Path mdFile = projectDir.resolve(filename);
        if (!Files.exists(mdFile)) {
            return;
        }

        try {
            String markdown = Files.readString(mdFile).trim();
            String title = extractTitle(markdown);
            String summary = extractSummary(markdown);

            ProjectTopicContent content = topic.findContentByLocale(locale).orElse(null);
            if (content == null) {
                content = new ProjectTopicContent();
                content.setId(UUID.randomUUID());
                content.setTopic(topic);
                content.setLocale(locale);
                topic.getContents().add(content);
            }
            content.setTitle(title);
            content.setSummary(summary);
            content.setDocMarkdown(markdown);

            Path diagramFile = projectDir.resolve("diagram.mmd");
            if (Files.exists(diagramFile)) {
                content.setDiagramMermaid(Files.readString(diagramFile).trim());
            }

            content.setDesignTopicKey(designTopicKey);
            content.setArchitectureTopicKey(architectureTopicKey);
            content.setRepositoryUrl(repositoryUrl);
            content.setTags(tagsToJson(tags));
            content.setUpdatedAt(Date.valueOf(LocalDate.now()));
        } catch (IOException e) {
            log.error("Failed to read markdown file {}", mdFile, e);
        }
    }

    private String tagsToJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(tags.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractTitle(String markdown) {
        // First line starting with # is the title
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }

    private String extractSummary(String markdown) {
        // First paragraph after title (until empty line)
        String[] lines = markdown.split("\n");
        boolean afterTitle = false;
        StringBuilder summary = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("# ")) {
                afterTitle = true;
                continue;
            }
            if (afterTitle) {
                if (line.isEmpty()) {
                    break;
                }
                summary.append(line).append(" ");
            }
        }
        return summary.toString().trim();
    }
}