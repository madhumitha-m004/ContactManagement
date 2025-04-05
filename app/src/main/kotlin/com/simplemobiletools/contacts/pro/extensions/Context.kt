package com.simplemobiletools.contacts.pro.extensions

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.FileProvider
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.contacts.pro.BuildConfig
import com.simplemobiletools.contacts.pro.R
import com.simplemobiletools.contacts.pro.helpers.AUTOMATIC_BACKUP_REQUEST_CODE
import com.simplemobiletools.contacts.pro.helpers.Config
import com.simplemobiletools.contacts.pro.helpers.getNextAutoBackupTime
import com.simplemobiletools.contacts.pro.helpers.getPreviousAutoBackupTime
import com.simplemobiletools.contacts.pro.receivers.AutomaticBackupReceiver
import org.joda.time.DateTime
import java.io.File
import java.io.FileOutputStream

val Context.config: Config get() = Config.newInstance(applicationContext)
fun Context.getCachePhotoUri(file: File = getCachePhoto()) = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)

@SuppressLint("UseCompatLoadingForDrawables")
fun Context.getPackageDrawable(packageName: String): Drawable {
    return resources.getDrawable(
        when (packageName) {
            TELEGRAM_PACKAGE -> R.drawable.ic_telegram_rect_vector
            SIGNAL_PACKAGE -> R.drawable.ic_signal_rect_vector
            WHATSAPP_PACKAGE -> R.drawable.ic_whatsapp_rect_vector
            VIBER_PACKAGE -> R.drawable.ic_viber_rect_vector
            else -> R.drawable.ic_threema_rect_vector
        }, theme
    )
}

fun Context.getAutomaticBackupIntent(): PendingIntent {
    val intent = Intent(this, AutomaticBackupReceiver::class.java)
    return PendingIntent.getBroadcast(this, AUTOMATIC_BACKUP_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.scheduleNextAutomaticBackup() {
    if (config.autoBackup) {
        val backupAtMillis = getNextAutoBackupTime().millis
        val pendingIntent = getAutomaticBackupIntent()
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, backupAtMillis, pendingIntent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Context.cancelScheduledAutomaticBackup() {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(getAutomaticBackupIntent())
}

fun Context.checkAndBackupContactsOnBoot() {
    if (config.autoBackup) {
        val previousRealBackupTime = config.lastAutoBackupTime
        val previousScheduledBackupTime = getPreviousAutoBackupTime().millis
        val missedPreviousBackup = previousRealBackupTime < previousScheduledBackupTime
        if (missedPreviousBackup) {
            // device was probably off at the scheduled time so backup now
            backupContacts()
        }
    }
}

fun Context.backupContacts() {
    require(isRPlus())
    ensureBackgroundThread {
        val config = config
        ContactsHelper(this).getContactsToExport(selectedContactSources = config.autoBackupContactSources) { contactsToBackup ->
            if (contactsToBackup.isEmpty()) {
                toast(com.simplemobiletools.commons.R.string.no_entries_for_exporting)
                config.lastAutoBackupTime = DateTime.now().millis
                scheduleNextAutomaticBackup()
                return@getContactsToExport
            }


            val now = DateTime.now()
            val year = now.year.toString()
            val month = now.monthOfYear.ensureTwoDigits()
            val day = now.dayOfMonth.ensureTwoDigits()
            val hours = now.hourOfDay.ensureTwoDigits()
            val minutes = now.minuteOfHour.ensureTwoDigits()
            val seconds = now.secondOfMinute.ensureTwoDigits()

            val filename = config.autoBackupFilename
                .replace("%Y", year, false)
                .replace("%M", month, false)
                .replace("%D", day, false)
                .replace("%h", hours, false)
                .replace("%m", minutes, false)
                .replace("%s", seconds, false)

            val outputFolder = File(config.autoBackupFolder).apply {
                mkdirs()
            }

            var exportFile = File(outputFolder, "$filename.vcf")
            var exportFilePath = exportFile.absolutePath
            val outputStream = try {
                if (hasProperStoredFirstParentUri(exportFilePath)) {
                    val exportFileUri = createDocumentUriUsingFirstParentTreeUri(exportFilePath)
                    if (!getDoesFilePathExist(exportFilePath)) {
                        createSAFFileSdk30(exportFilePath)
                    }
                    applicationContext.contentResolver.openOutputStream(exportFileUri, "wt") ?: FileOutputStream(exportFile)
                } else {
                    var num = 0
                    while (getDoesFilePathExist(exportFilePath) && !exportFile.canWrite()) {
                        num++
                        exportFile = File(outputFolder, "${filename}_${num}.vcf")
                        exportFilePath = exportFile.absolutePath
                    }
                    FileOutputStream(exportFile)
                }
            } catch (e: Exception) {
                showErrorToast(e)
                scheduleNextAutomaticBackup()
                return@getContactsToExport
            }

            val exportResult = try {
                ContactsHelper(this).exportContacts(contactsToBackup, outputStream)
            } catch (e: Exception) {
                showErrorToast(e)
            }

            when (exportResult) {
                ExportResult.EXPORT_OK -> toast(com.simplemobiletools.commons.R.string.exporting_successful)
                else -> toast(com.simplemobiletools.commons.R.string.exporting_failed)
            }

            config.lastAutoBackupTime = DateTime.now().millis
            scheduleNextAutomaticBackup()
        }
    }
}

