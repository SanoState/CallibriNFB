package com.callibri.nfb.protocol

/**
 * Temporary FRE1 band defaults for this prototype.
 *
 * These numbers live here, not in the signal processor. Later phases should
 * replace [defaults] with bands loaded from an assessment or protocol file
 * and pass that list into [com.callibri.nfb.signal.EegProcessor.setBands].
 */
object FRE1Protocol {
    const val SAMPLE_RATE_HZ = 250

    fun defaults(): Fre1Configuration = Fre1Configuration(
        inhibit1 = FrequencyBand(id = "inhibit1", label = "Inhibit 1", lowHz = 4.0, highHz = 8.0),
        reward = FrequencyBand(id = "reward", label = "Reward", lowHz = 12.0, highHz = 16.0),
        inhibit2 = FrequencyBand(id = "inhibit2", label = "Inhibit 2", lowHz = 19.0, highHz = 38.0),
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
