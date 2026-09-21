package atlas.fsrs

import atlas.core.Log
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler

/**
 * java-fsrs 适配层（PRD FR-C3，调研-T/C）：
 * 卡片的 FSRS 状态以 JSON 存于 cards.md 的 `- fsrs:` 行；评分 → 新状态为纯函数。
 */
object FsrsEngine {

    @Volatile
    private var currentScheduler: Scheduler = Scheduler.builder().build()

    @Volatile
    private var currentRetention: Double = 0.9

    private val defaultScheduler: Scheduler get() = Scheduler.builder().build()

    /** 当前 desired retention（默认 0.9，PRD FR-C3 可查看/重置） */
    fun desiredRetention(): Double = currentRetention

    /** 切换 retention（立即生效，后续评分用新 scheduler） */
    fun schedulerFor(retention: Double): Scheduler {
        val r = retention.coerceIn(0.70, 0.99)
        currentScheduler = Scheduler.builder().desiredRetention(r).build()
        currentRetention = r
        return currentScheduler
    }

    /** 恢复默认 0.9 */
    fun resetParams() {
        currentScheduler = defaultScheduler
        currentRetention = 0.9
    }

    fun newCardJson(): String = Card.builder()
        .due(java.time.Instant.now())
        .build()
        .toJson()

    enum class Grade { AGAIN, HARD, GOOD, EASY }

    data class ReviewResult(val newJson: String, val dueEpochMs: Long)

    fun review(cardJson: String, grade: Grade, now: java.time.Instant = java.time.Instant.now()): ReviewResult {
        val card = try {
            Card.fromJson(cardJson)
        } catch (e: Exception) {
            Log.w("FSRS 状态解析失败，按新卡处理（状态会丢失，json 前 40 字=${cardJson.take(40)}）", e)
            Card.builder().due(now).build()
        }
        val rating = when (grade) {
            Grade.AGAIN -> Rating.AGAIN; Grade.HARD -> Rating.HARD
            Grade.GOOD -> Rating.GOOD; Grade.EASY -> Rating.EASY
        }
        val out = currentScheduler.reviewCard(card, rating, now)
        return ReviewResult(out.card().toJson(), out.card().due?.toEpochMilli() ?: now.toEpochMilli())
    }

    fun dueEpochMs(cardJson: String): Long = try {
        Card.fromJson(cardJson).due?.toEpochMilli() ?: 0L
    } catch (e: Exception) { 0L }

    fun isDue(cardJson: String, now: java.time.Instant = java.time.Instant.now()): Boolean =
        dueEpochMs(cardJson) <= now.toEpochMilli()

    fun isLearning(cardJson: String): Boolean = try {
        when (Card.fromJson(cardJson).state?.name) {
            "LEARNING", "RELEARNING" -> true
            else -> false
        }
    } catch (e: Exception) { false }

    fun isNew(cardJson: String): Boolean = try {
        Card.fromJson(cardJson).state?.name == "NEW"
    } catch (e: Exception) { false }
}
