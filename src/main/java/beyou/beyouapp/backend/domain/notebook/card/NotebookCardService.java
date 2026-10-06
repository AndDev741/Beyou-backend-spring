package beyou.beyouapp.backend.domain.notebook.card;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.NotebookRewards;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CreateCardRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.DueCardDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.DueCardsResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.FinishReviewResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.ReviewResponseDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.UpdateCardRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * Flashcards and their reviews.
 *
 * <p>The day is always the owner's ({@link UserDateResolver}): a card due "today" is due on the
 * reader's today, and the review streak counts the reader's days.
 *
 * <p>XP is paid when a session ends ({@link #finish}), not per answer, so a person flipping
 * through cards sees one +XP at the end rather than a counter ticking under every tap. Each
 * review row is paid once, and only {@link NotebookRewards#DAILY_REVIEW_XP_CAP} a day: the cap
 * stops a deck of a thousand one-word cards being an XP farm, without making reviewing past it
 * pointless (the schedule still moves).
 */
@Service
@RequiredArgsConstructor
public class NotebookCardService {

    /** Most cards one queue read returns. A real backlog is reviewed over several sessions. */
    static final int QUEUE_LIMIT = 200;

    private final NotebookCardRepository cardRepository;
    private final NotebookCardReviewRepository reviewRepository;
    private final NotebookOwnership ownership;
    private final NotebookProgressService progressService;
    private final NotebookRewards rewards;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CardDTO> list(User user, UUID pageId) {
        ownership.page(user.getId(), pageId);
        return cardRepository.findByPageIdOrderByCreatedAtAsc(pageId).stream().map(NotebookCardService::toDto).toList();
    }

    @Transactional
    public CardDTO create(User user, UUID pageId, CreateCardRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        return toDto(save(page, request.front(), request.back(), request.sourceLabel(), UserDateResolver.today(user)));
    }

    /** Several cards at once, for the AI. Same shape as {@link #create}, one transaction. */
    @Transactional
    public List<CardDTO> createAll(NotebookPage page, List<CreateCardRequestDTO> cards, LocalDate today) {
        List<CardDTO> created = new ArrayList<>();
        for (CreateCardRequestDTO card : cards) {
            created.add(toDto(save(page, card.front(), card.back(), card.sourceLabel(), today)));
        }
        return created;
    }

    private NotebookCard save(NotebookPage page, String front, String back, String sourceLabel, LocalDate today) {
        Instant now = Instant.now();
        NotebookCard card = new NotebookCard();
        card.setUser(page.getUser());
        card.setPageId(page.getId());
        card.setFront(front.strip());
        card.setBack(back.strip());
        card.setSourceLabel(sourceLabel == null || sourceLabel.isBlank() ? null : sourceLabel.strip());
        // A new card is due the day it is written: it has never been reviewed.
        card.setDueOn(today);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);
        return cardRepository.save(card);
    }

    @Transactional
    public CardDTO update(User user, UUID cardId, UpdateCardRequestDTO request) {
        NotebookCard card = ownedCard(user, cardId);
        if (request.front() != null) card.setFront(request.front().strip());
        if (request.back() != null) card.setBack(request.back().strip());
        card.setUpdatedAt(Instant.now());
        return toDto(card);
    }

    @Transactional
    public void delete(User user, UUID cardId) {
        cardRepository.delete(ownedCard(user, cardId));
    }

    /**
     * The cards due today or earlier, oldest first: everywhere, or under one page (the page and
     * every page below it, through the tree and through its boards).
     */
    @Transactional(readOnly = true)
    public DueCardsResponseDTO due(User user, UUID scopePageId) {
        LocalDate today = UserDateResolver.today(user);
        ProgressGraph graph = progressService.graphFor(user.getId());
        List<NotebookCard> due;
        if (scopePageId == null) {
            due = cardRepository.findByUserIdAndDueOnLessThanEqualOrderByDueOnAscCreatedAtAsc(user.getId(), today);
        } else {
            ownership.page(user.getId(), scopePageId);
            Set<UUID> scope = graph.below(scopePageId);
            due = cardRepository.findByUserIdAndPageIdInAndDueOnLessThanEqualOrderByDueOnAscCreatedAtAsc(
                    user.getId(), scope, today);
        }
        List<DueCardDTO> cards = due.stream().limit(QUEUE_LIMIT).map(card -> {
            NotebookPage page = graph.page(card.getPageId());
            NotebookPage topic = page == null ? null : page.isTopic() ? page : graph.page(page.getTopicId());
            return new DueCardDTO(card.getId(), card.getPageId(),
                    page == null ? null : page.getTitle(),
                    topic == null ? null : topic.getId(),
                    topic == null ? null : topic.getTitle(),
                    card.getFront(), card.getBack(), card.getSourceLabel(),
                    SpacedRepetition.preview(card.state()));
        }).toList();
        return new DueCardsResponseDTO(cards, due.size(), streak(user, today));
    }

    @Transactional
    public ReviewResponseDTO review(User user, UUID cardId, CardRating rating) {
        NotebookCard card = ownedCard(user, cardId);
        LocalDate today = UserDateResolver.today(user);
        Instant now = Instant.now();
        card.apply(SpacedRepetition.next(card.state(), rating), today, now);

        NotebookCardReview review = new NotebookCardReview();
        review.setUser(card.getUser());
        review.setCardId(card.getId());
        review.setRating(rating);
        review.setReviewDate(today);
        review.setReviewedAt(now);
        reviewRepository.save(review);
        return new ReviewResponseDTO(card.getId(), card.getDueOn(), card.getIntervalDays(),
                !card.getDueOn().isAfter(today));
    }

    /**
     * Pays for today's unpaid reviews, up to the daily cap, and marks every one of them paid.
     *
     * <p>Reviews past the cap are marked paid too, with nothing paid for them. They could only
     * ever be paid today, and today is already capped, so leaving them open would just make every
     * later call of the day walk them again for nothing.
     */
    @Transactional
    public FinishReviewResponseDTO finish(User user) {
        LocalDate today = UserDateResolver.today(user);
        User owner = userRepository.findById(user.getId()).orElseThrow();
        List<NotebookCardReview> unpaid =
                reviewRepository.findByUserIdAndReviewDateAndXpPaidFalseOrderByReviewedAtAsc(user.getId(), today);
        long alreadyPaid = reviewRepository.countByUserIdAndReviewDateAndXpPaidTrue(user.getId(), today);
        int payable = (int) Math.max(0, Math.min(unpaid.size(), NotebookRewards.DAILY_REVIEW_XP_CAP - alreadyPaid));

        ProgressGraph graph = progressService.graphFor(user.getId());
        Map<UUID, NotebookCard> cards = new HashMap<>();
        cardRepository.findAllById(unpaid.stream().map(NotebookCardReview::getCardId).toList())
                .forEach(c -> cards.put(c.getId(), c));

        NotebookRewards.Payroll payroll = new NotebookRewards.Payroll();
        for (int i = 0; i < unpaid.size(); i++) {
            NotebookCardReview review = unpaid.get(i);
            review.setXpPaid(true);
            if (i >= payable) continue;
            NotebookCard card = cards.get(review.getCardId());
            NotebookPage page = card == null ? null : graph.page(card.getPageId());
            NotebookPage topic = page == null ? null : page.isTopic() ? page : graph.page(page.getTopicId());
            payroll.add(topic == null ? null : topic.getCategory(), NotebookRewards.CARD_REVIEW_XP);
        }
        var refresh = payroll.isEmpty() ? null : rewards.pay(owner, payroll);
        return new FinishReviewResponseDTO(payable, payroll.total(), streak(user, today), refresh);
    }

    public int streak(User user, LocalDate today) {
        return ReviewStreak.count(
                reviewRepository.reviewDaysSince(user.getId(), today.minusDays(ReviewStreak.LOOKBACK_DAYS)), today);
    }

    private NotebookCard ownedCard(User user, UUID cardId) {
        NotebookCard card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_CARD_NOT_FOUND, "Card not found"));
        ownership.page(user.getId(), card.getPageId());
        return card;
    }

    static CardDTO toDto(NotebookCard card) {
        return new CardDTO(card.getId(), card.getPageId(), card.getFront(), card.getBack(),
                card.getSourceLabel(), card.getDueOn(), card.getIntervalDays(), card.getReps(), card.getCreatedAt());
    }
}
