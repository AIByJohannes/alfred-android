package com.aibyjohannes.alfred.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
        setupModelDropdown()
        updateStatus()

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

