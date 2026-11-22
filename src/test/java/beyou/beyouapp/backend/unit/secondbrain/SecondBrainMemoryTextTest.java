package beyou.beyouapp.backend.unit.secondbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.secondbrain.OldNote;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType;

/**
 * The two pieces of text step 2 builds on the server: the old hidden note cut into sentences,
 * and the question asked about a new thing.
 */
class SecondBrainMemoryTextTest {

    // ---- the old note ----

    @Test
    @DisplayName("the old note splits on line breaks and sentence ends, trimmed, with no empty lines")
    void oldNoteSplitsIntoSentences() {
        String note = "  Training for a half marathon. Prefers mornings!\n\nHas two cats?   Works from home\r\n"
                + "Version 2.0 of the plan.  ";

        assertThat(OldNote.lines(note)).containsExactly(
                "Training for a half marathon.",
                "Prefers mornings!",
                "Has two cats?",
                "Works from home",
                // A full stop with no space after it is not a sentence end.
                "Version 2.0 of the plan.");
        assertThat(OldNote.lines(null)).isEmpty();
        assertThat(OldNote.lines(" \n ")).isEmpty();
    }

    @Test
    @DisplayName("a sentence longer than a profile line is cut at the last word that fits")
    void oldNoteLinesAreCutAtAWord() {
        String word = "abcdefghi ";
        String longSentence = word.repeat(25).strip();

        String cut = OldNote.lines(longSentence).get(0);

        assertThat(cut.length()).isLessThanOrEqualTo(OldNote.LINE_MAX);
        assertThat(cut).endsWith("abcdefghi").doesNotEndWith(" ");
        assertThat(longSentence).startsWith(cut);
        // One word with no space to cut at is cut at the limit.
        assertThat(OldNote.lines("x".repeat(250)).get(0)).hasSize(OldNote.LINE_MAX);
    }

    /**
     * The page sends back a sentence it was given, and the route finds it by splitting the note
     * again. That only works if splitting what is left gives the same sentences.
     */
    @Test
    @DisplayName("taking a sentence out leaves a note that splits into the same remaining sentences")
    void takingALineOutIsStable() {
        String note = "One. Two! " + "word ".repeat(60).strip() + ".\nThree?";
        List<String> lines = OldNote.lines(note);

        List<String> left = OldNote.without(note, "Two!").orElseThrow();
        String rewritten = OldNote.join(left);

        assertThat(left).containsExactly(lines.get(0), lines.get(2), lines.get(3));
        assertThat(OldNote.lines(rewritten)).isEqualTo(left);
        assertThat(OldNote.without(rewritten, lines.get(2))).isPresent();
        assertThat(OldNote.without(note, "Never said.")).isEmpty();
        assertThat(OldNote.join(List.of())).as("an emptied note is no note").isNull();
    }

    // ---- the NEW_THING question ----

    @Test
    @DisplayName("a new thing's question names it in quotes, in the account's language, per kind of thing")
    void newThingQuestionsInBothLanguages() {
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.HABIT, "Gym", "en"))
                .isEqualTo("You started the habit \"Gym\". What led you to it, and what usually gets in the way?");
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.GOAL, "Gym", "en"))
                .isEqualTo("You set the goal \"Gym\". What led you to it, and what could get in the way?");
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.ROUTINE, "Gym", "en"))
                .isEqualTo("You created the routine \"Gym\". What led you to it, and what usually gets in the way?");
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.HABIT, "Academia", "pt"))
                .isEqualTo("Você começou o hábito \"Academia\". O que te levou a ele, e o que costuma atrapalhar?");
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.GOAL, "Academia", "pt"))
                .isEqualTo("Você criou a meta \"Academia\". O que te levou a ela, e o que pode atrapalhar?");
        assertThat(SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.ROUTINE, "Academia", "pt"))
                .isEqualTo("Você criou a rotina \"Academia\". O que te levou a ela, e o que costuma atrapalhar?");
    }

    @Test
    @DisplayName("a long name is put on one line and cut to 80 characters")
    void newThingNameIsCut() {
        String text = SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.HABIT,
                "Read\n" + "y".repeat(200), "en");

        String quoted = text.substring(text.indexOf('"') + 1, text.lastIndexOf('"'));
        assertThat(quoted).hasSize(SecondBrainQuestionKind.NEW_THING_NAME_MAX).startsWith("Read y");
        assertThatThrownBy(() -> SecondBrainQuestionKind.newThingText(SecondBrainSubjectType.LOOSE, "x", "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
