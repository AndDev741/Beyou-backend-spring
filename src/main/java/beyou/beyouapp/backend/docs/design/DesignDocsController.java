package beyou.beyouapp.backend.docs.design;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.design.dto.DesignTopicDetailDTO;
import beyou.beyouapp.backend.docs.design.dto.DesignTopicListItemDTO;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(value = "/docs/design")
@RequiredArgsConstructor
public class DesignDocsController {

    private final DesignTopicService topicService;

    @GetMapping("/topics")
    public List<DesignTopicListItemDTO> getTopics(@RequestParam(required = false) String locale) {
        return topicService.getTopics(locale);
    }

    @GetMapping("/topics/{key}")
    public DesignTopicDetailDTO getTopic(@PathVariable String key, @RequestParam(required = false) String locale) {
        return topicService.getTopic(key, locale);
    }
}
