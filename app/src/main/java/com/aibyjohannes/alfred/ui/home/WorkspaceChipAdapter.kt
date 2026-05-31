package com.aibyjohannes.alfred.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aibyjohannes.alfred.R
import com.google.android.material.chip.Chip

class WorkspaceChipAdapter(
    private val onWorkspaceSelected: (UiWorkspace) -> Unit,
    private val onAddWorkspace: () -> Unit,
    private val onWorkspaceLongPressed: (UiWorkspace, View) -> Unit
) : RecyclerView.Adapter<WorkspaceChipAdapter.ViewHolder>() {

    private var workspacesList = emptyList<UiWorkspace>()
    private var activeWorkspaceId: Long? = null

    fun submitData(workspaces: List<UiWorkspace>, activeId: Long?) {
        workspacesList = workspaces
        activeWorkspaceId = activeId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = workspacesList.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workspace_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.chip.context
        if (position < workspacesList.size) {
            val workspace = workspacesList[position]
            holder.chip.text = workspace.name
            
            // Disable default checkable so check icon doesn't show
            holder.chip.isCheckable = false
            
            val isActive = workspace.id == activeWorkspaceId
            if (isActive) {
                val bgColor = resolveThemeColorByName(context, "colorPrimaryContainer")
                val textColor = resolveThemeColorByName(context, "colorOnPrimaryContainer")
                holder.chip.chipBackgroundColor = ColorStateList.valueOf(bgColor)
                holder.chip.setTextColor(textColor)
                holder.chip.chipStrokeWidth = 0f
            } else {
                val textColor = resolveThemeColorByName(context, "colorOnSurface")
                val strokeColor = resolveThemeColorByName(context, "colorOutline")
                holder.chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                holder.chip.setTextColor(textColor)
                holder.chip.chipStrokeColor = ColorStateList.valueOf(strokeColor)
                holder.chip.chipStrokeWidth = 1f
            }

            holder.chip.setOnClickListener {
                onWorkspaceSelected(workspace)
            }

            holder.chip.setOnLongClickListener { view ->
                onWorkspaceLongPressed(workspace, view)
                true
            }
        } else {
            // "＋ Add" chip at the end
            holder.chip.text = "+ Add"
            holder.chip.isCheckable = false
            
            val textColor = resolveThemeColorByName(context, "colorPrimary")
            val strokeColor = resolveThemeColorByName(context, "colorOutline")
            holder.chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
            holder.chip.setTextColor(textColor)
            holder.chip.chipStrokeColor = ColorStateList.valueOf(strokeColor)
            holder.chip.chipStrokeWidth = 1f
            
            holder.chip.setOnClickListener {
                onAddWorkspace()
            }
            holder.chip.setOnLongClickListener(null)
        }
    }

    private fun resolveThemeColorByName(context: Context, attrName: String): Int {
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName)
        if (attrId == 0) {
            return Color.GRAY
        }
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chip: Chip = view.findViewById(R.id.workspace_chip)
    }
}
