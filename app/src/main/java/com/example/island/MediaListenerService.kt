package com.example.island

import android.content.Context
import android.provider.Settings
import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * Пустой сервис: само его наличие и выданный доступ к уведомлениям
 * позволяют читать активные медиасессии (плеер на острове).
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun accessGranted(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
            }
        }
    }
}