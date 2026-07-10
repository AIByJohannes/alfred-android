package com.aibyjohannes.alfred.ui.settings

import android.Manifest
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ProfilePreferencesStore
import com.aibyjohannes.alfred.databinding.FragmentSettingsBinding
import com.aibyjohannes.alfred.notifications.NotificationPreferencesStore
import com.aibyjohannes.alfred.notifications.NotificationScheduler
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var profilePreferencesStore: ProfilePreferencesStore
    private lateinit var notificationPreferencesStore: NotificationPreferencesStore
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

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

        setupButtons()
        setupProfileControls()
        setupEfficiencyPrivacyControls()
        setupNotificationControls()
        updateStatus()
        updateNotificationControls()
        setupNavigationButtons()

        return root
    }

    private fun setupNavigationButtons() {
        binding.cardNavModels.setOnClickListener {
            findNavController().navigate(R.id.nav_settings_models)
        }
        binding.cardNavTools.setOnClickListener {
            findNavController().navigate(R.id.nav_settings_tools)
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

    private fun setupEfficiencyPrivacyControls() {
        binding.efficiencyModeSwitch.isChecked = apiKeyStore.isEfficiencyModeEnabled()
        binding.privacyModeSwitch.isChecked = apiKeyStore.isPrivacyModeEnabled()

        binding.efficiencyModeSwitch.setOnCheckedChangeListener { _, enabled ->
            apiKeyStore.saveEfficiencyMode(enabled)
            Snackbar.make(
                binding.root,
                if (enabled) "Cache-efficient routing enabled" else "Cache-efficient routing disabled",
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.privacyModeSwitch.setOnCheckedChangeListener { _, enabled ->
            apiKeyStore.savePrivacyMode(enabled)
            Snackbar.make(
                binding.root,
                if (enabled) "Zero data retention routing enabled" else "Zero data retention routing disabled",
                Snackbar.LENGTH_SHORT
            ).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
