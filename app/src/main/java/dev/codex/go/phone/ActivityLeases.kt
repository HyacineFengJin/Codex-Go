package dev.codex.go.phone

import dev.codex.go.R

enum class PhoneActivity(val labelId: Int) {
    Viewing(R.string.indicator_viewing), Reading(R.string.indicator_reading), Using(R.string.indicator_using);
    companion object {
        fun from(tool: String): PhoneActivity? = when (tool) {
            "phone_observe" -> Viewing
            "phone_pre_read" -> Reading
            "phone_tap", "phone_swipe", "phone_type", "phone_key", "phone_open_app" -> Using
            else -> null
        }
    }
}

/** Main-thread operation leases. An old finally cannot dismiss a newer operation. */
class ActivityLeases {
    private var sequence = 0L
    private val leases = linkedMapOf<Long, PhoneActivity>()
    val active get() = leases.isNotEmpty()
    val activity get() = if (PhoneActivity.Using in leases.values) PhoneActivity.Using else leases.values.lastOrNull()
    fun begin(activity: PhoneActivity = PhoneActivity.Using): Long = (++sequence).also { leases[it] = activity }
    fun end(token: Long) { leases.remove(token) }
    fun clear() { leases.clear() }

    companion object {
        // Cached images, handoffs and arbitrary Shell commands do not access the live screen.
        fun live(tool: String) = PhoneActivity.from(tool) != null
    }
}
