package com.aibyjohannes.alfred.ui.home

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ChatAdapter(
    private val onRetryClick: ((Long) -> Unit)? = null,
    private val loadYouTubeThumbnail: (String, ImageView) -> Unit = YouTubeThumbnailLoader::load
) : ListAdapter<UiChatMessage, ChatAdapter.MessageViewHolder>(MessageDiffCallback()) {
    private val expandedTraceIds = mutableSetOf<String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

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
        return MessageViewHolder(
            binding = binding,
            onRetryClick = onRetryClick,
            loadYouTubeThumbnail = loadYouTubeThumbnail,
            isTraceExpanded = expandedTraceIds::contains,
            setTraceExpanded = { key, expanded ->
                if (expanded) expandedTraceIds += key else expandedTraceIds -= key
            }
        )
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemChatMessageBinding,
        private val onRetryClick: ((Long) -> Unit)?,
        private val loadYouTubeThumbnail: (String, ImageView) -> Unit,
        private val isTraceExpanded: (String) -> Boolean,
        private val setTraceExpanded: (String, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val markwon: Markwon = Markwon.builder(binding.root.context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(binding.root.context))
            .usePlugin(StrikethroughPlugin.create())
            .build()

        fun bind(message: UiChatMessage) {
            val parsed = ChatWidgetParser.parse(message.content)
            if (parsed.displayContent.isBlank()) {
                binding.messageText.visibility = View.GONE
            } else {
                binding.messageText.visibility = View.VISIBLE
                if (message.renderMode == RenderMode.PLAIN) {
                    binding.messageText.text = parsed.displayContent
                } else {
                    markwon.setMarkdown(binding.messageText, parsed.displayContent)
                }
            }
            bindTraceItems(message)
            bindWidgets(message, parsed.widgets)

            val context = binding.root.context
            val params = binding.messageCard.layoutParams as ConstraintLayout.LayoutParams
            params.matchConstraintMaxWidth = context.resources.getDimensionPixelSize(
                if (message.isUser) R.dimen.user_message_max_width else R.dimen.assistant_message_max_width
            )

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
                // Hide retry button for user messages
                binding.retryButton.visibility = View.GONE
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
                // Show retry button for error messages
                binding.retryButton.visibility = View.VISIBLE
                binding.retryButton.setOnClickListener {
                    onRetryClick?.invoke(message.id)
                }
            } else {
                // Assistant message: align left, gray background
                params.horizontalBias = 0f
                binding.messageCard.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.assistant_message_bg)
                )
                binding.messageText.setTextColor(
                    ContextCompat.getColor(context, R.color.assistant_message_text)
                )
                // Hide retry button for assistant messages
                binding.retryButton.visibility = View.GONE
                // Hide sharing while streaming partial text.
                if (message.isStreaming) {
                    binding.shareButton.visibility = View.GONE
                } else {
                    binding.shareButton.visibility = View.VISIBLE
                    binding.shareButton.setOnClickListener {
                        shareMessage(context, parsed.displayContent)
                    }
                }
            }
            binding.messageCard.layoutParams = params
        }

        private fun bindWidgets(message: UiChatMessage, widgets: List<ChatWidget>) {
            binding.widgetContainer.removeAllViews()
            if (message.isUser || message.isError || widgets.isEmpty()) {
                binding.widgetContainer.visibility = View.GONE
                return
            }
            binding.widgetContainer.visibility = View.VISIBLE
            widgets.forEach { widget ->
                binding.widgetContainer.addView(
                    when (widget) {
                        is ChatWidget.Weather -> weatherWidget(widget)
                        is ChatWidget.YouTube -> youtubeWidget(widget)
                        is ChatWidget.Image -> imageWidget(widget)
                    }
                )
            }
        }

        private fun weatherWidget(widget: ChatWidget.Weather): View {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_chat_widget)
                addView(TextView(context).apply {
                    text = "☀  ${widget.location}"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.assistant_message_text))
                })
                addView(TextView(context).apply {
                    text = "${widget.temperature}  ·  ${widget.condition}"
                    textSize = 20f
                    setTextColor(ContextCompat.getColor(context, R.color.assistant_message_text))
                })
                widget.details?.let { detail ->
                    addView(TextView(context).apply {
                        text = detail
                        textSize = 12f
                        alpha = 0.8f
                        setTextColor(ContextCompat.getColor(context, R.color.assistant_message_text))
                    })
                }
            }
        }

        private fun youtubeWidget(widget: ChatWidget.YouTube): View {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(
                    R.string.youtube_video_action,
                    widget.title ?: context.getString(R.string.youtube_video_fallback)
                )
                background = ContextCompat.getDrawable(context, R.drawable.bg_chat_widget)
                addView(AspectRatioFrameLayout(context).apply {
                    val thumbnail = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = null
                        background = ColorDrawable(ContextCompat.getColor(context, R.color.assistant_message_bg))
                    }
                    addView(thumbnail, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    addView(TextView(context).apply {
                        text = "▶"
                        gravity = android.view.Gravity.CENTER
                        textSize = 30f
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                        setShadowLayer(6f, 0f, 2f, ContextCompat.getColor(context, android.R.color.black))
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    loadYouTubeThumbnail(widget.videoId, thumbnail)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(TextView(context).apply {
                    text = widget.title ?: context.getString(R.string.youtube_video_fallback)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.assistant_message_text))
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                })
                setOnClickListener {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(widget.url)))
                }
            }
        }

        private fun imageWidget(widget: ChatWidget.Image): View {
            val context = binding.root.context
            val root = context.filesDir.resolve("generated_images").canonicalFile
            val file = runCatching { File(widget.path).canonicalFile }.getOrNull()
            val isSafe = file != null && file.isFile && file.path.startsWith(root.path + File.separator)
            if (!isSafe) {
                return TextView(context).apply {
                    text = "Generated image is unavailable."
                    val padding = (12 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
            }
            return ImageView(context).apply {
                contentDescription = widget.alt ?: "Generated image"
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(file.path))
                background = ContextCompat.getDrawable(context, R.drawable.bg_chat_widget)
            }
        }

        private fun bindTraceItems(message: UiChatMessage) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            val padding12 = (12 * density).toInt()

            if (message.traceItems.isEmpty() || message.isUser || message.isError) {
                binding.traceContainer.removeAllViews()
                binding.traceContainer.visibility = View.GONE
                binding.messageText.setPadding(padding12, padding12, padding12, padding12)
                return
            }

            binding.traceContainer.visibility = View.VISIBLE
            val padding4 = (4 * density).toInt()
            binding.messageText.setPadding(padding12, padding4, padding12, padding12)
            val secondaryColor = ContextCompat.getColor(context, R.color.assistant_message_text)
            binding.traceContainer.removeAllViews()

            message.traceItems.forEach { trace ->
                val expansionKey = "${message.id}:${trace.id}"
                val presentation = TracePayloadFormatter.format(trace.content)
                var expanded = isTraceExpanded(expansionKey) || trace.isExpanded

                val title = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 12f
                    setTextColor(if (trace.isError) {
                        ContextCompat.getColor(context, R.color.error_message_text)
                    } else {
                        secondaryColor
                    })
                }
                title.text = trace.title
                binding.traceContainer.addView(title)

                val body = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    textSize = 12f
                    setTextColor(if (trace.isError) {
                        ContextCompat.getColor(context, R.color.error_message_text)
                    } else {
                        secondaryColor
                    })
                    setPadding(0, 2, 0, 4)
                    if (presentation.isStructuredJson || trace.kind == UiTraceKind.TOOL_CALL || trace.kind == UiTraceKind.TOOL_RESULT) {
                        typeface = Typeface.MONOSPACE
                    }
                }
                body.text = presentation.displayText(expanded)
                body.alpha = if (trace.isError) 1f else 0.78f
                body.visibility = if (trace.kind == UiTraceKind.REASONING && !trace.isExpanded) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                binding.traceContainer.addView(body)

                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                    visibility = if (
                        presentation.canCollapse ||
                        trace.kind == UiTraceKind.TOOL_CALL ||
                        trace.kind == UiTraceKind.TOOL_RESULT
                    ) View.VISIBLE else View.GONE
                }

                if (presentation.canCollapse) {
                    actions.addView(Button(context).apply {
                        isAllCaps = false
                        textSize = 11f
                        text = context.getString(
                            if (expanded) R.string.tool_payload_collapse else R.string.tool_payload_expand
                        )
                        setOnClickListener {
                            expanded = !expanded
                            setTraceExpanded(expansionKey, expanded)
                            body.text = presentation.displayText(expanded)
                            text = context.getString(
                                if (expanded) R.string.tool_payload_collapse else R.string.tool_payload_expand
                            )
                        }
                    })
                }

                if (trace.kind == UiTraceKind.TOOL_CALL || trace.kind == UiTraceKind.TOOL_RESULT) {
                    actions.addView(Button(context).apply {
                        isAllCaps = false
                        textSize = 11f
                        text = context.getString(R.string.tool_payload_copy)
                        setOnClickListener {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(trace.title, presentation.original))
                            Toast.makeText(context, R.string.tool_payload_copied, Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                binding.traceContainer.addView(actions)

                if (trace.kind == UiTraceKind.REASONING) {
                    title.setOnClickListener {
                        body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                } else {
                    title.setOnClickListener(null)
                }
            }
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
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: UiChatMessage, newItem: UiChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}

private class AspectRatioFrameLayout(context: android.content.Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 9f / 16f).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

private object YouTubeThumbnailLoader {
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, android.graphics.Bitmap>(8) {}

    fun load(videoId: String, target: ImageView) {
        target.tag = videoId
        cache.get(videoId)?.let {
            target.setImageBitmap(it)
            return
        }
        executor.execute {
            val bitmap = runCatching {
                val connection = URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use(BitmapFactory::decodeStream).also { connection.disconnect() }
            }.getOrNull()
            if (bitmap != null) cache.put(videoId, bitmap)
            target.post {
                if (target.tag == videoId && bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }
}

