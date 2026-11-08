package uk.imad7x.home

import android.app.Notification
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Tells the launcher which apps have notifications (the accent dot). Only runs when the user grants access. */
class Notifs : NotificationListenerService() {
    override fun onListenerConnected() = recount()
    override fun onListenerDisconnected() = Dots.update(emptySet())
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val um = getSystemService(UserManager::class.java)
        val all = try { activeNotifications } catch (_: Exception) { null } ?: return
        Dots.update(all.filter { !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 && it.packageName != packageName }
            .mapTo(HashSet()) { "${it.packageName}@${um.getSerialNumberForUser(it.user)}" })
    }
}
