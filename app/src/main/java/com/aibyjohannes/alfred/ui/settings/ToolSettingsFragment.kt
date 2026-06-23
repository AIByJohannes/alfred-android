package com.aibyjohannes.alfred.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aibyjohannes.alfred.R
import com.aibyjohannes.alfred.core.ticktick.TickTickClient
import com.aibyjohannes.alfred.core.ticktick.TickTickClient.Companion.OAUTH_REDIRECT_URI
import com.aibyjohannes.alfred.core.ticktick.TickTickClient.Companion.OAUTH_SCOPE
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.local.NoteSearchIndexDatabase
import com.aibyjohannes.alfred.data.local.ObsidianVaultStore
import com.aibyjohannes.alfred.data.local.VaultSearchIndexer
import com.aibyjohannes.alfred.data.ticktick.TickTickOAuthServer
import com.aibyjohannes.alfred.databinding.FragmentSettingsToolsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ToolSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsToolsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var obsidianVaultStore: ObsidianVaultStore
    private var oauthServer: TickTickOAuthServer? = null

    private val obsidianFolderLauncher = registerForActivityResult(
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsToolsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        apiKeyStore = ApiKeyStore(requireContext())
        obsidianVaultStore = ObsidianVaultStore(requireContext())

        setupTickTickControls()
        updateTickTickStatus()
        setupObsidianControls()
        updateObsidianStatus()

        return root
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
