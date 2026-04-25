package com.aibyjohannes.alfred.ui.home

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.databinding.ItemConversationBinding

class ConversationAdapter(
    private val onConversationSelected: (UiConversation) -> Unit
) : ListAdapter<UiConversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    private var activeConversationId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding, onConversationSelected)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position), activeConversationId)
    }

    fun setActiveConversationId(conversationId: Long?) {
        if (activeConversationId == conversationId) return
        activeConversationId = conversationId
        notifyDataSetChanged()
    }

    class ConversationViewHolder(
        private val binding: ItemConversationBinding,
        private val onConversationSelected: (UiConversation) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: UiConversation, activeConversationId: Long?) {
            val context = binding.root.context
            val isActive = conversation.id == activeConversationId
            val activeColor = ContextCompat.getColor(context, R.color.palette_blue)
            val inactiveSurfaceColor = ContextCompat.getColor(context, R.color.window_background)

            binding.conversationTitle.text = conversation.title
            binding.conversationUpdatedAt.text = DateUtils.getRelativeTimeSpanString(
                conversation.updatedAtEpochMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            binding.conversationCard.isChecked = isActive
            binding.conversationCard.strokeColor = if (isActive) {
                activeColor
            } else {
                ContextCompat.getColor(context, android.R.color.transparent)
            }
            binding.conversationCard.setCardBackgroundColor(
                if (isActive) ContextCompat.getColor(context, R.color.assistant_message_bg) else inactiveSurfaceColor
            )
            binding.conversationCard.contentDescription = if (isActive) {
                context.getString(R.string.active_conversation)
            } else {
                null
            }
            binding.root.setOnClickListener {
                onConversationSelected(conversation)
            }
        }
    }

    class ConversationDiffCallback : DiffUtil.ItemCallback<UiConversation>() {
        override fun areItemsTheSame(oldItem: UiConversation, newItem: UiConversation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: UiConversation, newItem: UiConversation): Boolean {
            return oldItem == newItem
        }
    }
}
