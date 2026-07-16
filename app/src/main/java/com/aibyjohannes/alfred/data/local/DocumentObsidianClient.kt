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

    private val db = NoteSearchIndexDatabase.get(context)
    private val indexer = VaultSearchIndexer(context, db)

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

            val isIndexEmpty = db.dao().getAllMetadata().isEmpty()
            val hits = if (isIndexEmpty) {
                // Fallback to disk scan
                val entries = mutableListOf<FileEntry>()
                findMarkdownFilesRecursively(startDir, "", entries)

                entries.mapNotNull { entry ->
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
            } else {
                val ftsQuery = FtsQueryBuilder.build(query)
                if (ftsQuery.isBlank()) {
                    return@withContext Result.success("No matching notes found.")
                }
                val dbHits = db.dao().search(ftsQuery, 200)
                dbHits.mapNotNull { entity ->
                    if (!directory.isNullOrBlank()) {
                        val normalizedDir = directory.trimEnd('/')
                        val isWithinDir = entity.path.startsWith("$normalizedDir/") || entity.path == normalizedDir
                        if (!isWithinDir) return@mapNotNull null
                    }
                    val fileName = entity.path.substringAfterLast('/')
                    val haystack = "$fileName ${entity.content}"
                    val score = score(haystack, terms)
                    if (score > 0) {
                        SearchResultHit(
                            path = entity.path,
                            snippet = buildSnippet(entity.content, terms),
                            score = score,
                            modifiedMs = entity.modifiedAt
                        )
                    } else {
                        null
                    }
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
            val notePath = validateNotePath(path).getOrElse { return@withContext Result.failure(it) }
            val file = findFileByPath(notePath)
                ?: return@withContext Result.failure(Exception("Note not found: $notePath"))
            val text = readFileText(file)
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun create(path: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val notePath = validateNotePath(path).getOrElse { return@withContext Result.failure(it) }
            if (findFileByPath(notePath) != null) {
                return@withContext Result.failure(Exception("Note already exists: $notePath"))
            }
            val file = createFileByPath(notePath)
                ?: return@withContext Result.failure(Exception("Could not create note: $notePath"))
            writeFileText(file, content, append = false)
            indexer.indexSingleFile(rootUri, notePath)
            Result.success("Successfully created note at relative path: $notePath")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun update(path: String, content: String, append: Boolean): Result<String> = withContext(Dispatchers.IO) {
        try {
            val notePath = validateNotePath(path).getOrElse { return@withContext Result.failure(it) }
            val file = findFileByPath(notePath)
                ?: return@withContext Result.failure(Exception("Note not found: $notePath"))

            writeFileText(file, content, append)
            indexer.indexSingleFile(rootUri, notePath)

            val action = if (append) "appended to" else "updated"
            Result.success("Successfully $action note at relative path: $notePath")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rename(fromPath: String, toPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sourcePath = validateNotePath(fromPath).getOrElse { return@withContext Result.failure(it) }
            val destinationPath = validateNotePath(toPath).getOrElse { return@withContext Result.failure(it) }
            if (sourcePath == destinationPath) {
                return@withContext Result.failure(Exception("Source and destination paths must differ."))
            }
            val source = findFileByPath(sourcePath)
                ?: return@withContext Result.failure(Exception("Note not found: $sourcePath"))
            if (findFileByPath(destinationPath) != null) {
                return@withContext Result.failure(Exception("Destination note already exists: $destinationPath"))
            }

            val sameParent = parentPath(sourcePath) == parentPath(destinationPath)
            val renamed = if (sameParent) {
                source.renameTo(destinationPath.substringAfterLast('/'))
            } else {
                val destination = createFileByPath(destinationPath)
                    ?: return@withContext Result.failure(Exception("Could not create destination note: $destinationPath"))
                writeFileText(destination, readFileText(source), append = false)
                if (!source.delete()) {
                    destination.delete()
                    return@withContext Result.failure(Exception("Could not delete original note after rename: $sourcePath"))
                }
                true
            }

            if (!renamed) {
                return@withContext Result.failure(Exception("Could not rename note: $sourcePath"))
            }
            db.dao().deleteByPath(sourcePath)
            indexer.indexSingleFile(rootUri, destinationPath)
            Result.success("Successfully renamed note from $sourcePath to $destinationPath")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val notePath = validateNotePath(path).getOrElse { return@withContext Result.failure(it) }
            val file = findFileByPath(notePath)
                ?: return@withContext Result.failure(Exception("Note not found: $notePath"))
            if (!file.delete()) {
                return@withContext Result.failure(Exception("Could not delete note: $notePath"))
            }
            db.dao().deleteByPath(notePath)
            Result.success("Successfully deleted note at relative path: $notePath")
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
        return query.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun score(content: String, terms: List<String>): Int {
        val normalized = content.lowercase(Locale.US)
        return terms.sumOf { term ->
            // Kotlin/JVM already applies Unicode-aware case folding for text terms;
            // the Java-only (?U) flag is rejected by Android's regex engine.
            Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
                .findAll(normalized)
                .count()
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

    private fun writeFileText(file: DocumentFile, content: String, append: Boolean) {
        val mode = if (append) "wa" else "wt"
        val stream = context.contentResolver.openOutputStream(file.uri, mode)
            ?: throw IllegalStateException("Could not open note for writing: ${file.name.orEmpty()}")
        stream.bufferedWriter().use { writer ->
            if (append && readFileText(file).isNotEmpty()) {
                writer.newLine()
            }
            writer.write(content)
        }
    }

    private fun validateNotePath(path: String): Result<String> {
        val normalized = path.trim()
        if (normalized.isBlank()) {
            return Result.failure(Exception("Note path must not be blank."))
        }
        if (
            normalized.startsWith("/") ||
            normalized.startsWith("\\") ||
            normalized.contains("\\") ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized)
        ) {
            return Result.failure(Exception("Note path must be relative to the vault and use forward slashes."))
        }
        val segments = normalized.split("/")
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return Result.failure(Exception("Note path must not contain empty, current, or parent directory segments."))
        }
        if (segments.any { it.startsWith(".") }) {
            return Result.failure(Exception("Note path must not target hidden files or folders."))
        }
        if (!segments.last().endsWith(".md", ignoreCase = true)) {
            return Result.failure(Exception("Note path must end with .md."))
        }
        return Result.success(segments.joinToString("/"))
    }

    private fun parentPath(path: String): String {
        return path.substringBeforeLast('/', missingDelimiterValue = "")
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

    private fun createFileByPath(path: String, mimeType: String = "text/markdown"): DocumentFile? {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (i in 0 until segments.size - 1) {
            current = findChild(context, current, segments[i])?.takeIf { it.isDirectory }
                ?: current.createDirectory(segments[i])
                ?: return null
        }
        val filename = segments.last()
        if (findChild(context, current, filename) != null) return null
        return current.createFile(mimeType, filename)
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
