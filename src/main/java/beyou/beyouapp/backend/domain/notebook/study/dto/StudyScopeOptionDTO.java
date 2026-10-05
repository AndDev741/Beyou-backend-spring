package beyou.beyouapp.backend.domain.notebook.study.dto;

import beyou.beyouapp.backend.domain.notebook.study.StudyScope;

/**
 * One choice for which notes the AI reads, with what it covers, so the setup screen can say
 * "this page and 4 under it, about 2,300 words" before the person picks it.
 *
 * @param pages pages in the scope that have notes
 * @param words words of notes across them
 */
public record StudyScopeOptionDTO(StudyScope scope, int pages, int words) {
}
