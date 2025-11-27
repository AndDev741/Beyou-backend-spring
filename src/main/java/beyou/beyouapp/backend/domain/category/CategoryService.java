package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CategoryService {
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private XpByLevelRepository xpByLevelRepository;

    @Autowired
    private UserRepository userRepository;

    public Category getCategory(UUID categoryId){
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFound("Category not found"));
    }

    public List<CategoryResponseDTO> getAllCategories(UUID userId){
        ArrayList<Category> categories = categoryRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound(""));

        return categories.stream()
                .map(this::mapToDto)
                .toList();
    }

    private CategoryResponseDTO mapToDto(Category category){
        return new CategoryResponseDTO(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIconId(),
                category.getHabits().stream()
                        .collect(Collectors.toMap(Habit::getId, Habit::getName)),
                category.getTasks().stream()
                        .collect(Collectors.toMap(Task::getId, Task::getName)),
                category.getGoals().stream()
                        .collect(Collectors.toMap(Goal::getId, Goal::getName)),
                category.getXp(),
                category.getNextLevelXp(),
                category.getActualLevelXp(),
                category.getLevel(),
                category.getCreatedAt()
        );
    }

    public ResponseEntity<Map<String, Object>> createCategory(CategoryRequestDTO categoryRequestDTO, UUID userId){
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level() + 1);
        XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level());

        Category newCategory = new Category(categoryRequestDTO, user);
        newCategory.setNextLevelXp(xpForNextLevel.getXp());
        newCategory.setActualLevelXp(xpForActualLevel.getXp());

        try{
            categoryRepository.save(newCategory);
            return ResponseEntity.ok().body(Map.of("success", "Category created successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying create the category"));
        }
    }

    public ResponseEntity<Map<String, Object>> editCategory(CategoryEditRequestDTO categoryEditRequestDTO, UUID userId){
        Category categoryToEdit = getCategory(UUID.fromString(categoryEditRequestDTO.categoryId()));
        //If are not from the user in context
        if(!categoryToEdit.getUser().getId().equals(userId)){
            throw new CategoryNotFound("This category are not from the user in context");
        }

        categoryToEdit.setName(categoryEditRequestDTO.name());
        categoryToEdit.setDescription(categoryEditRequestDTO.description());
        categoryToEdit.setIconId(categoryEditRequestDTO.icon());

        categoryRepository.save(categoryToEdit);

        return ResponseEntity.ok().body(Map.of("success", categoryToEdit));
    }

    public ResponseEntity<Map<String, String>> deleteCategory(String categoryId, UUID userId){
        try{
            Category category = categoryRepository.findById(UUID.fromString(categoryId))
                    .orElseThrow(() -> new CategoryNotFound("Category not found"));
            if(!category.getUser().getId().equals(userId)){
                throw new CategoryNotFound("This category are not from the user in context");
            }

            categoryRepository.delete(category);
            return ResponseEntity.ok().body(Map.of("success", "Category deleted successfully"));
        }catch(DataIntegrityViolationException exception){
            return ResponseEntity.badRequest().body(Map.of("error", "This category is used in some habit, please delete it first"));
        }catch(CategoryNotFound ex){
            throw new CategoryNotFound(ex.getMessage());
        }
        catch (Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to delete the category"));
        }
    }

    public void updateCategoriesXpAndLevel(List<Category> categories, Double newXp){
        for(Category category : categories){
            category.setXp(category.getXp() + newXp);

            if(category.getXp() > category.getNextLevelXp() || category.getXp() == category.getNextLevelXp()){
                category.setLevel(category.getLevel() + 1);
                XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(category.getLevel());
                XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(category.getLevel() + 1);
                category.setActualLevelXp(xpForActualLevel.getXp());
                category.setNextLevelXp(xpForNextLevel.getXp());
            }

            categoryRepository.save(category);
        }
    }

    public void removeXpFromCategories(List<Category> categories, Double xpToRemove){
        for(Category category : categories){
            log.info("[LOG] Xp to remove => {}", xpToRemove);
            category.setXp(category.getXp() - xpToRemove);
            if(category.getXp() < category.getActualLevelXp() && category.getXp() > 0 ){
                category.setLevel(category.getLevel() - 1);
                XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(category.getLevel());
                XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(category.getLevel() + 1);
                category.setActualLevelXp(xpForActualLevel.getXp());
                category.setNextLevelXp(xpForNextLevel.getXp());
            }

            categoryRepository.save(category);
        }
    }

}
