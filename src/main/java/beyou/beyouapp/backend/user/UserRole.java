package beyou.beyouapp.backend.user;

public enum UserRole {
    USER("beyou/beyouapp/backend/user");

    private String role;

    UserRole(String role){
        this.role = role;
    }

    public String getRole(){
        return role;
    }
}
