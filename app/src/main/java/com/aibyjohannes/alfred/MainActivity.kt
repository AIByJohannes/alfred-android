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
        conversationAdapter = ConversationAdapter { conversation ->
            homeViewModel.selectConversation(conversation.id)
            navigateToHome()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

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

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
