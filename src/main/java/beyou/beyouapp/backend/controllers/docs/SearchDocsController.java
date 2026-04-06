package beyou.beyouapp.backend.controllers.docs;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.search.SearchService;
import beyou.beyouapp.backend.docs.search.dto.SearchRequestDTO;
import beyou.beyouapp.backend.docs.search.dto.SearchResultDTO;
import jakarta.validation.constraints.Max;

@RestController
@RequestMapping(value = "/docs/search")
@Validated
public class SearchDocsController {

    @Autowired
    private SearchService searchService;

    @GetMapping
    public List<SearchResultDTO> search(
        @RequestParam String q,
        @RequestParam(required = false) String locale,
        @RequestParam(required = false, defaultValue = "all") String category,
        @RequestParam(required = false, defaultValue = "10") @Max(100) Integer limit,
        @RequestParam(required = false, defaultValue = "0") Integer offset
    ) {
        SearchRequestDTO request = new SearchRequestDTO(q, locale, category, limit, offset);
        return searchService.search(request);
    }
}