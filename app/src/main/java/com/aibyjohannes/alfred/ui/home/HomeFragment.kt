package com.aibyjohannes.alfred.ui.home

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.pm.PackageManager
import android.media.MediaPlayer
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

    // Dictation (btn_mic) state
    private var isRecording = false
    private var audioRecorder: AudioRecorder? = null

    // Voice mode (wave button) state
    private var voiceAudioRecorder: AudioRecorder? = null
    private var voiceOrAnimator: VoiceOrAnimator? = null
    private var mediaPlayer: MediaPlayer? = null
    /** The final AI text to synthesize — stored when streaming completes during voice mode. */
    private var pendingVoiceText: String? = null
    private var isInVoiceMode = false

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

    private val voiceModePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
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

        voiceOrAnimator = VoiceOrAnimator(binding.aiOrbBackground)

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
        if (isInVoiceMode) return
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
        if (isInVoiceMode) return
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
        if (isInVoiceMode) return
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
            } else {
                // Empty input: enter live voice mode
                onWaveButtonTapped()
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

    // ─── Voice Mode ───────────────────────────────────────────────────────────

    private fun onWaveButtonTapped() {
        if (isInVoiceMode) {
            // Already listening — stop and process
            stopListeningAndProcess()
        } else {
            // Start voice mode
            val ctx = context ?: return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                voiceModePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startListening() {
        val ctx = context ?: return
        isInVoiceMode = true
        pendingVoiceText = null

        // Stop any ongoing TTS playback
        stopMediaPlayer()

        if (voiceAudioRecorder == null) {
            voiceAudioRecorder = AudioRecorder(ctx)
        }
        voiceAudioRecorder?.startRecording()

        homeViewModel.setVoiceModeState(VoiceModeState.LISTENING)
        applyVoiceModeUi(VoiceModeState.LISTENING)
    }

    private fun stopListeningAndProcess() {
        val audioFile = voiceAudioRecorder?.stopRecording()
        homeViewModel.setVoiceModeState(VoiceModeState.THINKING)
        applyVoiceModeUi(VoiceModeState.THINKING)

        if (audioFile != null && audioFile.exists()) {
            transcribeAndSendInVoiceMode(audioFile)
        } else {
            endVoiceMode()
        }
    }

    private fun transcribeAndSendInVoiceMode(audioFile: java.io.File) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = homeViewModel.transcribeAudio(audioFile)
            audioFile.delete()

            result.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        // Mark that the next completed message should be spoken
                        pendingVoiceText = null
                        homeViewModel.setVoiceModeState(VoiceModeState.THINKING)
                        // sendMessage will stream to the chat; we wait for ttsAudioFile observer
                        homeViewModel.sendMessage(text)
                    } else {
                        Toast.makeText(ctx, getString(R.string.transcription_failed, "No speech detected"), Toast.LENGTH_SHORT).show()
                        endVoiceMode()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(ctx, getString(R.string.transcription_failed, error.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
                    endVoiceMode()
                }
            )
        }
    }

    /** Called from the ttsAudioFile observer when a new TTS file is ready. */
    private fun playTtsFile(file: java.io.File) {
        val ctx = context ?: return
        homeViewModel.setVoiceModeState(VoiceModeState.SPEAKING)
        applyVoiceModeUi(VoiceModeState.SPEAKING)

        stopMediaPlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    file.delete()
                    endVoiceMode()
                }
                setOnErrorListener { _, _, _ ->
                    file.delete()
                    endVoiceMode()
                    false
                }
                start()
            } catch (e: Exception) {
                Toast.makeText(ctx, getString(R.string.tts_error, e.message ?: "Playback error"), Toast.LENGTH_SHORT).show()
                file.delete()
                endVoiceMode()
            }
        }
    }

    private fun endVoiceMode() {
        isInVoiceMode = false
        pendingVoiceText = null
        stopMediaPlayer()
        homeViewModel.setVoiceModeState(VoiceModeState.IDLE)
        homeViewModel.emitTtsFile(null)
        applyVoiceModeUi(VoiceModeState.IDLE)
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun applyVoiceModeUi(state: VoiceModeState) {
        val animator = voiceOrAnimator ?: return
        when (state) {
            VoiceModeState.IDLE -> {
                animator.cancel()
                binding.voiceModeLabel.visibility = View.GONE
                binding.sendButton.setImageResource(R.drawable.ic_waveform)
                binding.btnMic.isEnabled = true
                binding.messageInput.isEnabled = true
                // Resume normal orb animation
                if (isOrbAtTop) {
                    backgroundAnimator?.cancel()
                    animateOrbToTop()  // re-apply the top position without voice override
                } else {
                    startIdleAnimation()
                }
            }
            VoiceModeState.LISTENING -> {
                backgroundAnimator?.cancel()
                animator.playListening()
                binding.voiceModeLabel.text = getString(R.string.voice_mode_listening)
                binding.voiceModeLabel.visibility = View.VISIBLE
                binding.sendButton.setImageResource(R.drawable.ic_stop)
                binding.btnMic.isEnabled = false
                binding.messageInput.isEnabled = false
            }
            VoiceModeState.THINKING -> {
                backgroundAnimator?.cancel()
                animator.playThinking()
                binding.voiceModeLabel.text = getString(R.string.voice_mode_thinking)
                binding.voiceModeLabel.visibility = View.VISIBLE
                binding.sendButton.setImageResource(R.drawable.ic_waveform)
                binding.btnMic.isEnabled = false
                binding.messageInput.isEnabled = false
            }
            VoiceModeState.SPEAKING -> {
                backgroundAnimator?.cancel()
                animator.playSpeaking()
                binding.voiceModeLabel.text = getString(R.string.voice_mode_speaking)
                binding.voiceModeLabel.visibility = View.VISIBLE
                binding.sendButton.setImageResource(R.drawable.ic_waveform)
                binding.btnMic.isEnabled = false
                binding.messageInput.isEnabled = false
            }
        }
    }

    // ─── Chat Observers ───────────────────────────────────────────────────────

    private fun setupObservers() {
        homeViewModel.messages.observe(viewLifecycleOwner) { messages ->
            // Animate orb logic (only when not in voice mode)
            if (!isInVoiceMode) {
                if (messages.isNotEmpty() && !isOrbAtTop) {
                    typingJob?.cancel()
                    animateOrbToTop()
                    binding.greetingMessage.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction {
                            binding.greetingMessage.visibility = View.GONE
                        }
                        .start()
                } else if (messages.isEmpty() && isOrbAtTop) {
                    resetOrbPosition()
                    animateGreetingText()
                    binding.root.postDelayed({ startIdleAnimation() }, 800)
                }
            }

            chatAdapter.submitList(messages) {
                val hasNewItems = messages.size > lastMessageCount
                val shouldAutoScroll = hasNewItems || isNearBottom()
                if (messages.isNotEmpty() && shouldAutoScroll) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
                lastMessageCount = messages.size
            }

            // When a streaming message completes and voice mode is active, synthesize TTS
            if (isInVoiceMode && homeViewModel.voiceModeState.value == VoiceModeState.THINKING) {
                val latestAssistant = messages.lastOrNull { !it.isUser && !it.isStreaming && !it.isError }
                if (latestAssistant != null && latestAssistant.content.isNotBlank()) {
                    val alreadyPending = pendingVoiceText == latestAssistant.content
                    if (!alreadyPending) {
                        pendingVoiceText = latestAssistant.content
                        synthesizeAndEmitTts(latestAssistant.content)
                    }
                }
            }
        }

        homeViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (!isInVoiceMode) {
                binding.loadingIndicator.isVisible = isLoading
                binding.sendButton.isEnabled = !isLoading
            } else {
                // In voice mode the loading indicator is not shown; TTS observer handles transition
                binding.loadingIndicator.isVisible = false
                binding.sendButton.isEnabled = true
            }
        }

        homeViewModel.needsApiKey.observe(viewLifecycleOwner) { needsKey ->
            binding.apiKeyWarning.isVisible = needsKey
        }

        homeViewModel.storageError.observe(viewLifecycleOwner) { detail ->
            if (!detail.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    getString(R.string.chat_history_temporarily_unavailable, detail),
                    Toast.LENGTH_LONG
                ).show()
                homeViewModel.consumeStorageError()
            }
        }

        homeViewModel.ttsAudioFile.observe(viewLifecycleOwner) { file ->
            if (file != null && isInVoiceMode) {
                playTtsFile(file)
            }
        }
    }

    private fun synthesizeAndEmitTts(text: String) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val cacheDir = ctx.cacheDir
            val result = homeViewModel.synthesizeSpeech(text, cacheDir)
            result.fold(
                onSuccess = { file ->
                    homeViewModel.emitTtsFile(file)
                },
                onFailure = { error ->
                    Toast.makeText(ctx, getString(R.string.tts_error, error.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
                    endVoiceMode()
                }
            )
        }
    }

    // ─── Dictation (btn_mic) — unchanged behaviour ────────────────────────────

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
        homeViewModel.checkApiKey()
    }

    override fun onPause() {
        super.onPause()
        // Cancel dictation mic if active
        if (isRecording) {
            audioRecorder?.cancelRecording()
            isRecording = false
            binding.btnMic.setColorFilter(null)
            binding.btnMic.setImageResource(R.drawable.ic_mic)
        }
        // Cancel voice mode if active
        if (isInVoiceMode) {
            voiceAudioRecorder?.cancelRecording()
            stopMediaPlayer()
            endVoiceMode()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMediaPlayer()
        voiceOrAnimator?.cancel()
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
