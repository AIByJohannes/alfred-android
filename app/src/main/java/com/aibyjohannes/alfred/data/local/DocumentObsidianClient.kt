package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.aibyjohannes.alfred.core.search.ObsidianClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentObsidianClient(
    private val context: Context,
    private val rootUri: Uri
) : ObsidianClient {

    // ── Data types ─────────────────────────────────────────────────────────────

    private data class SearchResultHit(
        val path: String,
        val snippet: String,
        val score: Int,
        val modifiedMs: Long
    )

    private data class FileEntry(
        val relPath: String,
        val file: DocumentFile,
        val modifiedMs: Long
    )

    private data class FolderEntry(
        val name: String,
        val isDir: Boolean,
        val modifiedMs: Long
    )

    // ── Public interface ───────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        directory: String?,
        sortBy: String,
        order: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@withContext Result.failure(Exception("Cannot open Obsidian vault root folder"))

            // Resolve the starting directory
            val startDir = if (!directory.isNullOrBlank()) {
                findDirectoryByPath(directory)
                    ?: return@withContext Result.failure(
                        Exception("Folder not found in vault: $directory")
                    )
            } else {
                root
            }

            val terms = normalizeTerms(query)
            if (terms.isEmpty()) {
                return@withContext Result.success("No matching notes found.")
            }

            val entries = mutableListOf<FileEntry>()
            findMarkdownFilesRecursively(startDir, "", entries)

            val hits = entries.mapNotNull { entry ->
                val fileName = entry.file.name ?: ""
                val content = readFileText(entry.file)
                val haystack = "$fileName $content"
                val score = score(haystack, terms)
                if (score > 0) {
                    SearchResultHit(
                        path = if (!directory.isNullOrBlank()) "${directory.trimEnd('/')}/${entry.relPath}" else entry.relPath,
                        snippet = buildSnippet(content, terms),
                        score = score,
                        modifiedMs = entry.modifiedMs
                    )
                } else {
                    null
                }
            }

            val sorted = sortHits(hits, sortBy, order)

            if (sorted.isEmpty()) {
                val scope = if (!directory.isNullOrBlank()) " in folder '$directory'" else ""
                Result.success("No matching notes found$scope.")
            } else {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val formatted = buildString {
                    val scope = if (!directory.isNullOrBlank()) " in '$directory'" else ""
                    appendLine("Found ${sorted.size} matching notes$scope:")
                    sorted.take(10).forEachIndexed { index, hit ->
                        val modDate = dateFormat.format(Date(hit.modifiedMs))
                        appendLine("${index + 1}. [${hit.path}] (score: ${hit.score}, modified: $modDate)")
                        appendLine("   ${hit.snippet}")
                    }
                }.trim()
                Result.success(formatted)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listFolder(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = if (path.isBlank()) {
                DocumentFile.fromTreeUri(context, rootUri)
                    ?: return@withContext Result.failure(Exception("Cannot open Obsidian vault root folder"))
            } else {
                findDirectoryByPath(path)
                    ?: return@withContext Result.failure(Exception("Folder not found in vault: $path"))
            }

            val entries = listDirectChildren(dir)

            if (entries.isEmpty()) {
                val label = if (path.isBlank()) "vault root" else "'$path'"
                return@withContext Result.success("No files or folders found in $label.")
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val label = if (path.isBlank()) "vault root" else "'$path'"
            val formatted = buildString {
                appendLine("Contents of $label (${entries.size} items):")
                appendLine()
                val dirs = entries.filter { it.isDir }.sortedBy { it.name.lowercase() }
                val files = entries.filter { !it.isDir }.sortedBy { it.name.lowercase() }
                for (d in dirs) {
                    appendLine("[DIR]  ${d.name}/")
                }
                for (f in files) {
                    val modDate = dateFormat.format(Date(f.modifiedMs))
                    appendLine("[FILE] ${f.name}  (modified: $modDate)")
                }
            }.trim()
            Result.success(formatted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun read(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = findFileByPath(path)
                ?: return@withContext Result.failure(Exception("Note not found: $path"))
            val text = readFileText(file)
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun write(path: String, content: String, append: Boolean): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = getOrCreateFileByPath(path)
                ?: return@withContext Result.failure(Exception("Could not create note: $path"))

            val mode = if (append) "wa" else "wt"
            val stream = context.contentResolver.openOutputStream(file.uri, mode)
                ?: return@withContext Result.failure(Exception("Could not open note for writing: $path"))

            stream.bufferedWriter().use { writer ->
                if (append && readFileText(file).isNotEmpty()) {
                    writer.newLine()
                }
                writer.write(content)
            }
            val action = if (append) "appended to" else "written to"
            Result.success("Successfully $action note at relative path: $path")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Sorting ────────────────────────────────────────────────────────────────

    private fun sortHits(
        hits: List<SearchResultHit>,
        sortBy: String,
        order: String
    ): List<SearchResultHit> {
        val comparator: Comparator<SearchResultHit> = when (sortBy.lowercase()) {
            "modified" -> compareBy { it.modifiedMs }
            "filename" -> compareBy { it.path.lowercase() }
            else /* "score" */ -> compareBy { it.score }
        }
        return if (order.lowercase() == "asc") {
            hits.sortedWith(comparator)
        } else {
            hits.sortedWith(comparator.reversed())
        }
    }

    // ── Text helpers ───────────────────────────────────────────────────────────

    private fun normalizeTerms(query: String): List<String> {
        return query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun score(content: String, terms: List<String>): Int {
        val normalized = content.lowercase()
        return terms.sumOf { term ->
            Regex("\\b${Regex.escape(term)}\\b").findAll(normalized).count()
        }
    }

    private fun buildSnippet(content: String, terms: List<String>): String {
        val lower = content.lowercase()
        val firstMatch = terms.mapNotNull { lower.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull() ?: 0
        val start = (firstMatch - 80).coerceAtLeast(0)
        val end = (firstMatch + 220).coerceAtMost(content.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < content.length) "..." else ""
        return prefix + content.substring(start, end).trim() + suffix
    }

    private fun readFileText(file: DocumentFile): String {
        return context.contentResolver.openInputStream(file.uri)?.use { stream ->
            stream.bufferedReader().readText()
        } ?: ""
    }

    // ── SAF navigation ─────────────────────────────────────────────────────────

    /**
     * Navigate to a relative folder path from the vault root.
     * Returns null if any segment in the path does not exist or is not a directory.
     */
    private fun findDirectoryByPath(path: String): DocumentFile? {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return DocumentFile.fromTreeUri(context, rootUri)
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in segments) {
            current = findChild(context, current, segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    private fun findFileByPath(path: String): DocumentFile? {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (i in 0 until segments.size - 1) {
            current = findChild(context, current, segments[i])?.takeIf { it.isDirectory } ?: return null
        }
        return findChild(context, current, segments.last())?.takeIf { it.isFile }
    }

    private fun getOrCreateFileByPath(path: String, mimeType: String = "text/markdown"): DocumentFile? {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (i in 0 until segments.size - 1) {
            current = findChild(context, current, segments[i])?.takeIf { it.isDirectory }
                ?: current.createDirectory(segments[i])
                ?: return null
        }
        val filename = segments.last()
        return findChild(context, current, filename)?.takeIf { it.isFile }
            ?: current.createFile(mimeType, filename)
    }

    private fun findChild(context: Context, parent: DocumentFile, displayName: String): DocumentFile? {
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

    /**
     * List immediate children of a directory, returning name, isDir, and last-modified timestamp.
     */
    private fun listDirectChildren(parent: DocumentFile): List<FolderEntry> {
        val result = mutableListOf<FolderEntry>()
        val parentDocId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        ) ?: return result
        cursor.use {
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val mimeType = it.getString(mimeIndex)
                val modifiedMs = it.getLong(modIndex)
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                // Include directories and .md files only
                if (isDir || name.endsWith(".md", ignoreCase = true)) {
                    result.add(FolderEntry(name, isDir, modifiedMs))
                }
            }
        }
        return result
    }

    /**
     * Recursively collect .md files under [parent], populating their last-modified timestamps.
     * The [currentPath] is relative to the starting directory passed to [search].
     */
    private fun findMarkdownFilesRecursively(
        parent: DocumentFile,
        currentPath: String,
        list: MutableList<FileEntry>
    ) {
        if (list.size >= 100) return
        val parentDocId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        ) ?: return
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val tempDirectories = mutableListOf<Pair<String, DocumentFile>>()
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val mimeType = it.getString(mimeIndex)
                val docId = it.getString(idIndex)
                val modifiedMs = it.getLong(modIndex)
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, docId)
                if (isDir) {
                    val dirFile = DocumentFile.fromTreeUri(context, fileUri) ?: continue
                    val nextPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    tempDirectories.add(nextPath to dirFile)
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    val mdFile = DocumentFile.fromSingleUri(context, fileUri) ?: continue
                    val relPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    list.add(FileEntry(relPath, mdFile, modifiedMs))
                    if (list.size >= 100) break
                }
            }
            for ((dirPath, dirFile) in tempDirectories) {
                findMarkdownFilesRecursively(dirFile, dirPath, list)
                if (list.size >= 100) break
            }
        }
    }
}
