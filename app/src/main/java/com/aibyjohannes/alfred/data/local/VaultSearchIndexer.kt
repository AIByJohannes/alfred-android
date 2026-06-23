package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal class VaultSearchIndexer(
    private val context: Context,
    private val database: NoteSearchIndexDatabase
) {
    private val dao = database.dao()

    suspend fun syncIndex(rootUri: Uri) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext
        val scannedFiles = mutableListOf<ScannedFile>()
        scanMarkdownFiles(root, "", scannedFiles)

        val dbMetadata = dao.getAllMetadata().associateBy { it.path }

        // 1. Reindex new or modified files
        for (scanned in scannedFiles) {
            val dbItem = dbMetadata[scanned.path]
            if (dbItem == null || dbItem.modifiedAt != scanned.modifiedMs || dbItem.sizeBytes != scanned.sizeBytes) {
                indexFile(scanned.path, scanned.file, scanned.modifiedMs, scanned.sizeBytes)
            }
        }

        // 2. Remove deleted files from index
        val scannedPathsSet = scannedFiles.map { it.path }.toSet()
        if (scannedPathsSet.isEmpty()) {
            dao.clearAll()
        } else {
            val dbPaths = dbMetadata.keys
            val pathsToDelete = dbPaths.filter { it !in scannedPathsSet }
            if (pathsToDelete.isNotEmpty()) {
                pathsToDelete.chunked(500).forEach { chunk ->
                    dao.deletePaths(chunk)
                }
            }
        }
        dao.rebuildFts()
    }

    suspend fun indexSingleFile(rootUri: Uri, relPath: String) = withContext(Dispatchers.IO) {
        val file = findFileByPath(rootUri, relPath) ?: return@withContext
        indexFile(relPath, file, file.lastModified(), file.length())
    }

    suspend fun removeDeletedFile(relPath: String) = withContext(Dispatchers.IO) {
        dao.deleteByPath(relPath)
    }

    suspend fun rebuildIndex(rootUri: Uri) = withContext(Dispatchers.IO) {
        dao.clearAll()
        syncIndex(rootUri)
    }

    private suspend fun indexFile(
        relPath: String,
        file: DocumentFile,
        modifiedMs: Long,
        sizeBytes: Long
    ) {
        val content = readFileText(file)
        val existing = dao.getByPath(relPath)
        val entity = NoteSearchIndexEntity(
            id = existing?.id ?: 0,
            path = relPath,
            title = extractTitle(content, relPath),
            content = content,
            modifiedAt = modifiedMs,
            sizeBytes = sizeBytes,
            indexedAt = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    private fun readFileText(file: DocumentFile): String {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun scanMarkdownFiles(
        parent: DocumentFile,
        currentPath: String,
        list: MutableList<ScannedFile>
    ) {
        val parentDocId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        ) ?: return
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val tempDirectories = mutableListOf<Pair<String, DocumentFile>>()
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                if (name.startsWith(".")) continue // Skip hidden folders like .obsidian, .trash
                val mimeType = it.getString(mimeIndex)
                val docId = it.getString(idIndex)
                val modifiedMs = it.getLong(modIndex)
                val sizeBytes = it.getLong(sizeIndex)
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, docId)
                if (isDir) {
                    val dirFile = DocumentFile.fromTreeUri(context, fileUri) ?: continue
                    val nextPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    tempDirectories.add(nextPath to dirFile)
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    val mdFile = DocumentFile.fromSingleUri(context, fileUri) ?: continue
                    val relPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    list.add(ScannedFile(relPath, mdFile, modifiedMs, sizeBytes))
                }
            }
            for ((dirPath, dirFile) in tempDirectories) {
                scanMarkdownFiles(dirFile, dirPath, list)
            }
        }
    }

    private fun findFileByPath(rootUri: Uri, path: String): DocumentFile? {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (i in 0 until segments.size - 1) {
            current = findChild(current, segments[i])?.takeIf { it.isDirectory } ?: return null
        }
        return findChild(current, segments.last())?.takeIf { it.isFile }
    }

    private fun findChild(parent: DocumentFile, displayName: String): DocumentFile? {
        try {
            val file = parent.findFile(displayName)
            if (file != null) return file
        } catch (_: Exception) {}

        val parentDocId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        ) ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                if (displayName.equals(name, ignoreCase = true)) {
                    val docId = it.getString(idIndex)
                    val mimeType = it.getString(mimeIndex)
                    val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, docId)
                    return if (isDir) {
                        DocumentFile.fromTreeUri(context, fileUri)
                    } else {
                        DocumentFile.fromSingleUri(context, fileUri)
                    }
                }
            }
        }
        return null
    }

    private data class ScannedFile(
        val path: String,
        val file: DocumentFile,
        val modifiedMs: Long,
        val sizeBytes: Long
    )

    companion object {
        fun extractTitle(content: String, relPath: String): String {
            // Find first H1 heading: e.g. "^#[ \t]+(.+)$"
            val h1Regex = Regex("""^#[ \t]+(.+)$""", RegexOption.MULTILINE)
            val match = h1Regex.find(content)
            if (match != null) {
                val title = match.groupValues[1].trim()
                if (title.isNotEmpty()) return title
            }
            // Fallback: filename without path and without extension
            val filename = relPath.substringAfterLast('/')
            return filename.removeSuffix(".md").removeSuffix(".MD")
        }
    }
}
