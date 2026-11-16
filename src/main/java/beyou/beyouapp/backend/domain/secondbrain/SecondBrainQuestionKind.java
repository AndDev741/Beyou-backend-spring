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
    FOLLOW_UP(null, null, null);

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
}
