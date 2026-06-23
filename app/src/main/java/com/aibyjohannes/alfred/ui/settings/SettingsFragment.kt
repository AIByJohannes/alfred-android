package com.aibyjohannes.alfred.ui.settings

import android.Manifest
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ProfilePreferencesStore
import com.aibyjohannes.alfred.data.local.ObsidianVaultStore
import com.aibyjohannes.alfred.data.local.NoteSearchIndexDatabase
import com.aibyjohannes.alfred.data.local.VaultSearchIndexer
import com.aibyjohannes.alfred.databinding.FragmentSettingsBinding
import com.aibyjohannes.alfred.notifications.NotificationPreferencesStore
import com.aibyjohannes.alfred.notifications.NotificationScheduler
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.snackbar.Snackbar
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.aibyjohannes.alfred.data.ticktick.TickTickOAuthServer
import com.aibyjohannes.alfred.core.ticktick.TickTickClient
import com.aibyjohannes.alfred.core.ticktick.TickTickClient.Companion.OAUTH_REDIRECT_URI
import com.aibyjohannes.alfred.core.ticktick.TickTickClient.Companion.OAUTH_SCOPE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var profilePreferencesStore: ProfilePreferencesStore
    private lateinit var notificationPreferencesStore: NotificationPreferencesStore
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var obsidianVaultStore: ObsidianVaultStore
    private lateinit var obsidianFolderLauncher: ActivityResultLauncher<Uri?>
    private var oauthServer: TickTickOAuthServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                notificationPreferencesStore.notificationsEnabled = true
                NotificationScheduler.rescheduleAll(requireContext())
                updateNotificationControls()
            } else {
                notificationPreferencesStore.notificationsEnabled = false
                updateNotificationControls()
                _binding?.root?.let { root ->
                    Snackbar.make(root, R.string.notifications_permission_denied, Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        obsidianFolderLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                try {
                    obsidianVaultStore.persistFolder(uri)
                    updateObsidianStatus()
                    
                    // Trigger asynchronous sync of the search index
                    val appContext = requireContext().applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val db = NoteSearchIndexDatabase.get(appContext)
                            val indexer = VaultSearchIndexer(appContext, db)
                            indexer.syncIndex(uri)
                        } catch (e: Exception) {
                            android.util.Log.e("AlfredSearch", "Failed to sync index on vault connect", e)
                        }
                    }
                    
                    Snackbar.make(binding.root, R.string.obsidian_connected_success, Snackbar.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "Failed to connect vault: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        apiKeyStore = ApiKeyStore(requireContext())
        profilePreferencesStore = ProfilePreferencesStore(requireContext())
        notificationPreferencesStore = NotificationPreferencesStore(requireContext())
        obsidianVaultStore = ObsidianVaultStore(requireContext())

        setupButtons()
        setupProfileControls()
        setupModelDropdown()
        setupSttModelDropdown()
        setupTtsModelDropdown()
        setupTtsVoiceDropdown()
        setupNotificationControls()
        updateStatus()
        updateNotificationControls()
        setupTickTickControls()
        updateTickTickStatus()
        setupObsidianControls()
        updateObsidianStatus()

        return root
    }

    private fun setupModelDropdown() {
        val models = resources.getStringArray(R.array.model_values)
        val labels = resources.getStringArray(R.array.model_labels)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.modelSelectDropdown.setAdapter(adapter)

        val currentModel = apiKeyStore.loadModel()
        val index = models.indexOf(currentModel)
        if (index >= 0) {
            binding.modelSelectDropdown.setText(labels[index], false)
        }

        binding.modelSelectDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position]
            apiKeyStore.saveModel(selectedModel)
            Snackbar.make(binding.root, "Model updated to ${labels[position]}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupSttModelDropdown() {
        val models = resources.getStringArray(R.array.stt_model_values)
        val labels = resources.getStringArray(R.array.stt_model_labels)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.sttModelSelectDropdown.setAdapter(adapter)

        val currentModel = apiKeyStore.loadSttModel()
        val index = models.indexOf(currentModel)
        if (index >= 0) {
            binding.sttModelSelectDropdown.setText(labels[index], false)
        }

        binding.sttModelSelectDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position]
            apiKeyStore.saveSttModel(selectedModel)
            Snackbar.make(binding.root, "Speech-to-Text model updated to ${labels[position]}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupTtsModelDropdown() {
        val models = resources.getStringArray(R.array.tts_model_values)
        val labels = resources.getStringArray(R.array.tts_model_labels)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.ttsModelSelectDropdown.setAdapter(adapter)

        val currentModel = apiKeyStore.loadTtsModel()
        val index = models.indexOf(currentModel)
        if (index >= 0) {
            binding.ttsModelSelectDropdown.setText(labels[index], false)
        }

        binding.ttsModelSelectDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position]
            apiKeyStore.saveTtsModel(selectedModel)
            Snackbar.make(binding.root, "Text-to-Speech model updated to ${labels[position]}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupTtsVoiceDropdown() {
        val voices = resources.getStringArray(R.array.tts_voice_values)
        val labels = resources.getStringArray(R.array.tts_voice_labels)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.ttsVoiceSelectDropdown.setAdapter(adapter)

        val currentVoice = apiKeyStore.loadTtsVoice()
        val index = voices.indexOf(currentVoice)
        if (index >= 0) {
            binding.ttsVoiceSelectDropdown.setText(labels[index], false)
        }

        binding.ttsVoiceSelectDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedVoice = voices[position]
            apiKeyStore.saveTtsVoice(selectedVoice)
            Snackbar.make(binding.root, "Voice updated to ${labels[position]}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        binding.saveButton.setOnClickListener {
            val apiKey = binding.apiKeyInput.text?.toString()?.trim()
            if (apiKey.isNullOrBlank()) {
                Snackbar.make(binding.root, R.string.api_key_empty_error, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apiKeyStore.saveOpenRouterKey(apiKey)
            binding.apiKeyInput.text?.clear()
            Snackbar.make(binding.root, R.string.api_key_saved, Snackbar.LENGTH_SHORT).show()
            updateStatus()
        }

        binding.clearButton.setOnClickListener {
            apiKeyStore.clearApiKey()
            Snackbar.make(binding.root, R.string.api_key_cleared, Snackbar.LENGTH_SHORT).show()
            updateStatus()
        }
    }

    private fun setupProfileControls() {
        binding.profileDisplayNameInput.setText(profilePreferencesStore.displayName)

        binding.saveProfileButton.setOnClickListener {
            val displayName = binding.profileDisplayNameInput.text?.toString()?.trim().orEmpty()

            profilePreferencesStore.displayName = displayName.ifBlank {
                ProfilePreferencesStore.DEFAULT_DISPLAY_NAME
            }

            binding.profileDisplayNameInput.setText(profilePreferencesStore.displayName)
            Snackbar.make(binding.root, R.string.profile_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupNotificationControls() {
        binding.notificationsEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == notificationPreferencesStore.notificationsEnabled) {
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                enableNotifications()
            } else {
                notificationPreferencesStore.notificationsEnabled = false
                NotificationScheduler.cancelAll(requireContext())
                updateNotificationControls()
            }
        }

        binding.notificationTimeButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(notificationPreferencesStore.dailyReminderHour)
                .setMinute(notificationPreferencesStore.dailyReminderMinute)
                .setTitleText(R.string.notifications_time_picker_title)
                .build()

            picker.addOnPositiveButtonClickListener {
                notificationPreferencesStore.dailyReminderHour = picker.hour
                notificationPreferencesStore.dailyReminderMinute = picker.minute
                NotificationScheduler.rescheduleAll(requireContext())
                updateNotificationControls()
            }

            picker.show(parentFragmentManager, "notification_time_picker")
        }
    }

    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPreferencesStore.notificationsEnabled = true
            NotificationScheduler.rescheduleAll(requireContext())
            updateNotificationControls()
        }
    }

    private fun updateNotificationControls() {
        if (!::notificationPreferencesStore.isInitialized || _binding == null) return

        binding.notificationsEnabledSwitch.setOnCheckedChangeListener(null)
        binding.notificationsEnabledSwitch.isChecked = notificationPreferencesStore.notificationsEnabled
        binding.notificationsEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == notificationPreferencesStore.notificationsEnabled) {
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                enableNotifications()
            } else {
                notificationPreferencesStore.notificationsEnabled = false
                NotificationScheduler.cancelAll(requireContext())
                updateNotificationControls()
            }
        }
        binding.notificationTimeButton.text = notificationPreferencesStore.reminderTimeLabel()
        binding.notificationTimeButton.isEnabled = notificationPreferencesStore.notificationsEnabled
    }

    private fun updateStatus() {
        val hasKey = apiKeyStore.hasApiKey()
        if (hasKey) {
            binding.apiKeyStatusChip.setText(R.string.api_key_configured)
            binding.apiKeyStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_configured_bg)
            )
            binding.apiKeyStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_configured_text)
            )
        } else {
            binding.apiKeyStatusChip.setText(R.string.api_key_not_configured)
            binding.apiKeyStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_bg)
            )
            binding.apiKeyStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_text)
            )
        }
    }

    private fun setupTickTickControls() {
        binding.ticktickClientIdInput.setText(apiKeyStore.loadTickTickClientId())
        binding.ticktickClientSecretInput.setText(apiKeyStore.loadTickTickClientSecret())

        binding.ticktickConnectButton.setOnClickListener {
            val clientId = binding.ticktickClientIdInput.text?.toString()?.trim()
            val clientSecret = binding.ticktickClientSecretInput.text?.toString()?.trim()

            if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                Snackbar.make(binding.root, R.string.ticktick_save_error, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apiKeyStore.saveTickTickClientId(clientId)
            apiKeyStore.saveTickTickClientSecret(clientSecret)

            oauthServer?.stop()
            val oauthState = UUID.randomUUID().toString()
            oauthServer = TickTickOAuthServer(
                port = 54321,
                expectedState = oauthState,
                onCodeReceived = { code ->
                    lifecycleScope.launch {
                        exchangeCodeForTokens(code)
                    }
                },
                onErrorReceived = { error ->
                    lifecycleScope.launch {
                        updateTickTickStatus()
                        Snackbar.make(binding.root, getString(R.string.ticktick_auth_failed, error), Snackbar.LENGTH_LONG).show()
                    }
                }
            )
            oauthServer?.start()

            val authUrl = Uri.Builder()
                .scheme("https")
                .authority("ticktick.com")
                .path("/oauth/authorize")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", OAUTH_SCOPE)
                .appendQueryParameter("state", oauthState)
                .build()
                .toString()

            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                startActivity(intent)
            } catch (e: Exception) {
                oauthServer?.stop()
                Snackbar.make(binding.root, "Failed to open browser: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }

        binding.ticktickDisconnectButton.setOnClickListener {
            apiKeyStore.clearTickTickCredentials()
            binding.ticktickClientIdInput.text?.clear()
            binding.ticktickClientSecretInput.text?.clear()
            updateTickTickStatus()
            Snackbar.make(binding.root, R.string.ticktick_cleared, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateTickTickStatus() {
        if (!::apiKeyStore.isInitialized || _binding == null) return

        val isConnected = apiKeyStore.hasTickTickAuth()
        if (isConnected) {
            binding.ticktickStatusChip.setText(R.string.ticktick_status_connected)
            binding.ticktickStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_configured_bg)
            )
            binding.ticktickStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_configured_text)
            )
            binding.ticktickClientIdInput.isEnabled = false
            binding.ticktickClientSecretInput.isEnabled = false
            binding.ticktickConnectButton.isEnabled = false
            binding.ticktickDisconnectButton.isEnabled = true
        } else {
            binding.ticktickStatusChip.setText(R.string.ticktick_status_not_connected)
            binding.ticktickStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_bg)
            )
            binding.ticktickStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_text)
            )
            binding.ticktickClientIdInput.isEnabled = true
            binding.ticktickClientSecretInput.isEnabled = true
            binding.ticktickConnectButton.isEnabled = true
            binding.ticktickDisconnectButton.isEnabled = false
        }
    }

    private suspend fun exchangeCodeForTokens(code: String) {
        val clientId = apiKeyStore.loadTickTickClientId() ?: return
        val clientSecret = apiKeyStore.loadTickTickClientSecret() ?: return

        withContext(Dispatchers.IO) {
            try {
                val creds = TickTickClient.exchangeCodeForTokens(clientId, clientSecret, code)
                withContext(Dispatchers.Main) {
                    apiKeyStore.saveTickTickAccessToken(creds.accessToken)
                    creds.refreshToken?.let { apiKeyStore.saveTickTickRefreshToken(it) }
                    updateTickTickStatus()
                    Snackbar.make(binding.root, R.string.ticktick_auth_success, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateTickTickStatus()
                    Snackbar.make(binding.root, getString(R.string.ticktick_auth_failed, e.message ?: "Unknown error"), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupObsidianControls() {
        binding.obsidianConnectButton.setOnClickListener {
            obsidianFolderLauncher.launch(null)
        }
        binding.obsidianDisconnectButton.setOnClickListener {
            obsidianVaultStore.clearFolder()
            updateObsidianStatus()
            Snackbar.make(binding.root, R.string.obsidian_cleared, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateObsidianStatus() {
        if (!::obsidianVaultStore.isInitialized || _binding == null) return

        val isConnected = obsidianVaultStore.hasUsableFolder()
        if (isConnected) {
            binding.obsidianStatusChip.setText(R.string.obsidian_status_connected)
            binding.obsidianStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_configured_bg)
            )
            binding.obsidianStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_configured_text)
            )
            binding.obsidianConnectButton.isEnabled = false
            binding.obsidianDisconnectButton.isEnabled = true
        } else {
            binding.obsidianStatusChip.setText(R.string.obsidian_status_not_connected)
            binding.obsidianStatusChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_bg)
            )
            binding.obsidianStatusChip.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.status_not_configured_text)
            )
            binding.obsidianConnectButton.isEnabled = true
            binding.obsidianDisconnectButton.isEnabled = false
        }
    }

    override fun onDestroyView() {
        oauthServer?.stop()
        super.onDestroyView()
        _binding = null
    }
}
