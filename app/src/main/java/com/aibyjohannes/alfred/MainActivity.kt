package com.aibyjohannes.alfred

import android.content.Intent
import android.os.Bundle
import androidx.core.view.GravityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.aibyjohannes.alfred.databinding.ActivityMainBinding
import com.aibyjohannes.alfred.data.ApiKeyStore
import com.aibyjohannes.alfred.data.ChatRepository
import com.aibyjohannes.alfred.data.ProfilePreferencesStore
import com.aibyjohannes.alfred.data.local.ChatHistoryLocationStore
import com.aibyjohannes.alfred.data.local.DocumentObsidianClient
import com.aibyjohannes.alfred.data.local.FileConversationStore
import com.aibyjohannes.alfred.data.local.FileLocalKnowledgeSearchClient
import com.aibyjohannes.alfred.data.local.FileMemorySearchSource
import com.aibyjohannes.alfred.data.local.ObsidianVaultStore
import com.aibyjohannes.alfred.data.local.StorageSkillClient
import com.aibyjohannes.alfred.data.local.NoteSearchIndexDatabase
import com.aibyjohannes.alfred.data.local.VaultSearchIndexer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aibyjohannes.alfred.ui.home.ConversationAdapter
import com.aibyjohannes.alfred.ui.home.HomeViewModel
import com.aibyjohannes.alfred.ui.home.UiConversation
import com.aibyjohannes.alfred.ui.home.DrawerProjectsAdapter
import com.aibyjohannes.alfred.ui.home.UiWorkspace
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.aibyjohannes.alfred.data.SysInfoProvider
import com.aibyjohannes.alfred.notifications.NotificationScheduler
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var workspaceAdapter: DrawerProjectsAdapter
    private lateinit var profilePreferencesStore: ProfilePreferencesStore
    private lateinit var chatHistoryLocationStore: ChatHistoryLocationStore
    private var drawerSwipeStartX = 0f
    private var drawerSwipeStartY = 0f
    private var drawerSwipeTracking = false
    private var drawerSwipeTouchSlop = 0
    private var drawerSwipeMinDistance = 0f

    private val chatHistoryFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            showChatHistoryFolderRequiredDialog()
            return@registerForActivityResult
        }

        try {
            chatHistoryLocationStore.persistFolder(uri)
            initializeHomeViewModel()
        } catch (error: Exception) {
            showChatHistoryFolderRequiredDialog(error.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        profilePreferencesStore = ProfilePreferencesStore(this)
        chatHistoryLocationStore = ChatHistoryLocationStore(this)
        drawerSwipeTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        drawerSwipeMinDistance = 48 * resources.displayMetrics.density

        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        initializeHomeViewModel()

        // Request ACCESS_COARSE_LOCATION at startup to enable datetime/location injection
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // SysInfoProvider automatically checks and handles permission dynamically.
        }.launch(Manifest.permission.ACCESS_COARSE_LOCATION)

        NotificationScheduler.rescheduleAll(this)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home,
                R.id.nav_settings
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.appBarMain.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_hamburger_two_lines)
        setupModelSelector()
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isTopLevel = destination.id == R.id.nav_home || destination.id == R.id.nav_settings
            if (isTopLevel) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            }

            val isChatOpen = destination.id == R.id.nav_home
            binding.appBarMain.toolbarModelSelectionPill.isVisible = isChatOpen
            if (isChatOpen) {
                updateModelSelectorPillText()
            }
            // Re-apply custom 2-line hamburger; the nav component resets it on every destination change
            binding.appBarMain.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_hamburger_two_lines)
        }
        setupDrawer()
        handleIntent(intent)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handleDrawerSwipeFromAnywhere(event)
        return super.dispatchTouchEvent(event)
    }

    private fun handleDrawerSwipeFromAnywhere(event: MotionEvent) {
        if (!::binding.isInitialized) return

        val drawerLayout = binding.drawerLayout
        val drawerLocked = drawerLayout.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_UNLOCKED
        if (drawerLocked || drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerSwipeTracking = false
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerSwipeStartX = event.rawX
                drawerSwipeStartY = event.rawY
                drawerSwipeTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawerSwipeTracking) return

                val deltaX = event.rawX - drawerSwipeStartX
                val deltaY = event.rawY - drawerSwipeStartY
                val minDistance = maxOf(drawerSwipeMinDistance, drawerSwipeTouchSlop * 2f)
                if (deltaX > minDistance && deltaX > abs(deltaY) * 1.5f) {
                    drawerSwipeTracking = false
                    drawerLayout.openDrawer(GravityCompat.START)
                } else if (abs(deltaY) > drawerSwipeTouchSlop * 2f && abs(deltaY) > abs(deltaX)) {
                    drawerSwipeTracking = false
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                drawerSwipeTracking = false
            }
        }
    }

    private fun initializeHomeViewModel() {
        val storage = chatHistoryLocationStore.createStorage()
        if (storage == null) {
            requestChatHistoryFolder()
            return
        }

        val apiKeyStore = ApiKeyStore(this)
        val conversationStore = FileConversationStore(storage, this)
        val localKnowledgeSearchClient = FileLocalKnowledgeSearchClient(
            conversationStore = conversationStore,
            memorySearchSource = FileMemorySearchSource(filesDir.resolve("memories.jsonl"))
        )
        val obsidianVaultStore = ObsidianVaultStore(this)
        obsidianVaultStore.parentFolderUri?.takeIf { obsidianVaultStore.hasUsableFolder() }?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = NoteSearchIndexDatabase.get(applicationContext)
                    val indexer = VaultSearchIndexer(applicationContext, db)
                    indexer.syncIndex(uri)
                } catch (e: Exception) {
                    android.util.Log.e("AlfredSearch", "Failed to sync index on startup", e)
                }
            }
        }
        val repository = ChatRepository(
            apiKeyStore = apiKeyStore,
            localKnowledgeSearchClient = localKnowledgeSearchClient,
            obsidianClientProvider = {
                obsidianVaultStore.parentFolderUri
                    ?.takeIf { obsidianVaultStore.hasUsableFolder() }
                    ?.let { DocumentObsidianClient(this, it) }
            },
            skillClient = StorageSkillClient(storage)
        )
        val sysInfoProvider = SysInfoProvider(this)
        homeViewModel.initialize(
            apiKeyStore = apiKeyStore,
            repository = repository,
            conversationStore = conversationStore,
            sysInfoProvider = sysInfoProvider,
            onChatActivity = {
                NotificationScheduler.recordChatActivity(this)
            }
        )
    }

    private fun requestChatHistoryFolder() {
        chatHistoryFolderLauncher.launch(null)
    }

    private fun showChatHistoryFolderRequiredDialog(detail: String? = null) {
        val message = if (detail.isNullOrBlank()) {
            getString(R.string.chat_history_folder_required_message)
        } else {
            getString(R.string.chat_history_folder_error_message, detail)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_history_folder_required_title)
            .setMessage(message)
            .setPositiveButton(R.string.choose_folder) { _, _ ->
                requestChatHistoryFolder()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupDrawer() {
        workspaceAdapter = DrawerProjectsAdapter(
            onWorkspaceSelected = { workspace ->
                homeViewModel.switchWorkspace(workspace.id)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onWorkspaceLongPressed = { workspace, view ->
                showWorkspaceMenu(workspace, view)
            }
        )

        binding.drawerProjectsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = workspaceAdapter
        }

        conversationAdapter = ConversationAdapter(
            onConversationSelected = { conversation ->
                homeViewModel.selectConversation(conversation.id)
                navigateToHome()
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onConversationDeleted = { conversation ->
                showDeleteConfirmation(conversation)
            }
        )

        binding.drawerConversationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
        }

        binding.btnNewChatLayout.setOnClickListener {
            homeViewModel.createConversationAndSwitch()
            navigateToHome()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.navHeader.btnCloseDrawer.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.drawerAddWorkspaceButton.setOnClickListener {
            showCreateWorkspaceDialog()
        }

        binding.drawerProfileRow.setOnClickListener {
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            if (navController.currentDestination?.id != R.id.nav_settings) {
                navController.navigate(R.id.nav_settings)
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        updateProfileRow()

        homeViewModel.workspaces.observe(this) { workspaces ->
            workspaceAdapter.submitData(workspaces, homeViewModel.activeWorkspaceId.value)
        }
        homeViewModel.activeWorkspaceId.observe(this) { activeWorkspaceId ->
            workspaceAdapter.submitData(homeViewModel.workspaces.value.orEmpty(), activeWorkspaceId)
        }

        homeViewModel.conversations.observe(this) { conversations ->
            conversationAdapter.submitList(conversations)
        }
        homeViewModel.activeConversationId.observe(this) { activeConversationId ->
            conversationAdapter.setActiveConversationId(activeConversationId)
        }
        homeViewModel.isLoading.observe(this) { isLoading ->
            binding.btnNewChatLayout.isEnabled = !isLoading
            binding.btnNewChatLayout.alpha = if (isLoading) 0.5f else 1f
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::profilePreferencesStore.isInitialized) {
            updateProfileRow()
            if (binding.appBarMain.toolbarModelSelectionPill.isVisible) {
                updateModelSelectorPillText()
            }
        }
    }

    private fun setupModelSelector() {
        binding.appBarMain.toolbarModelSelectionPill.setOnClickListener {
            showModelSelector()
        }
        updateModelSelectorPillText()
    }

    private fun showModelSelector() {
        val labels = resources.getStringArray(R.array.model_labels)
        val shortLabels = resources.getStringArray(R.array.model_short_labels)
        val values = resources.getStringArray(R.array.model_values)
        val apiKeyStore = ApiKeyStore(this)
        val currentModel = apiKeyStore.loadModel()
        val dialog = BottomSheetDialog(this, R.style.Theme_Alfred_BottomSheetDialog)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_model_selector, null)
        val optionsContainer = sheet.findViewById<LinearLayout>(R.id.model_options_container)

        labels.forEachIndexed { index, label ->
            val value = values.getOrNull(index) ?: return@forEachIndexed
            val row = layoutInflater.inflate(R.layout.item_model_option, optionsContainer, false)
            val isSelected = value == currentModel

            row.isSelected = isSelected
            row.findViewById<TextView>(R.id.model_option_title).text =
                shortLabels.getOrNull(index) ?: label
            row.findViewById<TextView>(R.id.model_option_subtitle).text = value
            row.findViewById<ImageView>(R.id.model_option_selected).isVisible = isSelected
            row.setOnClickListener {
                apiKeyStore.saveModel(value)
                updateModelSelectorPillText()
                dialog.dismiss()
            }
            optionsContainer.addView(row)
        }

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun updateModelSelectorPillText() {
        val currentModel = ApiKeyStore(this).loadModel()
        val labels = resources.getStringArray(R.array.model_toolbar_labels)
        val values = resources.getStringArray(R.array.model_values)
        val label = labels.getOrNull(values.indexOf(currentModel))
            ?: currentModel.substringAfterLast("/")
        binding.appBarMain.toolbarSelectedModelText.text = label
    }

    private fun updateProfileRow() {
        val displayName = profilePreferencesStore.displayName.ifBlank {
            ProfilePreferencesStore.DEFAULT_DISPLAY_NAME
        }
        binding.drawerProfileName.text = displayName
        binding.drawerProfileStatus.text = profilePreferencesStore.statusLabel
        binding.drawerProfileInitials.text = initialsFor(displayName)
    }

    private fun initialsFor(displayName: String): String {
        val parts = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return parts.take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .joinToString("")
            .ifBlank { "A" }
    }

    private fun navigateToHome() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.nav_home) {
            navController.navigate(R.id.nav_home)
        }
    }

    private fun showDeleteConfirmation(conversation: UiConversation) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, conversation.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                homeViewModel.deleteConversation(conversation.id)
            }
            .show()
    }

    private fun showCreateWorkspaceDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.workspace_name_hint)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (16 * resources.displayMetrics.density).toInt()
                setMargins(margin, 8, margin, 8)
            }
            addView(input, lp)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_workspace)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    homeViewModel.createWorkspace(name)
                }
            }
            .show()
    }

    private fun showWorkspaceMenu(workspace: UiWorkspace, view: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, view)
        popup.menu.add(getString(R.string.rename_workspace))
        
        val isLastWorkspace = homeViewModel.workspaces.value.orEmpty().size <= 1
        if (!isLastWorkspace) {
            popup.menu.add(getString(R.string.delete_workspace))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.rename_workspace) -> {
                    showRenameWorkspaceDialog(workspace)
                    true
                }
                getString(R.string.delete_workspace) -> {
                    showDeleteWorkspaceConfirmation(workspace)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameWorkspaceDialog(workspace: UiWorkspace) {
        val input = EditText(this).apply {
            hint = getString(R.string.workspace_name_hint)
            setText(workspace.name)
            setSelection(workspace.name.length)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (16 * resources.displayMetrics.density).toInt()
                setMargins(margin, 8, margin, 8)
            }
            addView(input, lp)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_workspace)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    homeViewModel.renameWorkspace(workspace.id, name)
                }
            }
            .show()
    }

    private fun showDeleteWorkspaceConfirmation(workspace: UiWorkspace) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_workspace_confirm_title)
            .setMessage(getString(R.string.delete_workspace_confirm_message, workspace.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                homeViewModel.deleteWorkspace(workspace.id)
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            if (intent.type?.startsWith("text/") == true) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    homeViewModel.setSharedText(sharedText)
                    navigateToHome()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
