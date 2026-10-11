package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Lays a cycle's blocks out from its week and its topics. Pure arithmetic, no I/O.
 *
 * <p>One function, used by everything that makes blocks: {@code POST /notebook/cycles/plan} (the
 * editor's live preview), creating a cycle, re-planning one, and the assistant's
 * {@code createStudyCycle}. The editor never does this sum itself, so the preview and the saved
 * cycle cannot disagree. The design canvas {@code Montar.dc.html} runs the same steps in its
 * script.
 *
 * <ol>
 *   <li>{@code weight = importance * (6 - mastery)}: an important subject the person barely knows
 *       weighs 25, a minor one they master weighs 1.</li>
 *   <li>Each topic's suggested minutes per lap are its share of the week, rounded to 5 and never
 *       below 5. The person may override them; the override wins.</li>
 *   <li>The minutes are cut into blocks no longer than {@code maxBlockMinutes}, as even as 5-minute
 *       steps allow: the first blocks take the extra 5s, and anything under 5 left over joins the
 *       first block.</li>
 *   <li>Order: topics by weight (ties keep the order given), and round r takes the r-th block of
 *       every topic that still has one. That interleaving is the point of a cycle: every subject
 *       comes back every few blocks instead of one subject eating a whole week.</li>
 * </ol>
 */
public final class StudyCyclePlanner {

    private StudyCyclePlanner() {
    }

    /** One topic as the planner sees it, in display order. */
    public record TopicInput(UUID topicId, int importance, int mastery, Integer minutesOverride) {
    }

    /**
     * @param share  the topic's weight over the sum of weights, 0..1
     * @param blocks the size of each of its blocks, first to last
     */
    public record TopicPlan(UUID topicId, int weight, double share, int suggestedMinutes, int minutes,
            List<Integer> blocks) {
    }

    /** @param position 1-based */
    public record BlockPlan(int position, UUID topicId, int plannedMinutes) {
    }

    /**
     * @param lapDays how many days one lap takes at the week's pace, or null with no minutes in
     *                the week (the lap would never move)
     */
    public record Plan(int weekMinutes, int plannedMinutes, Integer lapDays, List<TopicPlan> topics,
            List<BlockPlan> blocks) {
    }

    public static int weight(int importance, int mastery) {
        return importance * (6 - mastery);
    }

    public static Plan plan(int weekMinutes, int maxBlockMinutes, List<TopicInput> topics) {
        int sumWeights = 0;
        for (TopicInput topic : topics) {
            sumWeights += weight(topic.importance(), topic.mastery());
        }

        List<TopicPlan> plans = new ArrayList<>();
        int planned = 0;
        for (TopicInput topic : topics) {
            int weight = weight(topic.importance(), topic.mastery());
            double share = sumWeights == 0 ? 0 : (double) weight / sumWeights;
            int suggested = sumWeights == 0 ? 5
                    : Math.max(5, (int) Math.round((double) weekMinutes * weight / sumWeights / 5d) * 5);
            int minutes = topic.minutesOverride() != null ? topic.minutesOverride() : suggested;
            plans.add(new TopicPlan(topic.topicId(), weight, share, suggested, minutes,
                    split(minutes, maxBlockMinutes)));
            planned += minutes;
        }

        // Heaviest first; a stable sort keeps the given order between equal weights.
        List<TopicPlan> ranked = new ArrayList<>(plans);
        ranked.sort(Comparator.comparingInt(TopicPlan::weight).reversed());
        int rounds = ranked.stream().mapToInt(p -> p.blocks().size()).max().orElse(0);
        List<BlockPlan> blocks = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (TopicPlan topic : ranked) {
                if (round < topic.blocks().size()) {
                    blocks.add(new BlockPlan(blocks.size() + 1, topic.topicId(), topic.blocks().get(round)));
                }
            }
        }

        // At least one day: a lap that fits in an afternoon still takes the day it is studied on,
        // and "about 0 days" reads like a bug.
        Integer lapDays = weekMinutes > 0
                ? Math.max(1, (int) Math.round(planned / (weekMinutes / 7d)))
                : null;
        return new Plan(weekMinutes, planned, lapDays, plans, blocks);
    }

    /** Minutes cut into blocks of at most {@code maxBlock}, as even as 5-minute steps allow. */
    static List<Integer> split(int minutes, int maxBlock) {
        int n = Math.max(1, (int) Math.ceil((double) minutes / maxBlock));
        int base = (minutes / n) / 5 * 5;
        int[] sizes = new int[n];
        java.util.Arrays.fill(sizes, base);
        int rest = minutes - base * n;
        int k = 0;
        while (rest >= 5) {
            sizes[k % n] += 5;
            rest -= 5;
            k++;
        }
        if (rest > 0) {
            sizes[0] += rest;
        }
        List<Integer> out = new ArrayList<>(n);
        for (int size : sizes) {
            out.add(size);
        }
        return out;
    }
}
