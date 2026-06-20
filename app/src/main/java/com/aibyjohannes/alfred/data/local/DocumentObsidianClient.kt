package com.aibyjohannes.alfred.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.aibyjohannes.alfred.core.search.ObsidianClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentObsidianClient(
    private val context: Context,
    private val rootUri: Uri
) : ObsidianClient {

    override suspend fun search(query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@withContext Result.failure(Exception("Cannot open Obsidian vault root folder"))

            val terms = normalizeTerms(query)
            if (terms.isEmpty()) {
                return@withContext Result.success("No matching notes found.")
            }

            val mdFiles = mutableListOf<Pair<String, DocumentFile>>()
            findMarkdownFilesRecursively(context, root, "", mdFiles)

            val hits = mdFiles.mapNotNull { (relPath, file) ->
                val fileName = file.name ?: ""
                val content = readFileText(file)
                val haystack = "$fileName $content"
                val score = score(haystack, terms)
                if (score > 0) {
                    SearchResultHit(
                        path = relPath,
                        snippet = buildSnippet(content, terms),
                        score = score
                    )
                } else {
                    null
                }
            }.sortedByDescending { it.score }

            if (hits.isEmpty()) {
                Result.success("No matching notes found.")
            } else {
                val formatted = buildString {
                    appendLine("Found ${hits.size} matching notes in your Obsidian vault:")
                    hits.take(10).forEachIndexed { index, hit ->
                        appendLine("${index + 1}. [${hit.path}] (score: ${hit.score})")
                        appendLine("   ${hit.snippet}")
                    }
                }.trim()
                Result.success(formatted)
            }
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

    private data class SearchResultHit(
        val path: String,
        val snippet: String,
        val score: Int
    )

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

    private fun findMarkdownFilesRecursively(
        context: Context,
        parent: DocumentFile,
        currentPath: String,
        list: MutableList<Pair<String, DocumentFile>>
    ) {
        if (list.size >= 100) return
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
        ) ?: return
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val tempDirectories = mutableListOf<Pair<String, DocumentFile>>()
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val mimeType = it.getString(mimeIndex)
                val docId = it.getString(idIndex)
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, docId)
                if (isDir) {
                    val dirFile = DocumentFile.fromTreeUri(context, fileUri) ?: continue
                    val nextPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    tempDirectories.add(nextPath to dirFile)
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    val mdFile = DocumentFile.fromSingleUri(context, fileUri) ?: continue
                    val relPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    list.add(relPath to mdFile)
                    if (list.size >= 100) break
                }
            }
            for ((dirPath, dirFile) in tempDirectories) {
                findMarkdownFilesRecursively(context, dirFile, dirPath, list)
                if (list.size >= 100) break
            }
        }
    }
}
