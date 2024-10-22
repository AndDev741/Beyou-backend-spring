package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelAlgorithm;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping(value = "/category")
public class CategoryController {
    @Autowired
    CategoryService categoryService;

    @Autowired
    XpByLevelAlgorithm xpByLevelAlgorithm;

    @GetMapping("/{userId}")
    public ArrayList<Category> getCategories(@PathVariable String userId){
        return categoryService.getAllCategories(userId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCategory(@RequestBody @Valid CategoryRequestDTO categoryRequestDTO){
        return categoryService.createCategory(categoryRequestDTO);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> editCategory(@RequestBody CategoryEditRequestDTO categoryEditRequestDTO){
        return categoryService.editCategory(categoryEditRequestDTO);
    }

    @DeleteMapping(value = "/{categoryId}")
    public ResponseEntity<Map<String, String>> deleteCategory(@PathVariable String categoryId){
        return categoryService.deleteCategory(categoryId);
    }

    @PostMapping(value = "/execute")
    public void executeLevelAlgorithm(){
        xpByLevelAlgorithm.runXpAlgorithm();
    }
}
