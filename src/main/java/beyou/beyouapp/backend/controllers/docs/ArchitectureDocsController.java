package beyou.beyouapp.backend.controllers.docs;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicService;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicDetailDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicListItemDTO;

@RestController
@RequestMapping(value = "/docs/architecture")
public class ArchitectureDocsController {
    @Autowired
    private ArchitectureTopicService topicService;

    @GetMapping("/topics")
    public List<ArchitectureTopicListItemDTO> getTopics(@RequestParam(required = false) String locale) {
        return topicService.getTopics(locale);
    }

    @GetMapping("/topics/{key}")
    public ArchitectureTopicDetailDTO getTopic(@PathVariable String key, @RequestParam(required = false) String locale) {
        return topicService.getTopic(key, locale);
    }
}
