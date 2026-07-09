package com.aibyjohannes.alfred.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aibyjohannes.alfred.MainActivity
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ProfilePreferencesStore
import com.aibyjohannes.alfred.data.local.ChatHistoryLocationStore
import com.aibyjohannes.alfred.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private lateinit var profilePreferencesStore: ProfilePreferencesStore
    private lateinit var chatHistoryLocationStore: ChatHistoryLocationStore

    private val chatHistoryFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                chatHistoryLocationStore.persistFolder(uri)
                updateStorageStatus()
            } catch (error: Exception) {
                Toast.makeText(context, getString(R.string.chat_history_folder_error_message, error.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionStatuses()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionStatuses()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionStatuses()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profilePreferencesStore = ProfilePreferencesStore(requireContext())
        chatHistoryLocationStore = ChatHistoryLocationStore(requireContext())

        binding.btnSetupStorage.setOnClickListener {
            chatHistoryFolderLauncher.launch(null)
        }

        binding.btnSetupMic.setOnClickListener {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnSetupLocation.setOnClickListener {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        binding.btnSetupNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(context, "Notifications enabled automatically on this Android version", Toast.LENGTH_SHORT).show()
                updatePermissionStatuses()
            }
        }

        binding.btnGetStarted.setOnClickListener {
            profilePreferencesStore.isOnboardingCompleted = true
            (activity as? MainActivity)?.onOnboardingComplete()
        }

        updateStorageStatus()
        updatePermissionStatuses()
    }

    private fun updateStorageStatus() {
        val hasFolder = chatHistoryLocationStore.hasUsableFolder()
        if (hasFolder) {
            binding.onboardingStorageStatus.text = getString(R.string.status_configured)
            binding.onboardingStorageStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_configured_bg))
            binding.btnSetupStorage.isEnabled = false
            binding.btnSetupStorage.alpha = 0.5f
            binding.btnGetStarted.isEnabled = true
        } else {
            binding.onboardingStorageStatus.text = getString(R.string.onboarding_storage_status_not_set)
            binding.onboardingStorageStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSetupStorage.isEnabled = true
            binding.btnSetupStorage.alpha = 1.0f
            binding.btnGetStarted.isEnabled = false
        }
    }

    private fun updatePermissionStatuses() {
        // Mic permission status
        val hasMic = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            binding.onboardingMicStatus.text = getString(R.string.onboarding_mic_status_granted)
            binding.onboardingMicStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_configured_bg))
            binding.btnSetupMic.isEnabled = false
            binding.btnSetupMic.alpha = 0.5f
        } else {
            binding.onboardingMicStatus.text = getString(R.string.onboarding_mic_status_denied)
            binding.onboardingMicStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSetupMic.isEnabled = true
            binding.btnSetupMic.alpha = 1.0f
        }

        // Location permission status
        val hasLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            binding.onboardingLocationStatus.text = getString(R.string.onboarding_location_status_granted)
            binding.onboardingLocationStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_configured_bg))
            binding.btnSetupLocation.isEnabled = false
            binding.btnSetupLocation.alpha = 0.5f
        } else {
            binding.onboardingLocationStatus.text = getString(R.string.onboarding_location_status_denied)
            binding.onboardingLocationStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSetupLocation.isEnabled = true
            binding.btnSetupLocation.alpha = 1.0f
        }

        // Notification permission status
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // True by default on older versions
        }

        if (hasNotifications) {
            binding.onboardingNotificationsStatus.text = getString(R.string.onboarding_notifications_status_granted)
            binding.onboardingNotificationsStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_configured_bg))
            binding.btnSetupNotifications.isEnabled = false
            binding.btnSetupNotifications.alpha = 0.5f
        } else {
            binding.onboardingNotificationsStatus.text = getString(R.string.onboarding_notifications_status_denied)
            binding.onboardingNotificationsStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSetupNotifications.isEnabled = true
            binding.btnSetupNotifications.alpha = 1.0f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
