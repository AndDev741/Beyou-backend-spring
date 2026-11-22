package beyou.beyouapp.backend.domain.secondbrain;

/**
 * Why a question was asked. Mirrored by {@code second_brain_questions_kind_check} in V45.
 * Adding a value here without adding it there makes every write of the new kind fail.
 *
 * <p>The seeds and the check-in carry their own wording, in both of the app's languages. It is
 * stored on the question when it is asked, so a question reads the same after the person
 * switches language. A follow-up has none: the model writes it.
 */
public enum SecondBrainQuestionKind {

    /** The three seeds, each asked once per account while its fact kind is empty. */
    PROFILE_OBJECTIVES(SecondBrainFactKind.OBJECTIVE,
            "What are the main things you want out of this year?",
            "Quais são as principais coisas que você quer deste ano?"),
    PROFILE_LIKES(SecondBrainFactKind.LIKE,
            "What do you enjoy doing when the day is yours?",
            "O que você gosta de fazer quando o dia é seu?"),
    PROFILE_ABOUT(SecondBrainFactKind.ABOUT,
            "Tell me about your week: who you live with and what you do for work.",
            "Me conta da sua semana: com quem você mora e com o que trabalha."),
    /** Once a day, on a day a routine runs. */
    CHECK_IN(null,
            "How is your day going? Describe it and I can check or skip your routine items.",
            "Como está o seu dia? Me descreve e eu marco ou pulo os itens da sua rotina."),
    /** Written by the model from the enabled sources. */
    FOLLOW_UP(null, null, null),
    /**
     * Asked once about a habit, goal or routine created after the account's first visit. Its
     * wording depends on what the thing is and carries its name, so it comes from
     * {@link #newThingText} instead of {@link #fixedText}.
     */
    NEW_THING(null, null, null);

    /** How much of a thing's name a NEW_THING question quotes. */
    public static final int NEW_THING_NAME_MAX = 80;

    private final SecondBrainFactKind factKind;
    private final String english;
    private final String portuguese;

    SecondBrainQuestionKind(SecondBrainFactKind factKind, String english, String portuguese) {
        this.factKind = factKind;
        this.english = english;
        this.portuguese = portuguese;
    }

    /** The fact kind a profile question fills, or null for the other kinds. */
    public SecondBrainFactKind factKind() {
        return factKind;
    }

    public boolean isProfile() {
        return factKind != null;
    }

    /**
     * The fixed wording for this kind, or null for a follow-up.
     *
     * @param language what {@code UserLanguage.forPrompt} answered: "pt" or anything else
     */
    public String fixedText(String language) {
        return "pt".equals(language) ? portuguese : english;
    }

    /**
     * The NEW_THING question about one thing, in the account's language, with the thing's name
     * on one line and cut to {@link #NEW_THING_NAME_MAX} characters.
     *
     * @param type     HABIT, GOAL or ROUTINE
     * @param language what {@code UserLanguage.forPrompt} answered: "pt" or anything else
     */
    public static String newThingText(SecondBrainSubjectType type, String name, String language) {
        String quoted = SecondBrainStore.truncate(
                name == null ? "" : name.replaceAll("\\s+", " ").strip(), NEW_THING_NAME_MAX);
        boolean pt = "pt".equals(language);
        return switch (type) {
            case HABIT -> pt
                    ? "Você começou o hábito \"" + quoted + "\". O que te levou a ele, e o que costuma atrapalhar?"
                    : "You started the habit \"" + quoted + "\". What led you to it, and what usually gets in the way?";
            case GOAL -> pt
                    ? "Você criou a meta \"" + quoted + "\". O que te levou a ela, e o que pode atrapalhar?"
                    : "You set the goal \"" + quoted + "\". What led you to it, and what could get in the way?";
            case ROUTINE -> pt
                    ? "Você criou a rotina \"" + quoted + "\". O que te levou a ela, e o que costuma atrapalhar?"
                    : "You created the routine \"" + quoted + "\". What led you to it, and what usually gets in the way?";
            case CATEGORY, LOOSE -> throw new IllegalArgumentException("No NEW_THING question for a " + type);
        };
    }
}
