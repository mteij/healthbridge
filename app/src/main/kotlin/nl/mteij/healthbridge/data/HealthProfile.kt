package nl.mteij.healthbridge.data

import java.time.LocalDate
import kotlin.math.roundToInt

enum class BmiClass { UNDERWEIGHT, NORMAL, OVERWEIGHT, OBESE }

/**
 * Everything the app derives from the profile and from workouts, with the source of each rule.
 * These are population estimates for orientation, not medical advice.
 */
object HealthMath {
    /** WHO 2020: 150 minutes of moderate activity per week is the lower end of the target range (150-300). */
    const val WEEKLY_ACTIVE_MINUTES = 150

    /** AASM/SRS consensus 2015: adults should regularly sleep 7 hours or more. */
    const val SLEEP_TARGET_MINUTES = 7 * 60

    fun age(birthYear: Int): Int? = if (birthYear in 1900..LocalDate.now().year) LocalDate.now().year - birthYear else null

    /** Nes et al. 2013 (HUNT, 3,320 healthy adults, verified maximal effort): HRmax = 211 - 0.64 x age. SE about 11 bpm. */
    fun maxHrFromAge(age: Int): Int = (211 - 0.64 * age).roundToInt()

    /**
     * Age based estimate, raised when the user's own workouts show a higher heart rate: no one can
     * exceed their true maximum, so a higher observed value is the better figure.
     */
    fun maxHr(age: Int?, observed: Int?): Int? = listOfNotNull(age?.let(::maxHrFromAge), observed?.takeIf { it in 120..230 }).maxOrNull()

    /** Second highest per-workout maximum: one optical-sensor spike must not redefine the maximum. */
    fun observedMaxHr(workoutMaxima: List<Int>): Int? = workoutMaxima.filter { it in 120..230 }.sortedDescending().getOrNull(1)

    /**
     * Daily steps target. Paluch et al. 2022 (15 cohorts, Lancet Public Health): mortality benefit levels
     * off around 6,000-8,000 steps for adults 60+ and 8,000-10,000 for younger adults (ranges as reported
     * by secondary coverage). The lower end of each range is used.
     */
    fun stepGoal(age: Int?, recentDays: List<Double> = emptyList()): Int {
        val base = if (age != null && age >= 60) 6000 else 8000
        // someone who already walks more than the baseline gets a goal slightly above their own median day
        val median = recentDays.filter { it > 0 }.sorted().let { if (it.size >= 4) it[it.size / 2] else null }
        return if (median != null && median > base) minOf((median * 1.1 / 500).roundToInt() * 500, 15000) else base
    }

    fun bmi(weightKg: Double, heightM: Double): Double = weightKg / (heightM * heightM)

    /** WHO adult BMI classes: below 18.5, 18.5-24.9, 25-29.9, 30 and above. */
    fun bmiClass(bmi: Double) = when {
        bmi < 18.5 -> BmiClass.UNDERWEIGHT
        bmi < 25 -> BmiClass.NORMAL
        bmi < 30 -> BmiClass.OVERWEIGHT
        else -> BmiClass.OBESE
    }

    /** Mifflin-St Jeor 1990: 10 x kg + 6.25 x cm - 5 x age + 5 (men) or - 161 (women). Most reliable common equation, but individual error is real. */
    fun restingKcal(sex: String, age: Int, heightCm: Double, weightKg: Double): Int? {
        val offset = when (sex) {
            "male" -> 5
            "female" -> -161
            else -> return null
        }
        return (10 * weightKg + 6.25 * heightCm - 5 * age + offset).roundToInt()
    }

    // ---- VO2 max ----

    enum class Vo2Source { DEVICE, RUNS, HEART_RATES }

    class Vo2(val value: Double, val source: Vo2Source, val change: Double? = null)

    /**
     * Uth et al. 2004: VO2max = 15.3 x HRmax / HRrest (ml/kg/min). Needs nothing but two heart rates,
     * but is rough (about 10 % error) and ignores training, so it is only the fallback.
     */
    fun vo2FromHeartRates(maxHr: Int, restHr: Double): Double? =
        (15.3 * maxHr / restHr).takeIf { restHr in 30.0..110.0 && it in 15.0..90.0 }

    /**
     * One steady run -> VO2max. ACSM running equation: VO2 = 0.2 x speed (m/min) + 3.5 ml/kg/min on the flat.
     * Swain 1997: the share of heart rate reserve used equals the share of VO2 reserve, so
     * VO2max = 3.5 + 0.2 x speed / %HRR. Only runs that are long enough, fast enough to be running
     * and hard enough (50-95 % of reserve) give a trustworthy figure.
     */
    fun vo2FromRun(speedMps: Double, avgHr: Int, restHr: Double, maxHr: Int): Double? {
        if (maxHr <= restHr) return null
        val reserve = (avgHr - restHr) / (maxHr - restHr)
        if (reserve !in 0.5..0.95 || speedMps !in 2.2..6.5) return null
        return (3.5 + 0.2 * speedMps * 60 / reserve).takeIf { it in 15.0..80.0 }
    }

    /** Date (yyyy-MM-dd, oldest first) and VO2max estimate for every qualifying run. */
    fun runEstimates(workouts: List<Workout>, restHr: Double?, maxHr: Int?): List<Pair<String, Double>> {
        if (restHr == null || maxHr == null) return emptyList()
        return workouts.filter { it.type == "running" && it.durationS >= 600 }.mapNotNull { w ->
            val speed = w.avgSpeedMps ?: return@mapNotNull null
            val hr = w.heartRate?.avg ?: return@mapNotNull null
            val date = runCatching { java.time.Instant.parse(w.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.getOrNull()
            val vo2 = vo2FromRun(speed, hr, restHr, maxHr)
            if (date != null && vo2 != null) date to vo2 else null
        }.sortedBy { it.first }
    }

    /** The figure shown: a device measurement if there is one, else the median of the latest runs, else the heart rate formula. */
    fun vo2(measured: Double?, workouts: List<Workout>, restHr: Double?, maxHr: Int?): Vo2? {
        if (measured != null) return Vo2(measured, Vo2Source.DEVICE)
        val runs = runEstimates(workouts, restHr, maxHr).map { it.second }
        if (runs.isNotEmpty()) {
            fun median(l: List<Double>) = l.sorted()[l.size / 2]
            val recent = runs.takeLast(5)
            val earlier = runs.dropLast(5).takeLast(5)
            return Vo2(median(recent), Vo2Source.RUNS, if (earlier.size >= 2) median(recent) - median(earlier) else null)
        }
        if (maxHr != null && restHr != null) vo2FromHeartRates(maxHr, restHr)?.let { return Vo2(it, Vo2Source.HEART_RATES) }
        return null
    }
}
