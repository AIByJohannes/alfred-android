package com.aibyjohannes.alfred.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.databinding.FragmentSettingsBinding
import com.google.android.material.snackbar.Snackbar

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        apiKeyStore = ApiKeyStore(requireContext())

        setupButtons()
        updateStatus()

        return root
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

    private fun updateStatus() {
        val hasKey = apiKeyStore.hasApiKey()
        if (hasKey) {
            binding.apiKeyStatus.setText(R.string.api_key_configured)
            binding.apiKeyStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.teal_700)
            )
        } else {
            binding.apiKeyStatus.setText(R.string.api_key_not_configured)
            binding.apiKeyStatus.setTextColor(
                ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

