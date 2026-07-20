package com.aibyjohannes.alfred.ui.home

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import com.aibyjohannes.alfred.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ChatAdapterTest {
    @Test
    fun `youtube card keeps thumbnail placeholder at sixteen by nine and opens original URL`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(context)
        var requestedVideoId: String? = null
        val adapter = ChatAdapter(loadYouTubeThumbnail = { videoId, _ -> requestedVideoId = videoId })
        val holder = adapter.onCreateViewHolder(parent, 0)
        val url = "https://youtu.be/dQw4w9WgXcQ?t=4"

        holder.bind(UiChatMessage(id = 1, content = "Watch $url", isUser = false))

        val widgetContainer = holder.itemView.findViewById<LinearLayout>(R.id.widget_container)
        val card = widgetContainer.getChildAt(0) as LinearLayout
        val thumbnailFrame = card.getChildAt(0) as FrameLayout
        val thumbnail = thumbnailFrame.getChildAt(0) as ImageView
        thumbnailFrame.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST)
        )

        assertEquals("dQw4w9WgXcQ", requestedVideoId)
        assertEquals(320, thumbnailFrame.measuredWidth)
        assertEquals(180, thumbnailFrame.measuredHeight)
        assertNull(thumbnail.drawable)
        assertNotNull(thumbnail.background)
        assertTrue(card.contentDescription.contains("YouTube video"))

        card.performClick()
        assertEquals(url, Shadows.shadowOf(context).nextStartedActivity.dataString)
    }

    @Test
    fun `explicit youtube title is included in actionable accessibility text`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(context)
        val adapter = ChatAdapter(loadYouTubeThumbnail = { _, _ -> })
        val holder = adapter.onCreateViewHolder(parent, 0)
        holder.bind(
            UiChatMessage(
                id = 2,
                content = """```alfred-widget
                    |{"type":"youtube","url":"https://youtube.com/shorts/dQw4w9WgXcQ","title":"Kotlin tips"}
                    |```""".trimMargin(),
                isUser = false
            )
        )

        val card = holder.itemView.findViewById<LinearLayout>(R.id.widget_container).getChildAt(0)
        assertTrue(card.contentDescription.contains("Kotlin tips"))
        assertTrue(card.isClickable)
    }

    @Test
    fun `tool payload is formatted collapsible and copied from the complete original`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(context)
        val adapter = ChatAdapter(loadYouTubeThumbnail = { _, _ -> })
        val holder = adapter.onCreateViewHolder(parent, 0)
        val raw = """{"nested":{"value":"${"x".repeat(1_500)}"}}"""
        holder.bind(
            UiChatMessage(
                id = 3,
                content = "Done",
                isUser = false,
                traceItems = listOf(UiTraceItem("call", UiTraceKind.TOOL_CALL, "Tool · lookup", raw))
            )
        )

        val traceContainer = holder.itemView.findViewById<LinearLayout>(R.id.trace_container)
        val body = traceContainer.getChildAt(1) as TextView
        val actions = traceContainer.getChildAt(2) as LinearLayout
        val expand = actions.getChildAt(0) as Button
        val copy = actions.getChildAt(1) as Button

        assertEquals(Typeface.MONOSPACE, body.typeface)
        assertTrue(body.text.endsWith("…"))
        expand.performClick()
        assertTrue(body.text.length > 1_500)
        copy.performClick()

        val clipboard = context.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }
}
