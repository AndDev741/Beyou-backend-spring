package beyou.beyouapp.backend.controllers.docs;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.api.ApiControllerService;
import beyou.beyouapp.backend.docs.api.dto.ApiControllerDetailDTO;
import beyou.beyouapp.backend.docs.api.dto.ApiControllerListItemDTO;

@RestController
@RequestMapping(value = "/docs/api/controllers")
public class ApiDocsController {
    @Autowired
    private ApiControllerService controllerService;

    @GetMapping
    public List<ApiControllerListItemDTO> getControllers(@RequestParam(required = false) String locale) {
        return controllerService.getTopics(locale);
    }

    @GetMapping("/{key}")
    public ApiControllerDetailDTO getController(@PathVariable String key, @RequestParam(required = false) String locale) {
        return controllerService.getTopic(key, locale);
    }
}