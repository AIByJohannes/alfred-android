package com.aibyjohannes.alfred.ui.home

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class ChatMessageLayoutTest {

    @Test
    fun testTraceContainerIsAboveMessageText() {
        val path1 = File("app/src/main/res/layout/item_chat_message.xml")
        val path2 = File("src/main/res/layout/item_chat_message.xml")
        val file = if (path1.exists()) path1 else if (path2.exists()) path2 else {
            fail("Layout XML file not found at $path1 or $path2")
            return
        }

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(file)
        doc.documentElement.normalize()

        // Find trace_container and message_text nodes in document order
        val elements = mutableListOf<Element>()
        fun collectElements(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val id = element.getAttribute("android:id")
                if (id == "@+id/trace_container" || id == "@+id/message_text") {
                    elements.add(element)
                }
            }
            var child = node.firstChild
            while (child != null) {
                collectElements(child)
                child = child.nextSibling
            }
        }

        collectElements(doc.documentElement)

        // We expect exactly two elements if they both exist
        val ids = elements.map { it.getAttribute("android:id") }
        assertTrue("Both trace_container and message_text must exist in layout. Found: $ids", ids.size == 2)
        
        // Assert that trace_container comes before message_text
        assertTrue(
            "trace_container must be defined before message_text in layout file. Found order: $ids",
            ids[0] == "@+id/trace_container" && ids[1] == "@+id/message_text"
        )
    }
}
