package com.aibyjohannes.alfred

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
import com.aibyjohannes.alfred.data.local.FileConversationStore
import com.aibyjohannes.alfred.data.local.FileLocalKnowledgeSearchClient
import com.aibyjohannes.alfred.data.local.FileMemorySearchSource
import com.aibyjohannes.alfred.ui.home.ConversationAdapter
import com.aibyjohannes.alfred.ui.home.HomeViewModel
import com.aibyjohannes.alfred.ui.home.UiConversation
import com.aibyjohannes.alfred.ui.home.WorkspaceChipAdapter
import com.aibyjohannes.alfred.ui.home.UiWorkspace
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.aibyjohannes.alfred.data.SysInfoProvider
import com.aibyjohannes.alfred.notifications.NotificationScheduler
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build



class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var workspaceAdapter: WorkspaceChipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        initializeHomeViewModel()

        // Request ACCESS_COARSE_LOCATION at startup to enable datetime/location injection
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // SysInfoProvider automatically checks and handles permission dynamically.
        }.launch(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Request POST_NOTIFICATIONS permission on Android 13+ and schedule daily reminders
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    NotificationScheduler.scheduleDailyReminder(this)
                }
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationScheduler.scheduleDailyReminder(this)
        }

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
        setupDrawer()
    }

    private fun initializeHomeViewModel() {
        val apiKeyStore = ApiKeyStore(this)
        val conversationStore = FileConversationStore(filesDir)
        val localKnowledgeSearchClient = FileLocalKnowledgeSearchClient(
            conversationStore = conversationStore,
            memorySearchSource = FileMemorySearchSource(filesDir.resolve("memories.jsonl"))
        )
        val repository = ChatRepository(apiKeyStore, localKnowledgeSearchClient)
        val sysInfoProvider = SysInfoProvider(this)
        homeViewModel.initialize(apiKeyStore, repository, conversationStore, sysInfoProvider)
    }

    private fun setupDrawer() {
        workspaceAdapter = WorkspaceChipAdapter(
            onWorkspaceSelected = { workspace ->
                homeViewModel.switchWorkspace(workspace.id)
            },
            onAddWorkspace = {
                showCreateWorkspaceDialog()
            },
            onWorkspaceLongPressed = { workspace, view ->
                showWorkspaceMenu(workspace, view)
            }
        )

        binding.drawerWorkspacesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
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

        binding.drawerNewConversationButton.setOnClickListener {
            homeViewModel.createConversationAndSwitch()
            navigateToHome()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.drawerSettingsRow.setOnClickListener {
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            if (navController.currentDestination?.id != R.id.nav_settings) {
                navController.navigate(R.id.nav_settings)
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

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

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
