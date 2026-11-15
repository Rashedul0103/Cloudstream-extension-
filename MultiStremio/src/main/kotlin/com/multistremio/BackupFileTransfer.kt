package com.multistremio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Android's Storage Access Framework is used so users can save backups to Downloads, external
 * storage or a cloud document provider without granting broad storage permissions.
 */
internal object BackupFileTransfer {
    private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

    fun exportText(ctx: Context, contents: String, onSaved: () -> Unit) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "MultiStremio-backup-$timestamp.txt")
        }
        launchPicker(ctx, intent) { activity, uri ->
            runCatching {
                val output = activity.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Could not open the selected file")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(contents)
                }
            }.onSuccess {
                toast(activity, "Backup exported as a TXT file.")
                onSaved()
            }.onFailure {
                toast(activity, "Could not write the backup file. Choose another location and try again.")
            }
        }
    }

    fun importText(ctx: Context, onLoaded: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "application/json", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchPicker(ctx, intent) { activity, uri ->
            val contents = runCatching { readText(activity, uri) }
                .getOrElse {
                    toast(
                        activity,
                        it.message?.takeIf { message -> message.contains("10 MB") }
                            ?: "Could not read that file. Select a readable TXT backup.",
                    )
                    return@launchPicker
                }
            if (contents.isBlank()) {
                toast(activity, "The selected file is empty.")
                return@launchPicker
            }
            onLoaded(contents)
        }
    }

    private fun launchPicker(
        ctx: Context,
        intent: Intent,
        onPicked: (Activity, Uri) -> Unit,
    ) {
        val activity = findActivity(ctx)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            toast(ctx, "The system file picker is unavailable here. Use the copy/paste backup option.")
            return
        }
        val registryOwner = activity as? androidx.activity.result.ActivityResultRegistryOwner
        if (registryOwner == null) {
            toast(activity, "This CloudStream screen cannot receive file-picker results. Use copy/paste backup.")
            return
        }

        val key = "multistremio-backup-${UUID.randomUUID()}"
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = registryOwner.activityResultRegistry.register(
            key,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            if (result.resultCode != Activity.RESULT_OK) return@register
            val uri = result.data?.data
            if (uri == null) {
                toast(activity, "The file picker did not return a file.")
                return@register
            }
            if (!activity.isFinishing && !activity.isDestroyed) onPicked(activity, uri)
        }

        runCatching { launcher.launch(intent) }.onFailure {
            launcher.unregister()
            toast(activity, "Could not open the system file picker.")
        }
    }

    private fun findActivity(context: Context): Activity? {
        var current = context
        val visited = HashSet<Context>()
        while (visited.add(current)) {
            if (current is Activity) return current
            if (current !is ContextWrapper) return null
            current = current.baseContext
        }
        return null
    }

    private fun readText(activity: Activity, uri: Uri): String {
        val input = activity.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BACKUP_BYTES) {
                    throw IOException("Backup files must be 10 MB or smaller")
                }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
