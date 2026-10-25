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
    fun stepGoal(age: Int?): Int = if (age != null && age >= 60) 6000 else 8000

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
}
