package com.aibyjohannes.alfred.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ChatModelResourcesTest {
    @Test
    fun `remote model list starts with the default and keeps the maintained alternatives`() {
        val file = listOf(
            File("app/src/main/res/values/arrays.xml"),
            File("src/main/res/values/arrays.xml")
        ).first(File::exists)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val arrays = document.getElementsByTagName("string-array")
        val values = (0 until arrays.length)
            .map { arrays.item(it) as Element }
            .first { it.getAttribute("name") == "model_values" }
            .getElementsByTagName("item")
        val actual = (0 until values.length).map { values.item(it).textContent.trim() }

        assertEquals(
            listOf(
                "openai/gpt-5.6-luna",
                "together/Prism-ML/Ternary-Bonsai-27B",
                "deepseek/deepseek-v4-flash",
                "google/gemma-4-31b-it",
                "google/gemma-4-26b-a4b-it",
                "qwen/qwen3-next-80b-a3b-instruct"
            ),
            actual
        )
    }
}
