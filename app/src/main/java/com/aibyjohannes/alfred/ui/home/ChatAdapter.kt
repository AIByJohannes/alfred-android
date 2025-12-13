package com.aibyjohannes.alfred.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.databinding.ItemChatMessageBinding

class ChatAdapter : ListAdapter<UiChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: UiChatMessage) {
            binding.messageText.text = message.content

            val context = binding.root.context
            val params = binding.messageCard.layoutParams as ConstraintLayout.LayoutParams

            if (message.isUser) {
                // User message: align right, blue background
                params.horizontalBias = 1f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.user_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.user_message_text)
                )
            } else if (message.isError) {
                // Error message: align left, red background
                params.horizontalBias = 0f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.error_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.error_message_text)
                )
            } else {
                // Assistant message: align left, gray background
                params.horizontalBias = 0f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.assistant_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.assistant_message_text)
                )
            }
            binding.messageCard.layoutParams = params
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<UiChatMessage>() {
        override fun areItemsTheSame(oldItem: UiChatMessage, newItem: UiChatMessage): Boolean {
            return oldItem === newItem
        }

        override fun areContentsTheSame(oldItem: UiChatMessage, newItem: UiChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}

