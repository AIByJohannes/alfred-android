package com.aibyjohannes.alfred.ui.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.databinding.ItemChatMessageBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

class ChatAdapter : ListAdapter<UiChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // Initialize Markwon instance if not already done, or pass it.
        // For simplicity and performance, creating one here (or better, in the Adapter constructor or DI)
        // Since we don't have DI setup visible here easily, let's create it in the ViewHolder or pass context.
        // Actually, Markwon needs context.
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val markwon: Markwon = Markwon.builder(binding.root.context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(binding.root.context))
            .usePlugin(StrikethroughPlugin.create())
            .build()

        fun bind(message: UiChatMessage) {
            // Apply Markdown rendering
            markwon.setMarkdown(binding.messageText, message.content)

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
                // Hide share button for user messages
                binding.shareButton.visibility = View.GONE
            } else if (message.isError) {
                // Error message: align left, red background
                params.horizontalBias = 0f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.error_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.error_message_text)
                )
                // Hide share button for error messages
                binding.shareButton.visibility = View.GONE
            } else {
                // Assistant message: align left, gray background
                params.horizontalBias = 0f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.assistant_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.assistant_message_text)
                )
                // Show share button for assistant messages
                binding.shareButton.visibility = View.VISIBLE
                binding.shareButton.setOnClickListener {
                    shareMessage(context, message.content)
                }
            }
            binding.messageCard.layoutParams = params
        }

        private fun shareMessage(context: android.content.Context, content: String) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, "Shared from A.L.F.R.E.D.")
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share via"))
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

