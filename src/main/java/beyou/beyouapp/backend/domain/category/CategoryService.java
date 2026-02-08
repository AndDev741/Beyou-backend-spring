package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryService {
    @Autowired
    private final CategoryRepository categoryRepository;

    @Autowired
    private final XpByLevelRepository xpByLevelRepository;

    @Autowired
    private final UserRepository userRepository;

    @Autowired
    private final CategoryMapper categoryMapper;

    public Category getCategory(UUID categoryId){
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFound("Category not found"));
    }

    public List<CategoryResponseDTO> getAllCategories(UUID userId){
        ArrayList<Category> categories = categoryRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound(""));

        return categories.stream()
                .map(categoryMapper::toResponseDTO)
                .toList();
    }

    public ResponseEntity<Map<String, Object>> createCategory(CategoryRequestDTO categoryRequestDTO, UUID userId){
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level() + 1);
        XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level());

        Category newCategory = categoryMapper.toEntity(categoryRequestDTO, user, xpForActualLevel, xpForNextLevel);

        try{
            categoryRepository.save(newCategory);
            return ResponseEntity.ok().body(Map.of("success", "Category created successfully"));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.CATEGORY_CREATE_FAILED, "Error trying create the category");
        }
    }

    public ResponseEntity<Map<String, Object>> editCategory(CategoryEditRequestDTO categoryEditRequestDTO, UUID userId){
        Category categoryToEdit = getCategory(UUID.fromString(categoryEditRequestDTO.categoryId()));
        //If are not from the user in context
        if(!categoryToEdit.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.CATEGORY_NOT_OWNED, "This category are not from the user in context");
        }

        categoryMapper.updateEntity(categoryToEdit, categoryEditRequestDTO);

        try{
            categoryRepository.save(categoryToEdit);
            return ResponseEntity.ok().body(Map.of("success", categoryToEdit));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.CATEGORY_EDIT_FAILED, "Error trying to edit the category");
        }
    }

    public ResponseEntity<Map<String, String>> deleteCategory(String categoryId, UUID userId){
        try{
            Category category = categoryRepository.findById(UUID.fromString(categoryId))
                    .orElseThrow(() -> new CategoryNotFound("Category not found"));
            if(!category.getUser().getId().equals(userId)){
                throw new BusinessException(ErrorKey.CATEGORY_NOT_OWNED, "This category are not from the user in context");
            }

            categoryRepository.delete(category);
            return ResponseEntity.ok().body(Map.of("success", "Category deleted successfully"));
        }catch(DataIntegrityViolationException exception){
            throw new BusinessException(ErrorKey.CATEGORY_IN_USE, "This category is used in some habit, please delete it first");
        }catch(CategoryNotFound ex){
            throw new CategoryNotFound(ex.getMessage());
        }
        catch (Exception e){
            throw new BusinessException(ErrorKey.CATEGORY_DELETE_FAILED, "Error trying to delete the category");
        }
    }

    public void updateCategoriesXpAndLevel(List<Category> categories, Double newXp){
        categories.forEach(c -> 
            c.gainXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level)
            )
        );
    }

    public void removeXpFromCategories(List<Category> categories, Double xpToRemove){
        categories.forEach(c -> 
            c.loseXp(xpToRemove, 
                level -> xpByLevelRepository.findByLevel(level)
            )
        );
    }

}
