package beyou.beyouapp.backend.controllers.docs;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.blog.BlogTopicService;
import beyou.beyouapp.backend.docs.blog.dto.BlogTopicDetailDTO;
import beyou.beyouapp.backend.docs.blog.dto.BlogTopicListItemDTO;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(value = "/docs/blog")
@RequiredArgsConstructor
public class BlogDocsController {

    private final BlogTopicService topicService;

    @GetMapping("/topics")
    public List<BlogTopicListItemDTO> getTopics(
        @RequestParam(required = false) String locale,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String tag
    ) {
        return topicService.getTopics(locale, category, tag);
    }

    @GetMapping("/topics/{key}")
    public BlogTopicDetailDTO getTopic(@PathVariable String key, @RequestParam(required = false) String locale) {
        return topicService.getTopic(key, locale);
    }
}
