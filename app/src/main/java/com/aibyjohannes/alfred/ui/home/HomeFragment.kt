package com.aibyjohannes.alfred.ui.home

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.databinding.FragmentHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var chatAdapter: ChatAdapter
    private var typingJob: Job? = null
    private var lastMessageCount = 0
    private var isRecording = false
    private var audioRecorder: AudioRecorder? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            toggleRecording()
        } else {
            Toast.makeText(
                context,
                getString(R.string.transcription_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(requireActivity())[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        setupRecyclerView()
        setupInputHandling()
        setupObservers()
        setupBackgroundAnimation()

        if (homeViewModel.messages.value.orEmpty().isEmpty()) {
            animateGreetingText()
        } else {
            binding.greetingMessage.visibility = View.GONE
        }

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
            val message = binding.messageInput.text?.toString() ?: ""
            if (message.isNotBlank()) {
                sendMessage()
            }
        }

        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val message = binding.messageInput.text?.toString() ?: ""
                if (message.isNotBlank()) {
                    sendMessage()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                binding.btnMic.isVisible = !hasText
                if (hasText) {
                    binding.sendButton.setImageResource(R.drawable.ic_arrow_up)
                } else {
                    binding.sendButton.setImageResource(R.drawable.ic_waveform)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnMic.setOnClickListener { toggleRecording() }
        binding.btnAdd.setOnClickListener { }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.home_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_chat) {
            homeViewModel.clearChat()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // Re-check API key status when returning from settings
        homeViewModel.checkApiKey()
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            audioRecorder?.cancelRecording()
            isRecording = false
            binding.btnMic.setColorFilter(null)
            binding.btnMic.setImageResource(R.drawable.ic_mic)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleRecording() {
        val context = context ?: return
        if (isRecording) {
            isRecording = false
            binding.btnMic.setColorFilter(null)
            binding.btnMic.setImageResource(R.drawable.ic_mic)
            val audioFile = audioRecorder?.stopRecording()
            if (audioFile != null && audioFile.exists()) {
                transcribeAndAppend(audioFile)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isRecording = true
                binding.btnMic.setImageResource(R.drawable.ic_stop)
                binding.btnMic.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
                if (audioRecorder == null) {
                    audioRecorder = AudioRecorder(context)
                }
                audioRecorder?.startRecording()
                Toast.makeText(context, getString(R.string.recording_toast), Toast.LENGTH_SHORT).show()
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun transcribeAndAppend(audioFile: java.io.File) {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnMic.isEnabled = false
            binding.messageInput.isEnabled = false
            Toast.makeText(context, getString(R.string.transcribing_toast), Toast.LENGTH_SHORT).show()

            val result = homeViewModel.transcribeAudio(audioFile)

            binding.btnMic.isEnabled = true
            binding.messageInput.isEnabled = true

            result.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        val currentText = binding.messageInput.text?.toString() ?: ""
                        val newText = if (currentText.isBlank()) text else "$currentText $text"
                        binding.messageInput.setText(newText)
                        binding.messageInput.setSelection(newText.length)
                        binding.messageInput.requestFocus()
                    }
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    Toast.makeText(context, getString(R.string.transcription_failed, errorMsg), Toast.LENGTH_LONG).show()
                }
            )
            audioFile.delete()
        }
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager
            ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val thresholdIndex = max(chatAdapter.itemCount - 2, 0)
        return lastVisible >= thresholdIndex
    }
}
