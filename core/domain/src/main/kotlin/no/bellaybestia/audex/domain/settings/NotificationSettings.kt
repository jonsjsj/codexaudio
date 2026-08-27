package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Which push notifications Audex may post. Each category is an independent toggle in
 * Settings → Notifications; a category that's off is never posted (the workers check
 * before notifying, in addition to the OS-level notification permission).
 */
data class NotificationPrefs(
    /** A new app version is available (the OTA update notifier). */
    val appUpdates: Boolean = true,
    /** A read-along (audio-ebook sync) map finished building — or failed. */
    val readAlong: Boolean = true,
)

interface NotificationSettings {
    val prefs: Flow<NotificationPrefs>
    suspend fun set(prefs: NotificationPrefs)
}
