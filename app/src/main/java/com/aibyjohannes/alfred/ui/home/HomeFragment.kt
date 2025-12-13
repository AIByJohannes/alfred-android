package com.aibyjohannes.alfred.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
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

        // Initialize ViewModel with dependencies
        val apiKeyStore = ApiKeyStore(requireContext())
        val repository = ChatRepository(apiKeyStore)
        homeViewModel.initialize(apiKeyStore, repository)

        return root
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