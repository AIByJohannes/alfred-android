package com.aibyjohannes.alfred.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.databinding.FragmentSettingsModelsBinding
import com.google.android.material.snackbar.Snackbar

class ModelSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsModelsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsModelsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        apiKeyStore = ApiKeyStore(requireContext())

        setupModelDropdown()
        setupSttModelDropdown()
        setupTtsModelDropdown()
        setupTtsVoiceDropdown()

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
