package com.aibyjohannes.alfred.ui.home

import android.app.Activity
import android.widget.FrameLayout
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class DrawerProjectsAdapterTest {
    private val workspaces = listOf(UiWorkspace("personal", "Personal"), UiWorkspace("work", "Work"))

    @Test
    fun `workspace refresh superseding selection diff still rebinds both highlights`() {
        val background = QueuedExecutor()
        val main = QueuedExecutor()
        val adapter = createAdapter(background, main)
        adapter.submitData(workspaces, "personal")
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val holders = workspaces.map { adapter.onCreateViewHolder(FrameLayout(context), 0) }
        holders.forEachIndexed { index, holder -> adapter.onBindViewHolder(holder, index) }
        assertTrue(holders[0].itemView.isSelected)
        assertFalse(holders[1].itemView.isSelected)

        val changedPositions = mutableSetOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                repeat(itemCount) { offset ->
                    val position = positionStart + offset
                    changedPositions += position
                    adapter.onBindViewHolder(holders[position], position)
                }
            }
        })

        adapter.submitData(workspaces, "work")
        // The workspaces observer can emit again before the selection diff commits.
        adapter.submitData(workspaces.toList(), "work")
        background.drain()
        main.drain()

        assertEquals(setOf(0, 1), changedPositions)
        assertFalse(holders[0].itemView.isSelected)
        assertTrue(holders[1].itemView.isSelected)
    }

    @Test
    fun `rapid switches keep latest selection and row actions use current workspace`() {
        val background = QueuedExecutor()
        val main = QueuedExecutor()
        var clicked: UiWorkspace? = null
        var longPressed: UiWorkspace? = null
        val adapter = createAdapter(background, main,
            onSelected = { clicked = it }, onLongPressed = { longPressed = it })
        adapter.submitData(workspaces, "personal")
        adapter.submitData(workspaces, "work")
        adapter.submitData(workspaces.reversed(), "personal")
        background.drain()
        main.drain()

        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 1)
        assertTrue(holder.itemView.isSelected)
        adapter.onBindViewHolder(holder, 0)
        assertFalse(holder.itemView.isSelected)
        holder.itemView.performClick()
        holder.itemView.performLongClick()
        assertEquals(workspaces[1], clicked)
        assertEquals(workspaces[1], longPressed)
        assertEquals(listOf(false, true), adapter.currentList.map { it.isActive })
    }

    private fun createAdapter(
        background: Executor,
        main: Executor,
        onSelected: (UiWorkspace) -> Unit = {},
        onLongPressed: (UiWorkspace) -> Unit = {}
    ) = DrawerProjectsAdapter(
        onWorkspaceSelected = onSelected,
        onWorkspaceLongPressed = { workspace, _ -> onLongPressed(workspace) },
        differConfig = AsyncDifferConfig.Builder(DrawerProjectsAdapter.WorkspaceDiffCallback())
            .setBackgroundThreadExecutor(background)
            .setMainThreadExecutor(main)
            .build()
    )

    private class QueuedExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
