package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/category")
public class CategoryController {
    @Autowired
    CategoryService categoryService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    @GetMapping("")
    public List<CategoryResponseDTO> getCategories(){
        //Every user are in a separated thread on Spring
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return categoryService.getAllCategories(userAuth.getId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCategory(@RequestBody @Valid CategoryRequestDTO categoryRequestDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return categoryService.createCategory(categoryRequestDTO, userAuth.getId());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> editCategory(@RequestBody CategoryEditRequestDTO categoryEditRequestDTO){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return categoryService.editCategory(categoryEditRequestDTO, userAuth.getId());
    }

    @DeleteMapping(value = "/{categoryId}")
    public ResponseEntity<Map<String, String>> deleteCategory(@PathVariable String categoryId){
        User userAuth = authenticatedUser.getAuthenticatedUser();
        return categoryService.deleteCategory(categoryId, userAuth.getId());
    }
}
