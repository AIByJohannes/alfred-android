package com.aibyjohannes.alfred.core.search

/** Safe, Android-local subset of the Obsidian CLI. It never launches a shell or accepts pipes. */
class ObsidianCliEmulator(private val client: ObsidianClient) {
    suspend fun execute(commandLine: String): Result<String> {
        val tokens = tokenize(commandLine).getOrElse { return Result.failure(it) }
        if (tokens.firstOrNull() != "obsidian") return Result.failure(IllegalArgumentException("Commands must start with 'obsidian'."))
        val command = tokens.getOrNull(1)?.lowercase()
            ?: return Result.failure(IllegalArgumentException("Missing Obsidian command."))
        val args = tokens.drop(2).associate { token ->
            val key = token.substringBefore('=')
            key to token.substringAfter('=', "true")
        }
        fun required(name: String): String = args[name]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required argument: $name")
        return try { when (command) {
            "read" -> client.read(required("path"))
            "search", "search:context" -> client.search(
                required("query"), args["folder"], args["sort"] ?: "score", args["order"] ?: "desc"
            )
            "files", "folders" -> client.listFolder(args["folder"].orEmpty())
            "create" -> client.create(required("path"), args["content"].orEmpty())
            "write" -> client.update(required("path"), args["content"].orEmpty(), append = false)
            "append" -> client.update(required("path"), args["content"].orEmpty(), append = true)
            "rename", "move" -> client.rename(required("path"), required("to"))
            "delete" -> client.delete(required("path"))
            else -> Result.failure(IllegalArgumentException("Unsupported Obsidian CLI command: $command"))
        } } catch (error: Exception) { Result.failure(error) }
    }

    companion object {
        fun tokenize(input: String): Result<List<String>> = runCatching {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            input.trim().forEach { char ->
                when {
                    quote != null && char == quote -> quote = null
                    quote == null && (char == '\'' || char == '"') -> quote = char
                    quote == null && char.isWhitespace() -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                    else -> current.append(char)
                }
            }
            require(quote == null) { "Unterminated quoted argument." }
            if (current.isNotEmpty()) tokens += current.toString()
            tokens
        }
    }
}
