package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CategoryService {
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private XpByLevelRepository xpByLevelRepository;

    @Autowired
    private UserRepository userRepository;

    public Category getCategory(String categoryId){
        return categoryRepository.findById(UUID.fromString(categoryId))
                .orElseThrow(() -> new CategoryNotFound("Category not found"));
    }

    public ArrayList<Category> getAllCategories(String userId){
        return categoryRepository.findAllByUserId(UUID.fromString(userId))
                .orElseThrow(() -> new UserNotFound("User not found"));
    }

    public ResponseEntity<Map<String, Object>> createCategory(CategoryRequestDTO categoryRequestDTO){
        User user = userRepository.findById(UUID.fromString(categoryRequestDTO.userId()))
                .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel xpByLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level() + 1);
        Category newCategory = new Category(categoryRequestDTO, user);
        newCategory.setNextLevelXp(xpByLevel.getXp());

        categoryRepository.save(newCategory);

        return ResponseEntity.ok().body(Map.of("success", newCategory));
    }

    public ResponseEntity<Map<String, Object>> editCategory(CategoryEditRequestDTO categoryEditRequestDTO){
        Category categoryToEdit = getCategory(categoryEditRequestDTO.categoryId());

        categoryToEdit.setName(categoryEditRequestDTO.name());
        categoryToEdit.setDescription(categoryEditRequestDTO.description());
        categoryToEdit.setIconId(categoryEditRequestDTO.icon());

        categoryRepository.save(categoryToEdit);

        return ResponseEntity.ok().body(Map.of("success", categoryToEdit));
    }

    public ResponseEntity<Map<String, String>> deleteCategory(String categoryId){
        try{
            Category category = categoryRepository.findById(UUID.fromString(categoryId))
                    .orElseThrow(() -> new CategoryNotFound("Category not found"));

            categoryRepository.delete(category);
            return ResponseEntity.ok().body(Map.of("success", "Category deleted successfully"));
        }catch (Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTryingToDeleteCategory"));
        }
    }
}
