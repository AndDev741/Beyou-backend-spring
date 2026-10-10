package beyou.beyouapp.backend.exceptions;

public enum ErrorKey {
    CATEGORY_IN_USE,
    CATEGORY_NOT_FOUND,
    CATEGORY_NOT_OWNED,
    CATEGORY_CREATE_FAILED,
    CATEGORY_EDIT_FAILED,
    CATEGORY_DELETE_FAILED,
    HABIT_NOT_FOUND,
    HABIT_NOT_OWNED,
    HABIT_CREATE_FAILED,
    HABIT_EDIT_FAILED,
    HABIT_DELETE_FAILED,
    HABIT_IN_ROUTINE,
    TASK_NOT_FOUND,
    TASK_NOT_OWNED,
    TASK_CREATE_FAILED,
    TASK_EDIT_FAILED,
    TASK_DELETE_FAILED,
    TASK_IN_ROUTINE,
    GOAL_NOT_FOUND,
    GOAL_NOT_OWNED,
    GOAL_CREATE_FAILED,
    GOAL_EDIT_FAILED,
    GOAL_DELETE_FAILED,
    // Nested goals: the parent would make the goal its own ancestor
    GOAL_PARENT_CYCLE,
    // Nested goals: the chain would be deeper than GoalService.MAX_DEPTH levels
    GOAL_DEPTH_EXCEEDED,
    // Archived goals: a new sub-goal, or a moved one, under a goal that has been put away
    GOAL_PARENT_ARCHIVED,
    MOOD_NOT_FOUND,
    // A mood is a report, not a plan: MoodService refuses days after the owner's today.
    MOOD_FUTURE_DATE,
    MOOD_SAVE_FAILED,
    MOOD_DELETE_FAILED,
    ROUTINE_NOT_FOUND,
    ROUTINE_NOT_OWNED,
    ROUTINE_NAME_REQUIRED,
    ROUTINE_SECTION_REQUIRED,
    ROUTINE_SECTION_NOT_FOUND,
    ROUTINE_ITEM_NOT_FOUND,
    ROUTINE_SECTION_NAME_REQUIRED,
    ROUTINE_SECTION_START_REQUIRED,
    // LIST routines: the item list is what ROUTINE_SECTION_REQUIRED is to a DAILY one.
    ROUTINE_ITEMS_REQUIRED,
    // An entry naming neither a habit nor a task, or naming both.
    ROUTINE_ITEM_AMBIGUOUS,
    // Sections sent for a LIST routine, or items sent for a DAILY one.
    ROUTINE_SHAPE_MISMATCH,
    ITEM_END_BEFORE_START,
    ITEM_START_OUT_OF_SECTION,
    ITEM_END_OUT_OF_SECTION,
    SCHEDULE_NOT_FOUND,
    ITEM_GROUP_REQUIRED,
    DOCS_TOPIC_NOT_FOUND,
    DOCS_BLOG_NOT_FOUND,
    DOCS_IMPORT_FAILED,
    USER_NOT_FOUND,
    INVALID_REQUEST,
    PASSWORD_RESET_TOKEN_INVALID,
    PASSWORD_RESET_TOKEN_EXPIRED,
    PASSWORD_RESET_TOKEN_USED,
    PASSWORD_RESET_NOT_ALLOWED,
    PASSWORD_RESET_TOO_MANY_REQUESTS,

    // Account deletion: the emailed code that has to come back before anything is destroyed
    DELETION_CODE_INVALID,
    DELETION_CODE_EXPIRED,
    DELETION_CODE_TOO_MANY_ATTEMPTS,
    DELETION_CODE_TOO_MANY_REQUESTS,
    ACCOUNT_DELETE_FAILED,
    SNAPSHOT_NOT_FOUND,
    SNAPSHOT_NOT_OWNED,
    SNAPSHOT_CHECK_NOT_FOUND,
    SNAPSHOT_CHECK_NOT_IN_SNAPSHOT,
    EMAIL_NOT_VERIFIED,
    EXTERNAL_SERVICE_ERROR,
    UNEXPECTED_ERROR,
    DATA_CONFLICT,
    JWT_NOT_FOUND,
    JWT_INVALID,
    AUTH_HEADER_INVALID,
    REFRESH_TOKEN_NOT_FOUND,
    REFRESH_TOKEN_EXPIRED,
    REFRESH_TOKEN_INVALID,
    GOOGLE_OAUTH_FAILED,
    RATE_LIMIT_EXCEEDED,
    PHOTO_UPLOAD_NO_FILE,
    PHOTO_UPLOAD_INVALID_TYPE,
    PHOTO_UPLOAD_TOO_LARGE,
    PHOTO_UPLOAD_CORRUPT,
    PHOTO_DELETE_FAILED,
    CHAT_NOT_FOUND,
    CHAT_NOT_OWNED,
    CHAT_DELETE_FAILED,
    FEEDBACK_NOT_FOUND,
    FEEDBACK_NOT_OWNED,
    FEEDBACK_CREATE_FAILED,
    FEEDBACK_REPLY_FAILED,
    FEEDBACK_ATTACHMENT_NO_FILE,
    FEEDBACK_ATTACHMENT_INVALID_TYPE,
    FEEDBACK_ATTACHMENT_TOO_LARGE,
    FEEDBACK_ATTACHMENT_CORRUPT,
    FEEDBACK_ATTACHMENT_STORE_FAILED,
    FEEDBACK_ATTACHMENT_LIMIT_REACHED,
    FEEDBACK_ATTACHMENT_NOT_FOUND,
    AI_UNAVAILABLE,
    FEDERATED_IDENTITY_ALREADY_LINKED,
    FEDERATED_IDENTITY_ISSUER_ALREADY_LINKED,
    OIDC_PROVIDER_UNKNOWN,
    OIDC_TOKEN_INVALID,
    // Federated sign-in verified the identity, but it may not enter on its own: the issuer's
    // word on the address is not trusted, or the address belongs to an account that exists.
    // Answered as a 403 with the reason and the provider in details. Not an error to retry:
    // the client asks the person to sign in their usual way and link the provider from settings.
    FEDERATED_LINK_REQUIRED,
    // Focus mode micro-tasks. Separate keys for the same reason as the notebook's below.
    FOCUS_MICRO_TASK_NOT_FOUND,
    FOCUS_MICRO_TASK_NOT_OWNED,
    // Study notebook. Not-owned and not-found stay separate keys like every other domain,
    // even though both answer 400, because the client words them differently.
    NOTEBOOK_PAGE_NOT_FOUND,
    NOTEBOOK_PAGE_NOT_OWNED,
    // The id named a page where only a topic (a root) makes sense, or the other way round.
    NOTEBOOK_TOPIC_REQUIRED,
    // Putting the page on this board would make it reachable from itself.
    NOTEBOOK_BOARD_CYCLE,
    NOTEBOOK_NODE_NOT_FOUND,
    // The page is already a node on this board.
    NOTEBOOK_NODE_DUPLICATE,
    // An edge between nodes of different boards, or a section used as an endpoint.
    NOTEBOOK_EDGE_INVALID,
    NOTEBOOK_EDGE_NOT_FOUND,
    NOTEBOOK_CARD_NOT_FOUND,
    NOTEBOOK_SOURCE_NOT_FOUND,
    NOTEBOOK_SOURCE_TOO_LARGE,
    // The PDF could not be parsed, or held no text (a scan without OCR).
    NOTEBOOK_SOURCE_UNREADABLE,
    // A link the server refuses to fetch: not http(s), or pointing inside a private network.
    NOTEBOOK_SOURCE_URL_REFUSED,
    NOTEBOOK_SOURCE_FETCH_FAILED,
    // The server restarted while the source was being read. PDFs are not kept, so it has to
    // be added again.
    NOTEBOOK_SOURCE_INTERRUPTED,
    NOTEBOOK_SOURCE_LIMIT_REACHED,
    NOTEBOOK_OUTPUT_NOT_FOUND,
    // An AI action on a page with no text of its own and no sources to read.
    NOTEBOOK_NOTHING_TO_STUDY,
    // Roadmap drafts for "New topic with AI" (RoadmapDraftService).
    NOTEBOOK_DRAFT_NOT_FOUND,
    NOTEBOOK_DRAFT_NOT_OWNED,
    // The model is still writing this draft; it takes no redraft and no ticks until it ends.
    NOTEBOOK_DRAFT_BUSY,
    NOTEBOOK_DRAFT_LIMIT_REACHED,
    // The server restarted while the model was writing the draft. Drafting again works.
    NOTEBOOK_DRAFT_INTERRUPTED,
    // "Find sources for me": no search provider is configured, or the search did not answer.
    NOTEBOOK_DISCOVERY_UNAVAILABLE,
    NOTEBOOK_DISCOVERY_FAILED,
    // The page's document was written since the editor read it (another device, another tab, the
    // assistant). The editor reads the page again and merges before saving.
    NOTEBOOK_CONTENT_CONFLICT,
}
