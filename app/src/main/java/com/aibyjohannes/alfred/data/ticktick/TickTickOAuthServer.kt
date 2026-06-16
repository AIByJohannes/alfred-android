package com.aibyjohannes.alfred.data.ticktick

import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class TickTickOAuthServer(
    private val port: Int = 54321,
    private val onCodeReceived: (String) -> Unit,
    private val onErrorReceived: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.submit {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d("TickTickOAuthServer", "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("TickTickOAuthServer", "Server error", e)
                    onErrorReceived(e.message ?: "Failed to start local OAuth server")
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
        Log.d("TickTickOAuthServer", "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return@Thread
                Log.d("TickTickOAuthServer", "Request: $requestLine")

                // Extract path and query parameters
                // Request line format: "GET /callback?code=123 HTTP/1.1"
                val parts = requestLine.split(" ")
                if (parts.size >= 2 && parts[0] == "GET") {
                    val urlPath = parts[1]
                    if (urlPath.startsWith("/callback")) {
                        val uri = Uri.parse("http://localhost$urlPath")
                        val code = uri.getQueryParameter("code")
                        val error = uri.getQueryParameter("error")

                        val responseHtml = if (code != null) {
                            onCodeReceived(code)
                            """
                            <html>
                            <body style="font-family: sans-serif; text-align: center; padding-top: 50px;">
                                <h1 style="color: #4CAF50;">Authorization Successful!</h1>
                                <p>You can close this window and return to the Alfred Android app.</p>
                            </body>
                            </html>
                            """.trimIndent()
                        } else {
                            onErrorReceived(error ?: "Unknown error")
                            """
                            <html>
                            <body style="font-family: sans-serif; text-align: center; padding-top: 50px;">
                                <h1 style="color: #F44336;">Authorization Failed</h1>
                                <p>Error: ${error ?: "Unknown error"}</p>
                            </body>
                            </html>
                            """.trimIndent()
                        }

                        val bytes = responseHtml.toByteArray(Charsets.UTF_8)
                        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: text/html; charset=utf-8\r\n")
                        writer.write("Content-Length: ${bytes.size}\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.flush()
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()

                        // Stop server asynchronously after handling the request
                        Thread {
                            Thread.sleep(1000)
                            stop()
                        }.start()
                    } else {
                        send404(socket)
                    }
                } else {
                    send404(socket)
                }
            } catch (e: Exception) {
                Log.e("TickTickOAuthServer", "Error handling client", e)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun send404(socket: Socket) {
        try {
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 404 Not Found\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
        } catch (_: Exception) {}
    }
}
