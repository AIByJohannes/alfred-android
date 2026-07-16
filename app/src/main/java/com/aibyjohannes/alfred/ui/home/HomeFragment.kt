package com.aibyjohannes.alfred.ui.home

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.net.Uri
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import com.aibyjohannes.alfred.databinding.FragmentHomeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var localSpeechRecognizer: AndroidLocalSpeechRecognizer? = null

    // Voice mode (wave button) state
    private var voiceAudioRecorder: AudioRecorder? = null
    private var voiceOrAnimator: VoiceOrAnimator? = null
    private var mediaPlayer: MediaPlayer? = null
    /** The final AI text to synthesize — stored when streaming completes during voice mode. */
    private var pendingVoiceText: String? = null
    private var isInVoiceMode = false
    private var isStreamingResponse = false
    private var isConversationLoading = false
    private val conversationLoadingAnimators = mutableListOf<Animator>()

    private fun showPermissionSnackbar() {
        Snackbar.make(
            binding.root,
            R.string.transcription_permission_required,
            Snackbar.LENGTH_LONG
        ).setAction(R.string.menu_settings) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }.show()
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            toggleRecording()
        } else {
            showPermissionSnackbar()
        }
    }

    private val voiceModePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            showPermissionSnackbar()
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
        chatAdapter = ChatAdapter(
            onRetryClick = { failedMessageId ->
                homeViewModel.retryMessage(failedMessageId)
            }
        )
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupInputHandling() {
        binding.sendButton.setOnClickListener {
            if (homeViewModel.isLoading.value == true) {
                homeViewModel.stopGeneration()
                return@setOnClickListener
            }
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
        binding.btnAdd.setOnClickListener { showImageGenerationDialog() }
        binding.btnAllowMoreLoops.setOnClickListener {
            homeViewModel.allowMoreLoops(5)
        }
    }

    private fun sendMessage(skipCostConfirmation: Boolean = false) {
        val message = binding.messageInput.text?.toString() ?: return
        if (message.isBlank()) return

        val store = ApiKeyStore(requireContext())
        if (!skipCostConfirmation && store.isCostConfirmationEnabled() &&
            store.loadModel() != LocalGemmaModelStore.LOCAL_MODEL_ID
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cost_confirmation_title)
                .setMessage(R.string.cost_confirmation_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.send) { _, _ -> sendMessage(skipCostConfirmation = true) }
                .show()
            return
        }

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

        if (shouldUseLocalVoice()) {
            val recognizer = localSpeechRecognizer ?: AndroidLocalSpeechRecognizer(ctx).also {
                localSpeechRecognizer = it
            }
            recognizer.start(
                onResult = { text ->
                    pendingVoiceText = null
                    homeViewModel.setVoiceModeState(VoiceModeState.THINKING)
                    applyVoiceModeUi(VoiceModeState.THINKING)
                    homeViewModel.sendMessage(text)
                },
                onError = { detail ->
                    endVoiceMode()
                    Snackbar.make(binding.root, detail, Snackbar.LENGTH_LONG).show()
                }
            )
        } else {
            if (voiceAudioRecorder == null) {
                voiceAudioRecorder = AudioRecorder(ctx)
            }
            voiceAudioRecorder?.startRecording()
        }

        homeViewModel.setVoiceModeState(VoiceModeState.LISTENING)
        applyVoiceModeUi(VoiceModeState.LISTENING)
    }

    private fun stopListeningAndProcess() {
        if (localSpeechRecognizer?.isListening == true) {
            localSpeechRecognizer?.stop()
            homeViewModel.setVoiceModeState(VoiceModeState.THINKING)
            applyVoiceModeUi(VoiceModeState.THINKING)
            return
        }
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
                        endVoiceMode()
                        Snackbar.make(
                            binding.root,
                            getString(R.string.transcription_failed, "No speech detected"),
                            Snackbar.LENGTH_LONG
                        ).setAction(R.string.retry) {
                            onWaveButtonTapped()
                        }.show()
                    }
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    endVoiceMode()
                    Snackbar.make(
                        binding.root,
                        getString(R.string.transcription_failed, errorMsg),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.retry) {
                        onWaveButtonTapped()
                    }.show()
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
                Snackbar.make(binding.root, getString(R.string.tts_error, e.message ?: "Playback error"), Snackbar.LENGTH_LONG).show()
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

            val lastMsg = messages.lastOrNull()
            val isLoopLimitReached = lastMsg != null && !lastMsg.isUser &&
                lastMsg.content.contains("Agent loop limit reached. I executed ")
            binding.loopLimitContinuationLayout.visibility = if (isLoopLimitReached) View.VISIBLE else View.GONE

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
            isStreamingResponse = isLoading
            updateLoadingUi()
        }

        homeViewModel.isConversationLoading.observe(viewLifecycleOwner) { loading ->
            isConversationLoading = loading
            updateLoadingUi()
        }

        homeViewModel.needsApiKey.observe(viewLifecycleOwner) { needsKey ->
            binding.apiKeyWarning.isVisible = needsKey
        }

        homeViewModel.storageError.observe(viewLifecycleOwner) { detail ->
            if (!detail.isNullOrBlank()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.chat_history_temporarily_unavailable, detail),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.retry) {
                    homeViewModel.retryChatHistoryLoad()
                }.show()
                homeViewModel.consumeStorageError()
            }
        }

        homeViewModel.ttsAudioFile.observe(viewLifecycleOwner) { file ->
            if (file != null && isInVoiceMode) {
                playTtsFile(file)
            }
        }

        homeViewModel.sharedText.observe(viewLifecycleOwner) { text ->
            if (!text.isNullOrBlank()) {
                binding.messageInput.setText(text)
                binding.messageInput.requestFocus()
                binding.messageInput.setSelection(text.length)
                homeViewModel.consumeSharedText()
            }
        }
    }

    private fun showImageGenerationDialog() {
        val context = context ?: return
        val input = EditText(context).apply {
            hint = "Describe the image you want to create"
            minLines = 3
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Generate image")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Generate") { _, _ ->
                val prompt = input.text?.toString().orEmpty()
                if (prompt.isBlank()) return@setPositiveButton
                Toast.makeText(context, "Starting image generation…", Toast.LENGTH_SHORT).show()
                homeViewModel.sendImageGenerationRequest(prompt)
            }
            .show()
    }

    private fun updateLoadingUi() {
        val isBusy = isStreamingResponse || isConversationLoading
        // Streaming text and the disabled input already communicate progress.
        // Avoid a second, blocking spinner while a conversation is restored.
        stopConversationLoadingAnimation()
        binding.conversationLoadingOverlay.isVisible = false
        binding.inputLayout.alpha = if (isBusy) 0.5f else 1f
        binding.messageInput.isEnabled = !isBusy
        binding.btnAdd.isEnabled = !isBusy
        binding.btnMic.isEnabled = !isBusy

        if (!isInVoiceMode) {
            binding.loadingIndicator.isVisible = false
            binding.sendButton.isEnabled = !isConversationLoading
            if (isStreamingResponse) {
                binding.sendButton.setImageResource(R.drawable.ic_stop)
            } else {
                val hasText = !binding.messageInput.text.isNullOrBlank()
                binding.sendButton.setImageResource(
                    if (hasText) R.drawable.ic_arrow_up else R.drawable.ic_waveform
                )
            }
        } else {
            // In voice mode the streaming indicator is not shown; TTS handles the transition.
            binding.loadingIndicator.isVisible = false
            binding.sendButton.isEnabled = !isConversationLoading
        }
    }

    private fun startConversationLoadingAnimation() {
        if (conversationLoadingAnimators.isNotEmpty()) return

        val orbPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.conversationLoadingOrb,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.08f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.72f, 1f)
        ).apply {
            duration = 1_800L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val ringAnimations = listOf(
            binding.conversationLoadingRingInner,
            binding.conversationLoadingRingMiddle,
            binding.conversationLoadingRingOuter
        ).mapIndexed { index, ring ->
            ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.55f, 1.16f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.55f, 1.16f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.65f, 0f)
            ).apply {
                duration = 1_800L
                startDelay = index * 450L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        conversationLoadingAnimators += orbPulse
        conversationLoadingAnimators += ringAnimations
        conversationLoadingAnimators.forEach(Animator::start)
    }

    private fun stopConversationLoadingAnimation() {
        conversationLoadingAnimators.forEach(Animator::cancel)
        conversationLoadingAnimators.clear()

        binding.conversationLoadingOrb.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
        listOf(
            binding.conversationLoadingRingInner,
            binding.conversationLoadingRingMiddle,
            binding.conversationLoadingRingOuter
        ).forEach { ring ->
            ring.scaleX = 0.55f
            ring.scaleY = 0.55f
            ring.alpha = 0f
        }
    }

    private fun synthesizeAndEmitTts(text: String) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val store = ApiKeyStore(ctx)
            val useLocalFirst = shouldUseLocalVoice()
            val localOutput = java.io.File(ctx.cacheDir, "tts_local_${System.currentTimeMillis()}.wav")
            var result = if (useLocalFirst) {
                AndroidLocalTtsSynthesizer(ctx).synthesize(text, localOutput)
            } else {
                homeViewModel.synthesizeSpeech(text, ctx.cacheDir)
            }
            if (result.isFailure && !useLocalFirst && store.isLocalVoiceFallbackEnabled()) {
                result = AndroidLocalTtsSynthesizer(ctx).synthesize(text, localOutput)
            }
            result.fold(
                onSuccess = { file ->
                    homeViewModel.emitTtsFile(file)
                },
                onFailure = { error ->
                    endVoiceMode()
                    Snackbar.make(
                        binding.root,
                        getString(R.string.tts_error, error.message ?: "Unknown error"),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.retry) {
                        synthesizeAndEmitTts(text)
                    }.show()
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
            localSpeechRecognizer?.cancel()
            isRecording = false
            binding.btnMic.setColorFilter(null)
            binding.btnMic.setImageResource(R.drawable.ic_mic)
        }
        // Cancel voice mode if active
        if (isInVoiceMode) {
            voiceAudioRecorder?.cancelRecording()
            localSpeechRecognizer?.cancel()
            stopMediaPlayer()
            endVoiceMode()
        }
    }

    override fun onDestroyView() {
        stopConversationLoadingAnimation()
        stopMediaPlayer()
        voiceOrAnimator?.cancel()
        localSpeechRecognizer?.close()
        localSpeechRecognizer = null
        super.onDestroyView()
        _binding = null
    }

    private fun toggleRecording() {
        val context = context ?: return
        if (isRecording) {
            isRecording = false
            binding.btnMic.setColorFilter(null)
            binding.btnMic.setImageResource(R.drawable.ic_mic)
            if (localSpeechRecognizer?.isListening == true) {
                localSpeechRecognizer?.stop()
                return
            }
            val audioFile = audioRecorder?.stopRecording()
            if (audioFile != null && audioFile.exists()) {
                transcribeAndAppend(audioFile)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isRecording = true
                binding.btnMic.setImageResource(R.drawable.ic_stop)
                binding.btnMic.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
                if (shouldUseLocalVoice()) {
                    val recognizer = localSpeechRecognizer ?: AndroidLocalSpeechRecognizer(context).also {
                        localSpeechRecognizer = it
                    }
                    recognizer.start(
                        onResult = { text ->
                            isRecording = false
                            binding.btnMic.setColorFilter(null)
                            binding.btnMic.setImageResource(R.drawable.ic_mic)
                            appendDictation(text)
                        },
                        onError = { detail ->
                            isRecording = false
                            binding.btnMic.setColorFilter(null)
                            binding.btnMic.setImageResource(R.drawable.ic_mic)
                            Snackbar.make(binding.root, detail, Snackbar.LENGTH_LONG).show()
                        }
                    )
                } else {
                    if (audioRecorder == null) {
                        audioRecorder = AudioRecorder(context)
                    }
                    audioRecorder?.startRecording()
                }
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
                    appendDictation(text)
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    Snackbar.make(
                        binding.root,
                        getString(R.string.transcription_failed, errorMsg),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.retry) {
                        toggleRecording()
                    }.show()
                }
            )
            audioFile.delete()
        }
    }

    private fun appendDictation(text: String) {
        if (text.isBlank() || _binding == null) return
        val currentText = binding.messageInput.text?.toString() ?: ""
        val newText = if (currentText.isBlank()) text else "$currentText $text"
        binding.messageInput.setText(newText)
        binding.messageInput.setSelection(newText.length)
        binding.messageInput.requestFocus()
    }

    private fun shouldUseLocalVoice(): Boolean {
        val ctx = context ?: return false
        val store = ApiKeyStore(ctx)
        return store.isPreferLocalVoiceEnabled() ||
            (store.isLocalVoiceFallbackEnabled() && !hasInternetConnection(ctx))
    }

    private fun hasInternetConnection(context: android.content.Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager
            ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val thresholdIndex = max(chatAdapter.itemCount - 2, 0)
        return lastVisible >= thresholdIndex
    }
}
