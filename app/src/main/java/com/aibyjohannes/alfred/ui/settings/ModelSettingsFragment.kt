package com.aibyjohannes.alfred.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.databinding.FragmentSettingsModelsBinding
import com.aibyjohannes.alfred.data.local.LocalGemmaModelStore
import com.aibyjohannes.alfred.ui.home.AndroidLocalSpeechRecognizer
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

class ModelSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsModelsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var localGemmaModelStore: LocalGemmaModelStore

    private val modelFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        binding.importLocalGemmaButton.isEnabled = false
        binding.useLocalGemmaButton.isEnabled = false
        binding.localGemmaStatus.setText(R.string.local_gemma_importing)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = localGemmaModelStore.importModel(uri)
            result.onSuccess {
                apiKeyStore.saveModel(LocalGemmaModelStore.LOCAL_MODEL_ID)
                Snackbar.make(binding.root, R.string.local_gemma_imported, Snackbar.LENGTH_LONG).show()
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.local_gemma_import_failed, error.message ?: error.javaClass.simpleName),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            updateLocalGemmaUi()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsModelsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        apiKeyStore = ApiKeyStore(requireContext())
        localGemmaModelStore = LocalGemmaModelStore(requireContext())

        setupModelDropdown()
        setupLocalGemma()
        setupSttModelDropdown()
        setupTtsModelDropdown()
        setupTtsVoiceDropdown()
        setupLocalVoice()

        return root
    }

    private fun setupLocalGemma() {
        binding.importLocalGemmaButton.setOnClickListener {
            modelFileLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }
        binding.useLocalGemmaButton.setOnClickListener {
            apiKeyStore.saveModel(LocalGemmaModelStore.LOCAL_MODEL_ID)
            updateLocalGemmaUi()
            Snackbar.make(binding.root, R.string.local_gemma_selected, Snackbar.LENGTH_SHORT).show()
        }
        updateLocalGemmaUi()
    }

    private fun updateLocalGemmaUi() {
        val installed = localGemmaModelStore.installedModelPath() != null
        val active = apiKeyStore.loadModel() == LocalGemmaModelStore.LOCAL_MODEL_ID
        val size = formatBytes(localGemmaModelStore.installedModelSizeBytes())
        binding.localGemmaStatus.text = when {
            installed && active -> getString(R.string.local_gemma_active, size)
            installed -> getString(R.string.local_gemma_installed, size)
            else -> getString(R.string.local_gemma_not_installed)
        }
        binding.importLocalGemmaButton.isEnabled = true
        binding.importLocalGemmaButton.setText(
            if (installed) R.string.replace_local_gemma else R.string.import_local_gemma
        )
        binding.useLocalGemmaButton.isEnabled = installed && !active
    }

    private fun formatBytes(bytes: Long): String = if (bytes < 1024L * 1024L * 1024L) {
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
            updateLocalGemmaUi()
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

    private fun setupLocalVoice() {
        binding.preferLocalVoiceSwitch.isChecked = apiKeyStore.isPreferLocalVoiceEnabled()
        binding.localVoiceFallbackSwitch.isChecked = apiKeyStore.isLocalVoiceFallbackEnabled()
        binding.preferLocalVoiceSwitch.setOnCheckedChangeListener { _, checked ->
            apiKeyStore.savePreferLocalVoice(checked)
        }
        binding.localVoiceFallbackSwitch.setOnCheckedChangeListener { _, checked ->
            apiKeyStore.saveLocalVoiceFallback(checked)
        }
        binding.downloadLocalVoiceModelsButton.setOnClickListener {
            runCatching {
                AndroidLocalSpeechRecognizer(requireContext()).use { it.requestModelDownload() }
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            }.onSuccess {
                Snackbar.make(binding.root, R.string.local_voice_download_started, Snackbar.LENGTH_LONG).show()
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "No offline voice installer is available.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
