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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        setupRecyclerView()
        setupInputHandling()
        setupObservers()
        setupMenu()
        setupBackgroundAnimation()

        // Initialize ViewModel with dependencies
        val apiKeyStore = ApiKeyStore(requireContext())
        val repository = ChatRepository(apiKeyStore)
        homeViewModel.initialize(apiKeyStore, repository)

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

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
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
        homeViewModel.messages.observe(viewLifecycleOwner) { messages ->
            // Animate orb logic
            if (messages.isNotEmpty() && !isOrbAtTop) {
                animateOrbToTop()
            } else if (messages.isEmpty() && isOrbAtTop) {
                resetOrbPosition()
                // Restart idle animation after reset (delayed slightly or handled in reset)
                binding.root.postDelayed({ startIdleAnimation() }, 800)
            }

            chatAdapter.submitList(messages) {
                // Scroll to bottom when new message is added
                if (messages.isNotEmpty()) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
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
}