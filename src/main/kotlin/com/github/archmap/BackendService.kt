package com.github.archmap

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.PROJECT)
class BackendService(private val project: Project) {

    private val log = Logger.getInstance(BackendService::class.java)
    private val gson = Gson()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    var baseUrl: String = "http://127.0.0.1:8742"

    fun initProject(): JsonObject? {
        val projectRoot = project.basePath ?: return null
        val body = gson.toJson(mapOf("projectRoot" to projectRoot))
        return post("/init", body)
    }

    fun updateFile(filePath: String): JsonObject? {
        val projectRoot = project.basePath ?: return null
        val body = gson.toJson(mapOf("projectRoot" to projectRoot, "filePath" to filePath))
        return post("/update", body)
    }

    fun getNodeDetail(nodeId: String): JsonObject? {
        return get("/node/$nodeId")
    }

    private fun post(path: String, jsonBody: String): JsonObject? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                gson.fromJson(response.body(), JsonObject::class.java)
            } else {
                log.warn("Backend $path returned ${response.statusCode()}: ${response.body()}")
                null
            }
        } catch (e: Exception) {
            log.warn("Backend call $path failed: ${e.message}")
            null
        }
    }

    private fun get(path: String): JsonObject? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                gson.fromJson(response.body(), JsonObject::class.java)
            } else {
                log.warn("Backend GET $path returned ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            log.warn("Backend GET $path failed: ${e.message}")
            null
        }
    }

    companion object {
        fun getInstance(project: Project): BackendService =
            project.getService(BackendService::class.java)
    }
}
