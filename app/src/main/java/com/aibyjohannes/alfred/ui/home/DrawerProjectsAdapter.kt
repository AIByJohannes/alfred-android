package com.aibyjohannes.alfred.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.databinding.ItemDrawerProjectBinding

class DrawerProjectsAdapter(
    private val onWorkspaceSelected: (UiWorkspace) -> Unit,
    private val onWorkspaceLongPressed: (UiWorkspace, View) -> Unit
) : ListAdapter<UiWorkspace, DrawerProjectsAdapter.ProjectViewHolder>(WorkspaceDiffCallback()) {

    private var activeWorkspaceId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding = ItemDrawerProjectBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProjectViewHolder(binding, onWorkspaceSelected, onWorkspaceLongPressed)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(getItem(position), activeWorkspaceId)
    }

    fun submitData(list: List<UiWorkspace>, activeId: Long?) {
        activeWorkspaceId = activeId
        submitList(list)
    }

    class ProjectViewHolder(
        private val binding: ItemDrawerProjectBinding,
        private val onWorkspaceSelected: (UiWorkspace) -> Unit,
        private val onWorkspaceLongPressed: (UiWorkspace, View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(workspace: UiWorkspace, activeId: Long?) {
            val context = binding.root.context
            val isActive = workspace.id == activeId

            binding.projectName.text = workspace.name

            val iconRes = ICONS[Math.floorMod(workspace.id, ICONS.size).toInt()]
            val tintColor = COLORS[Math.floorMod(workspace.id, COLORS.size).toInt()]

            binding.projectIcon.setImageResource(iconRes)
            binding.projectIcon.imageTintList = ColorStateList.valueOf(tintColor)

            // Stylize based on active state
            if (isActive) {
                binding.projectName.textColor = Color.WHITE
                binding.projectName.alpha = 1.0f
                binding.projectItemRoot.setBackgroundColor(Color.parseColor("#1b1b1f"))
            } else {
                binding.projectName.textColor = Color.parseColor("#dcdfe4")
                binding.projectName.alpha = 0.8f
                binding.projectItemRoot.background = ContextCompat.getDrawable(context, android.R.color.transparent)
            }

            binding.root.setOnClickListener {
                onWorkspaceSelected(workspace)
            }

            binding.root.setOnLongClickListener {
                onWorkspaceLongPressed(workspace, it)
                true
            }
        }

        // Helper property to set text color easily
        private var android.widget.TextView.textColor: Int
            get() = currentTextColor
            set(v) = setTextColor(v)
    }

    companion object {
        private val ICONS = intArrayOf(
            R.drawable.ic_folder,
            R.drawable.ic_globe,
            R.drawable.ic_code,
            R.drawable.ic_brain
        )
        private val COLORS = intArrayOf(
            Color.parseColor("#61afef"),
            Color.parseColor("#56b6c2"),
            Color.parseColor("#98c379"),
            Color.parseColor("#c678dd")
        )
    }

    class WorkspaceDiffCallback : DiffUtil.ItemCallback<UiWorkspace>() {
        override fun areItemsTheSame(oldItem: UiWorkspace, newItem: UiWorkspace): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: UiWorkspace, newItem: UiWorkspace): Boolean {
            return oldItem == newItem
        }
    }
}
