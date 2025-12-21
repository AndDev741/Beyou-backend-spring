package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.XpProgress;
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
                category.getXpProgress().getXp(),
                category.getXpProgress().getNextLevelXp(),
                category.getXpProgress().getActualLevelXp(),
                category.getXpProgress().getLevel(),
                category.getCreatedAt()
        );
    }

    public ResponseEntity<Map<String, Object>> createCategory(CategoryRequestDTO categoryRequestDTO, UUID userId){
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level() + 1);
        XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(categoryRequestDTO.level());

        Category newCategory = new Category(categoryRequestDTO, user);
        XpProgress xpProgress = new XpProgress();
        xpProgress.setNextLevelXp(xpForNextLevel.getXp());
        xpProgress.setActualLevelXp(xpForActualLevel.getXp());

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
