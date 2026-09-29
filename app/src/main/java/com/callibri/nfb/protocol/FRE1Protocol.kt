package com.callibri.nfb.protocol

/**
 * Temporary FRE1 band defaults for this prototype.
 *
 * These numbers live here, not in the signal processor. Later phases should
 * replace [defaults] with bands loaded from an assessment or protocol file
 * and pass that list into [com.callibri.nfb.signal.EegProcessor.setBands].
 */
enum class BandGoal {
    /** Success means the amplitude is below the threshold. */
    InhibitBelow,

    /** Success means the amplitude is above the threshold. */
    RewardAbove,
}

data class Fre1BandPolicy(
    val id: String,
    val goal: BandGoal,
    val targetSuccess: Double,
    val weight: Double,
)

object FRE1Protocol {
    const val SAMPLE_RATE_HZ = 250

    /** Rolling history used for auto-threshold. */
    const val WINDOW_SECONDS = 30.0

    /** Reward output floor. The mapped score never goes below this unless the user changes it. */
    const val MIN_REWARD_PERCENT = 20.0

    /**
     * Exponential smoother time constant. About half a second, inside the
     * 300–700 ms response the reward should feel like.
     */
    const val SMOOTHING_MILLIS = 500.0

    /**
     * Valid amplitude readings required in a band before its threshold may
     * move the reward. At ~6 updates/s this is a little over one second.
     * Until every band reaches this count, reward stays at [MIN_REWARD_PERCENT].
     */
    const val MIN_VALID_OBSERVATIONS = 8

    /** Band RMS above this is treated as an artifact and kept out of the window. */
    const val MAX_PLAUSIBLE_MICROVOLTS = 200.0

    fun defaults(): Fre1Configuration = Fre1Configuration(
        inhibit1 = FrequencyBand(id = "inhibit1", label = "Inhibit 1", lowHz = 4.0, highHz = 8.0),
        reward = FrequencyBand(id = "reward", label = "Reward", lowHz = 12.0, highHz = 16.0),
        inhibit2 = FrequencyBand(id = "inhibit2", label = "Inhibit 2", lowHz = 19.0, highHz = 38.0),
    )

    fun policies(): List<Fre1BandPolicy> = listOf(
        Fre1BandPolicy("inhibit1", BandGoal.InhibitBelow, targetSuccess = 0.80, weight = 0.333),
        Fre1BandPolicy("reward", BandGoal.RewardAbove, targetSuccess = 0.70, weight = 0.334),
        Fre1BandPolicy("inhibit2", BandGoal.InhibitBelow, targetSuccess = 0.80, weight = 0.333),
    )
}

data class FrequencyBand(
    val id: String,
    val label: String,
    val lowHz: Double,
    val highHz: Double,
)

data class Fre1Configuration(
    val inhibit1: FrequencyBand,
    val reward: FrequencyBand,
    val inhibit2: FrequencyBand,
) {
    fun asList(): List<FrequencyBand> = listOf(inhibit1, reward, inhibit2)
}
