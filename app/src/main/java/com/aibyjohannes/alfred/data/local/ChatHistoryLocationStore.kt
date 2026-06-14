package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri

class ChatHistoryLocationStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var parentFolderUri: Uri?
        get() = prefs.getString(KEY_PARENT_URI, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_PARENT_URI, value?.toString()).apply()
        }

    fun hasUsableFolder(): Boolean {
        val uri = parentFolderUri ?: return false
        return appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun persistFolder(uri: Uri) {
        val persistableFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        appContext.contentResolver.takePersistableUriPermission(uri, persistableFlags)
        parentFolderUri = uri
    }

    fun createStorage(): DocumentChatHistoryStorage? {
        val uri = parentFolderUri ?: return null
        if (!hasUsableFolder()) {
            return null
        }
        return DocumentChatHistoryStorage.fromParentUri(appContext, uri)
    }

    companion object {
        private const val PREFS_NAME = "chat_history_location"
        private const val KEY_PARENT_URI = "parent_folder_uri"
    }
}
