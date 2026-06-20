package com.aibyjohannes.alfred.core.search

interface ObsidianClient {
    /**
     * Search for notes in the vault by keyword.
     *
     * @param query       The search terms.
     * @param directory   Optional sub-folder path to restrict the search (e.g. "Projects/2025").
     *                    Pass null or blank to search the whole vault.
     * @param sortBy      How to sort results: "score" (default), "modified", or "filename".
     * @param order       Sort direction: "desc" (default) or "asc".
     */
    suspend fun search(
        query: String,
        directory: String? = null,
        sortBy: String = "score",
        order: String = "desc"
    ): Result<String>

    /**
     * List the immediate contents of a folder in the vault.
     *
     * @param path  The relative folder path to list (empty string = vault root).
     * @return A formatted listing of subfolders and .md files with modification dates.
     */
    suspend fun listFolder(path: String = ""): Result<String>

    suspend fun read(path: String): Result<String>
    suspend fun write(path: String, content: String, append: Boolean): Result<String>
}
