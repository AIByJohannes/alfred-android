package com.aibyjohannes.alfred.ui.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.animation.AccelerateDecelerateInterpolator
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.local.FileConversationStore
import com.aibyjohannes.alfred.data.local.FileLocalKnowledgeSearchClient
import com.aibyjohannes.alfred.data.local.FileMemorySearchSource
import com.aibyjohannes.alfred.databinding.FragmentHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlin.math.max

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var inlineConversationAdapter: ConversationAdapter
    private lateinit var drawerConversationAdapter: ConversationAdapter
    private var typingJob: Job? = null
    private var lastMessageCount = 0
    private var showInlineConversationPane = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        setupRecyclerView()
        setupConversationSidebars()
        setupInputHandling()
        setupObservers()
        setupMenu()
        setupBackgroundAnimation()

        // Initialize ViewModel with dependencies
        val apiKeyStore = ApiKeyStore(requireContext())
        val conversationStore = FileConversationStore(requireContext().filesDir)
        val localKnowledgeSearchClient = FileLocalKnowledgeSearchClient(
            conversationStore = conversationStore,
            memorySearchSource = FileMemorySearchSource(requireContext().filesDir.resolve("memories.jsonl"))
        )
        val repository = ChatRepository(apiKeyStore, localKnowledgeSearchClient)
        homeViewModel.initialize(apiKeyStore, repository, conversationStore)

        animateGreetingText()

        return root
    }

    private var backgroundAnimator: ObjectAnimator? = null
    private var isOrbAtTop = false

    private fun setupBackgroundAnimation() {
        startIdleAnimation()
    }

    private fun startIdleAnimation() {
        backgroundAnimator?.cancel()
        
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.2f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.2f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f, 0.5f)

        backgroundAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.aiOrbBackground, scaleX, scaleY, alpha).apply {
            duration = 3000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun animateOrbToTop() {
        backgroundAnimator?.cancel()
        
        // Calculate translation to top (negative Y)
        // Move up by 35% of screen height or a fixed dp amount
        val translationY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -binding.root.height * 0.35f)
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f)

        backgroundAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.aiOrbBackground, translationY, scaleX, scaleY, alpha).apply {
            duration = 1000
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        isOrbAtTop = true
    }

    private fun resetOrbPosition() {
        backgroundAnimator?.cancel()

        val translationY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f)

        backgroundAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.aiOrbBackground, translationY, scaleX, scaleY, alpha).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        // After reset, restart idle animation
        isOrbAtTop = false
    }


    private fun animateGreetingText() {
        val greeting = getString(R.string.greeting_message)
        binding.greetingMessage.text = ""
        binding.greetingMessage.visibility = View.VISIBLE
        binding.greetingMessage.alpha = 0.8f

        typingJob?.cancel()
        typingJob = viewLifecycleOwner.lifecycleScope.launch {
            greeting.forEach { char ->
                binding.greetingMessage.append(char.toString())
                delay(30)
            }
        }
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home_menu, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.action_open_conversations)?.isVisible = !showInlineConversationPane
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_open_conversations -> {
                        binding.homeDrawerLayout.openDrawer(GravityCompat.START)
                        true
                    }
                    R.id.action_clear_chat -> {
                        showClearChatConfirmation()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showClearChatConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_chat)
            .setMessage(R.string.clear_chat_confirmation)
            .setPositiveButton(R.string.clear) { _, _ ->
                homeViewModel.clearChat()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupConversationSidebars() {
        showInlineConversationPane = resources.getBoolean(R.bool.show_inline_conversation_pane)
        binding.inlineConversationPane.isVisible = showInlineConversationPane
        binding.inlineConversationDivider.isVisible = showInlineConversationPane
        binding.drawerConversationPane.isVisible = !showInlineConversationPane
        binding.homeDrawerLayout.setDrawerLockMode(
            if (showInlineConversationPane) {
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            } else {
                DrawerLayout.LOCK_MODE_UNLOCKED
            },
            GravityCompat.START
        )

        inlineConversationAdapter = ConversationAdapter { conversation ->
            homeViewModel.selectConversation(conversation.id)
        }
        drawerConversationAdapter = ConversationAdapter { conversation ->
            homeViewModel.selectConversation(conversation.id)
            closeConversationDrawerIfNeeded()
        }

        binding.inlineConversationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = inlineConversationAdapter
        }
        binding.drawerConversationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = drawerConversationAdapter
        }

        binding.inlineNewConversationButton.setOnClickListener {
            homeViewModel.createConversationAndSwitch()
        }
        binding.drawerNewConversationButton.setOnClickListener {
            homeViewModel.createConversationAndSwitch()
            closeConversationDrawerIfNeeded()
        }
    }

    private fun setupInputHandling() {
        binding.sendButton.setOnClickListener {
            sendMessage()
        }

        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun sendMessage() {
        val message = binding.messageInput.text?.toString() ?: return
        if (message.isBlank()) return

        homeViewModel.sendMessage(message)
        binding.messageInput.text?.clear()
    }

    private fun setupObservers() {
        homeViewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            inlineConversationAdapter.submitList(conversations)
            drawerConversationAdapter.submitList(conversations)
        }

        homeViewModel.activeConversationId.observe(viewLifecycleOwner) { activeConversationId ->
            inlineConversationAdapter.setActiveConversationId(activeConversationId)
            drawerConversationAdapter.setActiveConversationId(activeConversationId)
        }

        homeViewModel.messages.observe(viewLifecycleOwner) { messages ->
            // Animate orb logic
            if (messages.isNotEmpty() && !isOrbAtTop) {
                typingJob?.cancel()
                animateOrbToTop()
                // Hide greeting message with fade out animation
                binding.greetingMessage.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction {
                        binding.greetingMessage.visibility = View.GONE
                    }
                    .start()
            } else if (messages.isEmpty() && isOrbAtTop) {
                resetOrbPosition()
                // Show greeting message again with animation
                animateGreetingText()
                // Restart idle animation after reset (delayed slightly or handled in reset)
                binding.root.postDelayed({ startIdleAnimation() }, 800)
            }

            chatAdapter.submitList(messages) {
                val hasNewItems = messages.size > lastMessageCount
                val shouldAutoScroll = hasNewItems || isNearBottom()
                if (messages.isNotEmpty() && shouldAutoScroll) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
                lastMessageCount = messages.size
            }
        }

        homeViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.isVisible = isLoading
            binding.sendButton.isEnabled = !isLoading
        }

        homeViewModel.needsApiKey.observe(viewLifecycleOwner) { needsKey ->
            binding.apiKeyWarning.isVisible = needsKey
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check API key status when returning from settings
        homeViewModel.checkApiKey()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager
            ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val thresholdIndex = max(chatAdapter.itemCount - 2, 0)
        return lastVisible >= thresholdIndex
    }

    private fun closeConversationDrawerIfNeeded() {
        if (!showInlineConversationPane && binding.homeDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.homeDrawerLayout.closeDrawer(GravityCompat.START)
        }
    }
}
