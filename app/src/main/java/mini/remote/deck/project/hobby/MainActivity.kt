package mini.remote.deck.project.hobby

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import mini.remote.deck.project.hobby.ui.theme.MyApplication45Theme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator


object ConfigManager {
    private const val PREFS_NAME = "settings"
    private const val KEY_DEVICE_IP = "device_ip"
    private const val KEY_PROFILES = "remote_profiles"
    private const val KEY_ACTIVE_PROFILE_NAME = "active_profile_name"
    private const val DEFAULT_IP = "192.168.1.100"

    fun getBaseUrl(context: Context): String {
        val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip = sharedPref.getString(KEY_DEVICE_IP, DEFAULT_IP) ?: DEFAULT_IP
        return "http://$ip:8000/"
    }

    fun updateIp(context: Context, newIp: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DEVICE_IP, newIp.trim()) }
    }

    fun saveProfiles(context: Context, profiles: List<RemoteProfile>) {
        val json = Gson().toJson(profiles)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PROFILES, json)
        }
    }

    fun loadProfiles(context: Context): List<RemoteProfile> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
        return if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<RemoteProfile>>() {}.type
            Gson().fromJson(json, type)
        } else {
            emptyList()
        }
    }

    fun saveActiveProfileName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACTIVE_PROFILE_NAME, name)
        }
    }

    fun loadActiveProfileName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE_NAME, null)
    }
}

@Keep
data class CharacterRequest(val char: String)

@Keep
data class MouseMoveRequest(val dx: Float, val dy: Float, val sensitivity: Float)

@Keep
data class ClickRequest(val button: String)

@Keep
data class ScrollRequest(val dy: Float)

@Keep
data class HScrollGestureRequest(val dx: Float, val state: String)

@Keep
data class KeyPressRequest(val key: String)

@Keep
data class MediaKeyRequest(val key: String)

@Keep
data class Command(val type: String, val payload: Map<String, Any>)

@Keep
data class WidgetScript(
    val type: String,
    val label: String,
    val iconName: String,
    val interactionType: String,
    val commands: Map<String, Command>
)

@Keep
data class GridPosition(val row: Int, val col: Int)

@Keep
data class GridSize(val width: Int, val height: Int)

@Keep
data class RemoteWidget(
    val id: String = UUID.randomUUID().toString(),
    val position: GridPosition,
    val size: GridSize,
    val script: WidgetScript
)

@Keep
data class RemoteProfile(val name: String, val widgets: List<RemoteWidget>)


object OkHttpProvider {
    val client = OkHttpClient()
}

@SuppressLint("StaticFieldLeak")
class MainViewModel(private val context: Context) : ViewModel() {
    private val sharedPref = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaultIp = "192.168.1.100"

    var ipAddress by mutableStateOf(sharedPref.getString("device_ip", defaultIp) ?: defaultIp); private set
    var pointerSensitivity by mutableFloatStateOf(sharedPref.getFloat("pointer_sensitivity", 0.5f)); private set
    var verticalScrollSensitivity by mutableFloatStateOf(sharedPref.getFloat("vertical_scroll_sensitivity", 5.0f)); private set
    var horizontalScrollSensitivity by mutableFloatStateOf(sharedPref.getFloat("horizontal_scroll_sensitivity", 5.0f)); private set
    var isScanning by mutableStateOf(false); private set
    var showUploadDialog by mutableStateOf(false)
    var uploadStatus by mutableStateOf("")
    var uploadProgress by mutableFloatStateOf(0f)
    var profiles by mutableStateOf<List<RemoteProfile>>(emptyList()); private set
    var activeProfile by mutableStateOf<RemoteProfile?>(null); private set
    var discoveredDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList()); private set
    var isEditMode by mutableStateOf(false); private set
    val widgetLibrary = WidgetLibrary()

    enum class DeviceStatus { CONNECTABLE }

    @Keep
    data class DiscoveredDevice(val ip: String, val hostname: String?, val status: DeviceStatus)

    @Keep
    data class IdentifyResponse(val app: String, val hostname: String)

    private var baseUrl = "http://${ipAddress}:8000/"
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    init {
        loadProfiles()
    }

    fun toggleEditMode() {
        isEditMode = !isEditMode
    }

    fun changeActiveProfile(profile: RemoteProfile) {
        if (isEditMode) isEditMode = false
        activeProfile = profile
        ConfigManager.saveActiveProfileName(context, profile.name)
    }

    fun addNewProfile(name: String) {
        if (name.isBlank() || profiles.any { it.name.equals(name, ignoreCase = true) }) {
            return
        }
        val newProfile = RemoteProfile(name, emptyList())
        profiles = profiles + newProfile
        changeActiveProfile(newProfile)
        saveProfiles()
    }

    fun deleteProfile(profile: RemoteProfile) {
        if (profiles.size <= 1) return

        val updatedProfiles = profiles.toMutableList()
        updatedProfiles.remove(profile)
        profiles = updatedProfiles

        if (activeProfile?.name == profile.name) {
            changeActiveProfile(profiles.first())
        }
        saveProfiles()
    }

    fun addWidgetToProfile(widgetScript: WidgetScript, position: GridPosition, size: GridSize) {
        val currentProfile = activeProfile ?: return

        val newWidgetRect = (position.row until (position.row + size.height)) to
                (position.col until (position.col + size.width))

        val collision = currentProfile.widgets.any { existingWidget ->
            val existingWidgetRect = (existingWidget.position.row until (existingWidget.position.row + existingWidget.size.height)) to
                    (existingWidget.position.col until (existingWidget.position.col + existingWidget.size.width))

            newWidgetRect.first.intersect(existingWidgetRect.first).isNotEmpty() &&
                    newWidgetRect.second.intersect(existingWidgetRect.second).isNotEmpty()
        }

        if (collision) {
            Log.w("ViewModel", "Collision detected at $position for size $size. Cannot add widget.")
            return
        }

        val newWidget = RemoteWidget(position = position, size = size, script = widgetScript)
        val updatedWidgets = currentProfile.widgets + newWidget
        val updatedProfile = currentProfile.copy(widgets = updatedWidgets)

        val profileIndex = profiles.indexOfFirst { it.name == updatedProfile.name }
        if (profileIndex != -1) {
            val newProfilesList = profiles.toMutableList()
            newProfilesList[profileIndex] = updatedProfile
            profiles = newProfilesList
            activeProfile = updatedProfile
        }
        saveProfiles()
    }

    fun deleteWidget(widgetId: String) {
        val currentProfile = activeProfile ?: return

        val updatedWidgets = currentProfile.widgets.filterNot { it.id == widgetId }

        if (updatedWidgets.size < currentProfile.widgets.size) {
            val updatedProfile = currentProfile.copy(widgets = updatedWidgets)

            val profileIndex = profiles.indexOfFirst { it.name == updatedProfile.name }
            if (profileIndex != -1) {
                val newProfilesList = profiles.toMutableList()
                newProfilesList[profileIndex] = updatedProfile
                profiles = newProfilesList
                activeProfile = updatedProfile
            }
            saveProfiles()
        }
    }


    private fun loadProfiles() {
        val loadedProfiles = ConfigManager.loadProfiles(context)
        if (loadedProfiles.isEmpty()) {
            val defaultProfiles = createDefaultProfiles()
            profiles = defaultProfiles
            changeActiveProfile(defaultProfiles.first())
        } else {
            profiles = loadedProfiles
            val activeProfileName = ConfigManager.loadActiveProfileName(context)
            val profileToActivate =
                loadedProfiles.find { it.name == activeProfileName } ?: loadedProfiles.first()
            changeActiveProfile(profileToActivate)
        }
    }

    private fun saveProfiles() {
        ConfigManager.saveProfiles(context, profiles)
    }

    private fun createDefaultProfiles(): List<RemoteProfile> {
        val editingWidgets = listOf(
            RemoteWidget(
                position = GridPosition(0, 0),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Copy")!!
            ),
            RemoteWidget(
                position = GridPosition(0, 1),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Paste")!!
            ),
            RemoteWidget(
                position = GridPosition(0, 2),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Select All")!!
            )
        )
        val navWidgets = listOf(
            RemoteWidget(
                position = GridPosition(0, 1),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Up")!!
            ),
            RemoteWidget(
                position = GridPosition(1, 0),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Left")!!
            ),
            RemoteWidget(
                position = GridPosition(1, 1),
                size = GridSize(1, 1),
                script = widgetLibrary.getScript("Down")!!
            ),

            RemoteWidget(
                position = GridPosition(2, 0),
                size = GridSize(width = 4, height = 2), // Spans the full width
                script = widgetLibrary.getScript("Touchpad")!!
            )
        )
        return listOf(
            RemoteProfile("Editing", editingWidgets),
            RemoteProfile("Navigation", navWidgets)
        )
    }

    fun updateIpAddress(newIp: String) {
        val p = newIp.trim()
        ipAddress = p
        sharedPref.edit { putString("device_ip", p) }
        ConfigManager.updateIp(context, p)
        baseUrl = "http://$p:8000/"
    }

    fun updatePointerSensitivity(newValue: Float) {
        pointerSensitivity = newValue
        sharedPref.edit { putFloat("pointer_sensitivity", newValue) }
    }

    fun updateVerticalScrollSensitivity(newValue: Float) {
        verticalScrollSensitivity = newValue
        sharedPref.edit { putFloat("vertical_scroll_sensitivity", newValue) }
    }

    fun updateHorizontalScrollSensitivity(newValue: Float) {
        horizontalScrollSensitivity = newValue
        sharedPref.edit { putFloat("horizontal_scroll_sensitivity", newValue) }
    }

    private suspend fun sendRequest(endpoint: String, jsonBody: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody.toRequestBody(json)
                val request = Request.Builder().url("$baseUrl$endpoint").post(body).build()
                OkHttpProvider.client.newCall(request).execute().use {
                    if (!it.isSuccessful) Log.e("API", "Req failed: ${it.code}")
                }
            } catch (e: Exception) {
                Log.e("API", "Error: ${e.message}")
            }
        }
    }

    fun onSendCharacter(word: String) {
        viewModelScope.launch {
            val data = gson.toJson(CharacterRequest(word))
            sendRequest("send-char", data)
        }
    }

    fun onMoveMouse(dx: Float, dy: Float) {
        viewModelScope.launch {
            val (fDx, fDy) = calculateDrag(dx, dy, pointerSensitivity)
            val command = Command("mouse_move", mapOf("dx" to fDx, "dy" to fDy))
            executeCommand(command)
        }
    }

    private fun calculateDrag(x: Float, y: Float, s: Float): Pair<Float, Float> = Pair(x * s, y * s)
    fun onClickMouse(button: String) {
        viewModelScope.launch {
            val data = gson.toJson(ClickRequest(button))
            sendRequest("click-mouse", data)
        }
    }

    fun onScrollMouse(scrollAmount: Float) {
        viewModelScope.launch {
            val command = Command("v_scroll", mapOf("dy" to scrollAmount * verticalScrollSensitivity))
            executeCommand(command)
        }
    }

    fun onHorizontalScrollGesture(state: String, dx: Float) {
        viewModelScope.launch {
            val fDx = dx * horizontalScrollSensitivity
            val command = Command("h_scroll", mapOf("state" to state, "dx" to fDx))
            executeCommand(command)
        }
    }

    fun onPressKey(key: String) {
        viewModelScope.launch {
            val data = gson.toJson(KeyPressRequest(key))
            sendRequest("press-key", data)
        }
    }

    fun onPressMediaKey(key: String) {
        viewModelScope.launch {
            val data = gson.toJson(MediaKeyRequest(key))
            sendRequest("press-media-key", data)
        }
    }

    fun onPressHotkey(key: String) {
        viewModelScope.launch {
            val data = gson.toJson(KeyPressRequest(key))
            sendRequest("press-hotkey", data)
        }
    }

    fun openDownloadsFolder() {
        viewModelScope.launch { sendRequest("open-folder", "") }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun uploadFilesFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return
        showUploadDialog = true
        uploadStatus = "Starting..."
        uploadProgress = 0f
        viewModelScope.launch {
            FileUploadManager.uploadMultipleFiles(
                context,
                uris,
                onStatusUpdate = { newStatus -> uploadStatus = newStatus },
                onProgressUpdate = { newProgress -> uploadProgress = newProgress }
            )
        }
    }

    fun executeCommand(command: Command) {
        viewModelScope.launch {
            val jsonCmd = gson.toJson(command)
            sendRequest("execute-command", jsonCmd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocalIpSubnet(): String? {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (ia in ni.inetAddresses) {
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address && ia.isSiteLocalAddress) {
                        val hostAddress = ia.hostAddress
                        if (hostAddress != null) return hostAddress.substring(
                            0,
                            hostAddress.lastIndexOf('.') + 1
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("SCAN", "Error", ex)
        }
        return null
    }

    fun scanForDevices() {
        if (isScanning) return
        viewModelScope.launch {
            isScanning = true
            discoveredDevices = emptyList()
            val subnet = getLocalIpSubnet()
            if (subnet == null) {
                isScanning = false
                return@launch
            }
            try {
                val devices = withContext(Dispatchers.IO) {
                    val tempDevices = mutableListOf<DiscoveredDevice>()
                    val scannerClient = OkHttpClient.Builder()
                        .connectTimeout(1, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .build()

                    val jobs = (1..254).map { i ->
                        launch {
                            val host = "$subnet$i"
                            Log.d("KD", host)
                            try {
                                val req = Request.Builder().url("http://$host:8000/identify").get().build()
                                scannerClient.newCall(req).execute().use { rsp ->
                                    if (rsp.isSuccessful) {
                                        rsp.body?.string()?.let { body ->
                                            val idr = gson.fromJson(body, IdentifyResponse::class.java)
                                            if (idr.app == "RemoteControlServer") {
                                                synchronized(tempDevices) {
                                                    tempDevices.add(
                                                        DiscoveredDevice(
                                                            host,
                                                            idr.hostname,
                                                            DeviceStatus.CONNECTABLE
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    jobs.joinAll()
                    tempDevices.sortedBy { it.ip }
                }
                discoveredDevices = devices
            } catch (e: Exception) {
                Log.e("SCAN", "Error", e)
            } finally {
                isScanning = false
            }
        }
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(context.applicationContext) as T
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    //object Touchpad : Screen("touchpad", "Touchpad", Icons.Default.Mouse)
    object Profiles : Screen("profiles", "Profiles", Icons.Default.Widgets)
    //object Media : Screen("media", "Media", Icons.Default.PlayCircleOutline)
    //object Power : Screen("power", "Power", Icons.Default.PowerSettingsNew)
    object DeviceLocation : Screen("devices", "Devices", Icons.Default.NetworkCheck)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems =
    listOf(
        //Screen.Touchpad,
        Screen.Profiles,
        //Screen.Media,
        //Screen.Power,
        Screen.DeviceLocation,
        Screen.Settings
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    filePickerLauncher: ManagedActivityResultLauncher<Array<String>, List<@JvmSuppressWildcards Uri>>
) {
    val navController = rememberNavController()
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    title = { Text("Remote Control", fontWeight = FontWeight.Bold) }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    bottomNavItems.forEach { screen ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                //startDestination = Screen.Touchpad.route,
                startDestination = Screen.Profiles.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                //composable(Screen.Touchpad.route) { TouchpadScreen(viewModel, filePickerLauncher) }
                composable(Screen.Profiles.route) { ProfilesScreen(viewModel, filePickerLauncher) }
                //composable(Screen.Media.route) { MediaScreen(viewModel) }
                //composable(Screen.Power.route) { PowerScreen(viewModel) }
                composable(Screen.DeviceLocation.route) { DeviceLocationScreen(viewModel) }
                composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            }
        }
        if (viewModel.showUploadDialog) {
            UploadProgressDialog(
                status = viewModel.uploadStatus,
                progress = viewModel.uploadProgress,
                onDismiss = {
                    if (!viewModel.uploadStatus.contains("Sending", ignoreCase = true)) {
                        viewModel.showUploadDialog = false
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: MainViewModel,
    filePickerLauncher: ManagedActivityResultLauncher<Array<String>, List<@JvmSuppressWildcards Uri>>
) {
    val currentProfile = viewModel.activeProfile
    val isEditMode = viewModel.isEditMode

    var showMenu by remember { mutableStateOf(false) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var showWidgetDrawer by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var widgetToResize by remember { mutableStateOf<Pair<WidgetScript, GridPosition>?>(null) }
    var selectedGridPosition by remember { mutableStateOf<GridPosition?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    if (showAddProfileDialog) {
        AddNewProfileDialog(
            onDismiss = { showAddProfileDialog = false },
            onConfirm = { name ->
                viewModel.addNewProfile(name)
                showAddProfileDialog = false
            }
        )
    }

    if (showDeleteConfirmationDialog && currentProfile != null) {
        DeleteProfileDialog(
            profileName = currentProfile.name,
            onConfirm = {
                viewModel.deleteProfile(currentProfile)
                showDeleteConfirmationDialog = false
            },
            onDismiss = { showDeleteConfirmationDialog = false }
        )
    }

    widgetToResize?.let { (script, position) ->
        if (showResizeDialog) {
            ResizeWidgetDialog(
                onDismiss = { showResizeDialog = false },
                onConfirm = { size ->
                    viewModel.addWidgetToProfile(script, position, size)
                    showResizeDialog = false
                }
            )
        }
    }

    if (showWidgetDrawer) {
        ModalBottomSheet(onDismissRequest = { showWidgetDrawer = false }, sheetState = sheetState) {
            WidgetDrawer(
                widgetLibrary = viewModel.widgetLibrary,
                onWidgetSelected = { widgetScript ->
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showWidgetDrawer = false
                    }
                    selectedGridPosition?.let { position ->
                        if (widgetScript.interactionType == "button_tap") {
                            viewModel.addWidgetToProfile(widgetScript, position, GridSize(1, 1))
                        } else {
                            widgetToResize = widgetScript to position
                            showResizeDialog = true
                        }
                    }
                }
            )
        }
    }

    currentProfile?.let { profile ->
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = profile.name, style = MaterialTheme.typography.titleLarge)
                Row {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(if (isEditMode) Icons.Default.Check else Icons.Default.Edit, "Edit")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            viewModel.profiles.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = { viewModel.changeActiveProfile(p); showMenu = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Add New Profile...") },
                                onClick = { showAddProfileDialog = true; showMenu = false })
                            if (viewModel.profiles.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("Delete '${profile.name}'", color = MaterialTheme.colorScheme.error) },
                                    onClick = { showDeleteConfirmationDialog = true; showMenu = false }
                                )
                            }
                        }
                    }
                }
            }

            val gridCells = 4
            val totalCells = gridCells * 8 // Increased rows for more space
            val placedWidgets = profile.widgets.associateBy { it.position }

            val occupiedCells = remember(profile.widgets) {
                mutableSetOf<GridPosition>().apply {
                    profile.widgets.forEach { widget ->
                        for (row in 0 until widget.size.height) {
                            for (col in 0 until widget.size.width) {
                                add(GridPosition(widget.position.row + row, widget.position.col + col))
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()) // usage of scroll state
            ) {
                // Calculate precise cell sizes
                val gap = 8.dp
                val colCount = 4
                val rowCount = 8

                // Calculate width of a single cell dynamically based on screen width
                val cellWidth = (maxWidth - (gap * (colCount - 1))) / colCount
                val cellHeight = 100.dp

                // --- FIX STARTS HERE ---
                // Calculate the total height of the grid content
                val totalGridHeight = (cellHeight * rowCount) + (gap * (rowCount - 1))

                // Add an invisible Box that forces the scroll container to be tall enough
                Box(Modifier.size(width = maxWidth, height = totalGridHeight))
                // --- FIX ENDS HERE ---

                // LAYER 1: Render Empty Slots (Background)
                for (r in 0 until rowCount) {
                    for (c in 0 until colCount) {
                        val pos = GridPosition(r, c)

                        val xPos = (cellWidth + gap) * c
                        val yPos = (cellHeight + gap) * r

                        if (!occupiedCells.contains(pos)) {
                            Box(
                                modifier = Modifier
                                    .offset(x = xPos, y = yPos)
                                    .size(cellWidth, cellHeight)
                            ) {
                                if (isEditMode) {
                                    EmptyGridSlot(position = pos) {
                                        selectedGridPosition = pos
                                        showWidgetDrawer = true
                                    }
                                } else {
                                    Spacer(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }

                // LAYER 2: Render Widgets
                profile.widgets.forEach { widget ->

                    val xPos = (cellWidth + gap) * widget.position.col
                    val yPos = (cellHeight + gap) * widget.position.row

                    val totalW = (cellWidth * widget.size.width) + (gap * (widget.size.width - 1))
                    val totalH = (cellHeight * widget.size.height) + (gap * (widget.size.height - 1))

                    Box(
                        modifier = Modifier
                            .offset(x = xPos, y = yPos)
                            .size(totalW, totalH)
                    ) {
                        when (widget.script.interactionType) {
                            "button_tap" -> WidgetButton(
                                widget = widget,
                                isEditMode = isEditMode,
                                onClick = {
                                    val command = widget.script.commands["on_tap"]
                                    if (command?.type == "local_action" && command.payload["action"] == "upload_file") {
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    } else {
                                        command?.let { viewModel.executeCommand(it) }
                                    }
                                },
                                onDelete = { widgetId -> viewModel.deleteWidget(widgetId) }
                            )
                            "drag_area" -> DragSurfaceWidget(
                                widget = widget,
                                isEditMode = isEditMode,
                                onDrag = { dx, dy -> viewModel.onMoveMouse(dx, dy) },
                                onDelete = { widgetId -> viewModel.deleteWidget(widgetId) }
                            )
                            "v_scroll" -> ScrollSurfaceWidget(
                                widget = widget,
                                isEditMode = isEditMode,
                                onScroll = { dy -> viewModel.onScrollMouse(dy) },
                                onDelete = { widgetId -> viewModel.deleteWidget(widgetId) }
                            )
                            "h_scroll" -> ScrollSurfaceWidget(
                                widget = widget,
                                isEditMode = isEditMode,
                                isHorizontal = true,
                                onHScroll = { state, dx -> viewModel.onHorizontalScrollGesture(state, dx) },
                                onDelete = { widgetId -> viewModel.deleteWidget(widgetId) }
                            )
                            "text_input" -> TextInputWidget(
                                widget = widget,
                                isEditMode = isEditMode,
                                onSend = { text -> viewModel.onSendCharacter(text) },
                                onDelete = { widgetId -> viewModel.deleteWidget(widgetId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteProfileDialog(profileName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Profile") },
        text = { Text("Are you sure you want to delete the '$profileName' profile? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetDrawer(widgetLibrary: WidgetLibrary, onWidgetSelected: (WidgetScript) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredScripts = remember(searchQuery, widgetLibrary) {
        if (searchQuery.isBlank()) {
            widgetLibrary.getAllScripts()
        } else {
            widgetLibrary.getAllScripts().filter {
                it.label.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Widgets") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, "Clear Search")
                    }
                }
            }
        )

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(filteredScripts, key = { it.label }) { script ->
                ListItem(
                    headlineContent = { Text(script.label) },
                    leadingContent = {
                        Icon(
                            getIconFromName(script.iconName),
                            contentDescription = script.label
                        )
                    },
                    modifier = Modifier.clickable { onWidgetSelected(script) }
                )
            }
        }
    }
}

@Composable
fun WidgetButton(
    widget: RemoteWidget,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onDelete: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val interactionModifier = if (isEditMode) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier.clickable(onClick = onClick)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .then(interactionModifier),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    getIconFromName(widget.script.iconName),
                    widget.script.label,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = widget.script.label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (isEditMode) {
            IconButton(
                onClick = { onDelete(widget.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        RoundedCornerShape(50)
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete Widget",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun DragSurfaceWidget(
    widget: RemoteWidget,
    isEditMode: Boolean,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDelete: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val interactionModifier = if (isEditMode) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier.pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(12.dp)
                )
                .then(interactionModifier),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    getIconFromName(widget.script.iconName),
                    widget.script.label,
                    modifier = Modifier.size(48.dp)
                )
                Text(widget.script.label, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (isEditMode) {
            IconButton(
                onClick = { onDelete(widget.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        RoundedCornerShape(50)
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete Widget",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ScrollSurfaceWidget(
    widget: RemoteWidget,
    isEditMode: Boolean,
    isHorizontal: Boolean = false,
    onScroll: (dy: Float) -> Unit = {},
    onHScroll: (state: String, dx: Float) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize()) {
        val interactionModifier = if (isEditMode) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isHorizontal) onHScroll("start", 0f)
                    },
                    onDragEnd = { if (isHorizontal) onHScroll("end", 0f) },
                    onDrag = { _, dragAmount ->
                        if (isHorizontal) {
                            onHScroll("drag", dragAmount.x)
                        } else {
                            onScroll(dragAmount.y)
                        }
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                    RoundedCornerShape(12.dp)
                )
                .then(interactionModifier),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    getIconFromName(widget.script.iconName),
                    widget.script.label,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    widget.script.label,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (isEditMode) {
            IconButton(
                onClick = { onDelete(widget.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        RoundedCornerShape(50)
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete Widget",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun TextInputWidget(
    widget: RemoteWidget,
    isEditMode: Boolean,
    onSend: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val interactionModifier = if (isEditMode) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier // No special interaction on the box itself
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .then(interactionModifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            var textState by remember { mutableStateOf(TextFieldValue("")) }
            val enabled = !isEditMode

            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = textState,
                onValueChange = { newValue ->
                    if (newValue.text.endsWith(" ")) {
                        onSend(newValue.text)
                        textState = TextFieldValue("")
                    } else {
                        textState = newValue
                    }
                },
                label = { Text(widget.script.label) },
                placeholder = { Text("Type and space to send") },
                singleLine = true,
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            )
        }

        if (isEditMode) {
            IconButton(
                onClick = { onDelete(widget.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        RoundedCornerShape(50)
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete Widget",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}


// START MODIFICATION: Update EmptyGridSlot to accept and display position
@Composable
fun EmptyGridSlot(position: GridPosition, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(100.dp)
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "(${position.row + 1},${position.col + 1})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
        )
        Icon(
            Icons.Default.Add,
            "Add new widget",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
// END MODIFICATION

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNewProfileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Profile") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Profile Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResizeWidgetDialog(onDismiss: () -> Unit, onConfirm: (GridSize) -> Unit) {
    var width by remember { mutableStateOf("3") }
    var height by remember { mutableStateOf("2") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Widget Size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Define how many grid cells the widget should occupy.")
                OutlinedTextField(
                    value = width,
                    onValueChange = { value -> width = value.filter { it.isDigit() }.take(1) },
                    label = { Text("Width (1-4)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { value -> height = value.filter { it.isDigit() }.take(2) },
                    label = { Text("Height") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val w = width.toIntOrNull()?.coerceIn(1, 4) ?: 1
                val h = height.toIntOrNull()?.coerceIn(1, 50) ?: 1
                onConfirm(GridSize(w, h))
            }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun getIconFromName(name: String): ImageVector {
    return when (name) {
        // Editing
        "ContentCopy" -> Icons.Default.ContentCopy
        "ContentPaste" -> Icons.Default.ContentPaste
        "SelectAll" -> Icons.Default.SelectAll
        "Search" -> Icons.Default.Search
        "Delete" -> Icons.Default.Delete
        // Navigation
        "ArrowUpward" -> Icons.Default.ArrowUpward
        "ArrowDownward" -> Icons.Default.ArrowDownward
        "ArrowBack" -> Icons.AutoMirrored.Filled.ArrowBack
        "ArrowForward" -> Icons.AutoMirrored.Filled.ArrowForward
        "KeyboardArrowUp" -> Icons.Default.KeyboardArrowUp
        "KeyboardArrowDown" -> Icons.Default.KeyboardArrowDown
        "LastPage" -> Icons.AutoMirrored.Filled.LastPage
        // System & Special Keys
        "KeyboardReturn" -> Icons.AutoMirrored.Filled.KeyboardReturn
        "Close" -> Icons.Default.Close
        "FullscreenExit" -> Icons.Default.FullscreenExit
        "Fullscreen" -> Icons.Default.Fullscreen
        "KeyboardCapslock" -> Icons.Default.KeyboardCapslock
        "Pin" -> Icons.Default.Pin // For Num Lock
        "SyncLock" -> Icons.Default.SyncLock // For Scroll Lock
        "Window" -> Icons.Default.Window
        "SpaceBar" -> Icons.Default.SpaceBar
        "Input" -> Icons.AutoMirrored.Filled.Input // For Insert
        "DesktopWindows" -> Icons.Default.DesktopWindows // For Win+D
        "CameraAlt" -> Icons.Default.CameraAlt // For Screenshot
        "FolderOpen" -> Icons.Default.FolderOpen
        "UploadFile" -> Icons.Default.UploadFile
        // Mouse Clicks & Gestures
        "Mouse" -> Icons.Default.Mouse
        "SwapVert" -> Icons.Default.SwapVert
        "SwapHoriz" -> Icons.Default.SwapHoriz
        // Volume
        "VolumeUp" -> Icons.AutoMirrored.Filled.VolumeUp
        "VolumeDown" -> Icons.AutoMirrored.Filled.VolumeDown
        "VolumeMute" -> Icons.AutoMirrored.Filled.VolumeMute
        // Brightness
        "BrightnessHigh" -> Icons.Default.BrightnessHigh
        "BrightnessLow" -> Icons.Default.BrightnessLow
        "TextFields" -> Icons.Default.TextFields
        else -> Icons.Default.Widgets
    }
}

class WidgetLibrary {
    private val scripts = listOf(
        // --- GESTURE WIDGETS ---
        WidgetScript("surface", "Touchpad", "Mouse", "drag_area", mapOf("on_drag" to Command("mouse_move", emptyMap()))),
        WidgetScript("surface", "Vertical Scroll", "SwapVert", "v_scroll", mapOf("on_drag" to Command("v_scroll", emptyMap()))),
        WidgetScript("surface", "Horizontal Scroll", "SwapHoriz", "h_scroll", mapOf("on_drag" to Command("h_scroll", emptyMap()))),
        // --- TEXT INPUT WIDGET ---
        WidgetScript("input", "Text Input", "TextFields", "text_input", mapOf("on_send" to Command("send_char", emptyMap()))),
        // --- Brightness ---
        WidgetScript("button", "Brightness Down", "BrightnessLow", "button_tap", mapOf("on_tap" to Command("brightness_control", mapOf("change" to -10)))),
        WidgetScript("button", "Brightness Up", "BrightnessHigh", "button_tap", mapOf("on_tap" to Command("brightness_control", mapOf("change" to 10)))),
        // --- Editing ---
        WidgetScript("button", "Caps Lock", "KeyboardCapslock", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "capslock")))),
        WidgetScript("button", "Copy", "ContentCopy", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("ctrl", "c"))))),
        WidgetScript("button", "Delete", "Delete", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "delete")))),
        WidgetScript("button", "Find", "Search", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("ctrl", "f"))))),
        WidgetScript("button", "Insert", "Input", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "insert")))),
        WidgetScript("button", "Paste", "ContentPaste", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("ctrl", "v"))))),
        WidgetScript("button", "Select All", "SelectAll", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("ctrl", "a"))))),
        // --- File Operations ---
        WidgetScript("button", "Open Downloads", "FolderOpen", "button_tap", mapOf("on_tap" to Command("open_folder", emptyMap()))),
        WidgetScript("button", "Upload File", "UploadFile", "button_tap", mapOf("on_tap" to Command("local_action", mapOf("action" to "upload_file")))),
        // --- Mouse Clicks ---
        WidgetScript("button", "Left Click", "Mouse", "button_tap", mapOf("on_tap" to Command("mouse_click", mapOf("button" to "left")))),
        WidgetScript("button", "Middle Click", "Mouse", "button_tap", mapOf("on_tap" to Command("mouse_click", mapOf("button" to "middle")))),
        WidgetScript("button", "Right Click", "Mouse", "button_tap", mapOf("on_tap" to Command("mouse_click", mapOf("button" to "right")))),
        // --- Navigation ---
        WidgetScript("button", "Down", "ArrowDownward", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "down")))),
        WidgetScript("button", "End", "LastPage", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "end")))),
        WidgetScript("button", "Left", "ArrowBack", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "left")))),
        WidgetScript("button", "Page Down", "KeyboardArrowDown", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "pagedown")))),
        WidgetScript("button", "Page Up", "KeyboardArrowUp", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "pageup")))),
        WidgetScript("button", "Right", "ArrowForward", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "right")))),
        WidgetScript("button", "Up", "ArrowUpward", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "up")))),
        // --- System & Special Keys ---
        WidgetScript("button", "Enter", "KeyboardReturn", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "enter")))),
        WidgetScript("button", "Esc", "Close", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "esc")))),
        WidgetScript("button", "Full Screen", "FullscreenExit", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "f11")))),
        WidgetScript("button", "Num Lock", "Pin", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "numlock")))),
        WidgetScript("button", "Screenshot", "CameraAlt", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "printscreen")))),
        WidgetScript("button", "Scroll Lock", "SyncLock", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "scrolllock")))),
        WidgetScript("button", "Show Desktop", "DesktopWindows", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("win", "d"))))),
        WidgetScript("button", "Spacebar", "SpaceBar", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "space")))),
        WidgetScript("button", "Windows Key", "Window", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "win")))),
        WidgetScript("button", "Shutdown Menu", "Power", "button_tap", mapOf("on_tap" to Command("hotkey", mapOf("keys" to listOf("alt", "f4"))))),
        // --- Volume ---
        WidgetScript("button", "Mute", "VolumeMute", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "volumemute")))),
        WidgetScript("button", "Volume Down", "VolumeDown", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "volumedown")))),
        WidgetScript("button", "Volume Up", "VolumeUp", "button_tap", mapOf("on_tap" to Command("key_press", mapOf("key" to "volumeup")))),

    ).sortedBy { it.label }

    fun getAllScripts(): List<WidgetScript> = scripts
    fun getScript(label: String): WidgetScript? = scripts.find { it.label == label }
}

@Composable
fun TouchpadScreen(
    viewModel: MainViewModel,
    filePickerLauncher: ManagedActivityResultLauncher<Array<String>, List<@JvmSuppressWildcards Uri>>
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var textState by remember { mutableStateOf(TextFieldValue("")) }
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = textState,
            onValueChange = { newValue ->
                if (newValue.text.endsWith(" ")) {
                    viewModel.onSendCharacter(newValue.text)
                    textState = TextFieldValue("")
                } else {
                    textState = newValue
                }
            },
            label = { Text("Type and press space to send") },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("left", "right", "middle").forEach { label ->
                Box(modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    .weight(1f)
                    .height(60.dp)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.onClickMouse(label)
                    })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier
                .weight(2.05f)
                .height(150.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        viewModel.onMoveMouse(
                            dragAmount.x,
                            dragAmount.y
                        )
                    }
                })
            Box(modifier = Modifier
                .weight(1f)
                .height(150.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { _, dragAmount -> viewModel.onScrollMouse(dragAmount.y) })
                })
        }

        Box(modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.onHorizontalScrollGesture("start", 0f)
                    },
                    onDragEnd = { viewModel.onHorizontalScrollGesture("end", 0f) },
                    onDrag = { _, dragAmount ->
                        viewModel.onHorizontalScrollGesture(
                            "drag",
                            dragAmount.x
                        )
                    })
            })

        Spacer(modifier = Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(icon = Icons.Default.SelectAll, desc = "Select All") { viewModel.onPressHotkey("ctrl+a") }
            ControlButton(icon = Icons.Default.ContentCopy, desc = "Copy") { viewModel.onPressHotkey("ctrl+c") }
            ControlButton(icon = Icons.Default.ContentPaste, desc = "Paste") { viewModel.onPressHotkey("ctrl+v") }
            ControlButton(icon = Icons.Default.Search, desc = "Find") { viewModel.onPressHotkey("ctrl+f") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(
                icon = Icons.Default.UploadFile,
                desc = "Select Files"
            ) { filePickerLauncher.launch(arrayOf("*/*")) }
            ControlButton(
                icon = Icons.Default.FolderOpen,
                desc = "Open Location"
            ) { viewModel.openDownloadsFolder() }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ControlButton(icon = Icons.Default.ArrowUpward, desc = "Up") { viewModel.onPressKey("up") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(icon = Icons.AutoMirrored.Filled.ArrowBack, desc = "Left") { viewModel.onPressKey("left") }
            ControlButton(icon = Icons.Default.ArrowDownward, desc = "Down") { viewModel.onPressKey("down") }
            ControlButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                desc = "Right"
            ) { viewModel.onPressKey("right") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(
                icon = Icons.Default.KeyboardArrowUp,
                desc = "Page Up"
            ) { viewModel.onPressKey("pageup") }
            ControlButton(
                icon = Icons.Default.KeyboardArrowDown,
                desc = "Page Down"
            ) { viewModel.onPressKey("pagedown") }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { viewModel.onPressKey("esc") }) { Text("ESC") }
            Button(onClick = { viewModel.onPressKey("enter") }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardReturn,
                    "Enter"
                )
            }
            Button(onClick = { viewModel.onPressKey("f11") }) { Text("F11") }
            Button(onClick = { viewModel.onPressKey("backspace") }) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    "Backspace"
                )
            }
        }
    }
}

@Composable
fun ControlButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(32.dp))
    }
}

@SuppressLint("DefaultLocale")
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("App Configuration", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Mouse Pointer Sensitivity: ${String.format("%.1f", viewModel.pointerSensitivity)}",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = viewModel.pointerSensitivity,
            onValueChange = { viewModel.updatePointerSensitivity(it) },
            valueRange = 0.1f..2.0f,
            steps = 18
        )
        Text(
            "Controls the speed of the mouse pointer.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Vertical Scroll Sensitivity: ${String.format("%.1f", viewModel.verticalScrollSensitivity)}",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = viewModel.verticalScrollSensitivity,
            onValueChange = { viewModel.updateVerticalScrollSensitivity(it) },
            valueRange = 1.0f..20.0f,
            steps = 18
        )
        Text(
            "Controls the speed of vertical scrolling.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Horizontal Scroll Sensitivity: ${
                String.format(
                    "%.1f",
                    viewModel.horizontalScrollSensitivity
                )
            }",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = viewModel.horizontalScrollSensitivity,
            onValueChange = { viewModel.updateHorizontalScrollSensitivity(it) },
            valueRange = 1.0f..20.0f,
            steps = 18
        )
        Text(
            "Controls the speed of horizontal scrolling.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val viewModel by viewModels<MainViewModel> { MainViewModelFactory(applicationContext) }
        setContent {
            MyApplication45Theme {
                val filePickerLauncher =
                    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                        if (uris.isNotEmpty()) {
                            viewModel.uploadFilesFromPicker(uris)
                        }
                    }
                MainApp(viewModel = viewModel, filePickerLauncher = filePickerLauncher)
            }
        }
    }
}

@Composable
fun UploadProgressDialog(status: String, progress: Float, onDismiss: () -> Unit) {
    val isUploading = status.contains("Sending", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "File Upload") },
        text = {
            Column {
                Text(status)
                if (isUploading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

object FileUploadManager {
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun uploadMultipleFiles(
        context: Context,
        fileUris: List<Uri>,
        onStatusUpdate: (String) -> Unit,
        onProgressUpdate: (Float) -> Unit
    ) {
        var successCount = 0
        fileUris.forEachIndexed { index, uri ->
            onStatusUpdate("Sending file ${index + 1} of ${fileUris.size}...")
            if (uploadSingleFile(context, uri, onProgressUpdate)) {
                successCount++
            }
        }
        onStatusUpdate("Sent $successCount/${fileUris.size} files successfully!")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("Recycle")
    private suspend fun uploadSingleFile(
        context: Context,
        fileUri: Uri,
        onProgressUpdate: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                var fileName = getFileName(context, fileUri)
                val mimeType = context.contentResolver.getType(fileUri)
                val extension = getExtensionFromMimeType(mimeType)
                if (extension.isNotEmpty() && !fileName.endsWith(extension, ignoreCase = true)) {
                    fileName += extension
                }
                val fileSize =
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize }
                        ?: -1L
                withContext(Dispatchers.Main) { onProgressUpdate(0f) }

                val requestBody = ProgressRequestBody(
                    "application/octet-stream".toMediaTypeOrNull(),
                    inputStream,
                    fileSize
                ) { progress ->
                    (context as? ComponentActivity)?.runOnUiThread { onProgressUpdate(progress) }
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, requestBody)
                    .build()

                val baseUrl = ConfigManager.getBaseUrl(context)
                val request = Request.Builder().url("${baseUrl}upload-file").post(multipartBody).build()
                OkHttpProvider.client.newCall(request).execute().use { it.isSuccessful }
            } == true
        } catch (e: Exception) {
            Log.e("FileUpload", "Error uploading file", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getFileName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(index)
                }
            }
        }
        return name ?: (uri.lastPathSegment ?: "shared_file")
    }

    private fun getExtensionFromMimeType(mimeType: String?): String {
        return when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "audio/mpeg" -> ".mp3"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> ""
        }
    }
}

class ProgressRequestBody(
    private val contentType: okhttp3.MediaType?,
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength(): Long = contentLength
    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var uploaded: Long = 0
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            sink.write(buffer, 0, read)
            uploaded += read
            if (contentLength > 0) {
                onProgress(uploaded.toFloat() / contentLength)
            }
        }
    }
}

@Suppress("DEPRECATION")
class ShareReceiverActivity : ComponentActivity() {
    private val uploadStatus = mutableStateOf("Initializing upload...")
    private val uploadProgress = mutableFloatStateOf(0f)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val fileUris: List<Uri>? = when (intent?.action) {
                Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                else -> null
            }

            if (!fileUris.isNullOrEmpty()) {
                FileUploadManager.uploadMultipleFiles(
                    this@ShareReceiverActivity,
                    fileUris,
                    onStatusUpdate = { uploadStatus.value = it },
                    onProgressUpdate = { uploadProgress.floatValue = it }
                )
            } else {
                uploadStatus.value = "Unsupported action or no files found."
            }
        }
        setContent {
            MyApplication45Theme {
                SharedFileScreen(
                    status = uploadStatus.value,
                    progress = uploadProgress.floatValue,
                    onClose = { finish() },
                    onOpenFolder = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val baseUrl = ConfigManager.getBaseUrl(this@ShareReceiverActivity)
                                val request = Request.Builder()
                                    .url("${baseUrl}open-folder")
                                    .post("".toRequestBody(null))
                                    .build()
                                OkHttpProvider.client.newCall(request).execute()
                            } catch (e: Exception) {
                                Log.e("API", "Error: ${e.message}")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SharedFileScreen(
    modifier: Modifier = Modifier,
    status: String,
    progress: Float,
    onClose: () -> Unit,
    onOpenFolder: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = status)
            if (progress in 0.0..1.0 && status.contains("Sending", ignoreCase = true)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            Button(onClick = onClose) { Text("Close") }
            Button(onClick = onOpenFolder) { Text("Open File Location") }
        }
    }
}

@Composable
fun DeviceLocationScreen(viewModel: MainViewModel) {
    val isScanning = viewModel.isScanning
    val discoveredDevices = viewModel.discoveredDevices
    val currentIp = viewModel.ipAddress
    var manualIpState by remember(currentIp) { mutableStateOf(TextFieldValue(currentIp)) }

    LaunchedEffect(Unit) {
        if (discoveredDevices.isEmpty() && !viewModel.isScanning) {
            viewModel.scanForDevices()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Available Devices", style = MaterialTheme.typography.headlineSmall)
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = { viewModel.scanForDevices() }) {
                    Icon(Icons.Default.Refresh, "Rescan Network")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (discoveredDevices.isEmpty() && !isScanning) {
            Text(
                "No devices found. Ensure you are on the same Wi-Fi as your PC and the server is running. Press refresh to try again.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(discoveredDevices, key = { it.ip }) { device ->
                    DeviceListItem(
                        device = device,
                        isConnected = device.ip == currentIp,
                        onConnect = { viewModel.updateIpAddress(device.ip) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Manual Connection", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = manualIpState,
            onValueChange = { manualIpState = it },
            label = { Text("Enter IP Address") },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.updateIpAddress(manualIpState.text) },
            enabled = manualIpState.text.isNotBlank() && manualIpState.text != currentIp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect to Manual IP")
        }
    }
}

@Composable
fun DeviceListItem(device: MainViewModel.DiscoveredDevice, isConnected: Boolean, onConnect: () -> Unit) {
    val cardColors = CardDefaults.cardColors(
        containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = cardColors,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CheckCircle, "Connectable",
                tint = if (isConnected) MaterialTheme.colorScheme.primary else Color.Green,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.hostname ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                Text(text = device.ip, style = MaterialTheme.typography.bodyMedium)
            }
            if (isConnected) {
                Text("Connected", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            } else {
                Button(onClick = onConnect) { Text("Connect") }
            }
        }
    }
}

@Composable
fun MediaScreen(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume Down") { viewModel.onPressMediaKey("volumedown") }
            MediaButton(Icons.AutoMirrored.Filled.VolumeMute, "Mute") { viewModel.onPressMediaKey("volumemute") }
            MediaButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume Up") { viewModel.onPressMediaKey("volumeup") }
        }
        Spacer(modifier = Modifier.height(48.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaButton(Icons.Default.SkipPrevious, "Previous Track") { viewModel.onPressMediaKey("prevtrack") }
            MediaButton(Icons.Default.PlayArrow, "Play/Pause") { viewModel.onPressMediaKey("playpause") }
            MediaButton(Icons.Default.SkipNext, "Next Track") { viewModel.onPressMediaKey("nexttrack") }
        }
    }
}

@Composable
fun MediaButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun PowerScreen(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Interactive Power Control",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "1. Press Alt+F4 to open the system menu.\n2. Use arrows to navigate.\n3. Press Enter to select.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        Button(
            onClick = { viewModel.onPressHotkey("alt+f4") },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(Icons.Default.PowerSettingsNew, "System Menu", modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("System Menu (Alt+F4)")
        }
        Spacer(modifier = Modifier.height(48.dp))
        ArrowButton(icon = Icons.Default.ArrowUpward, description = "Up Arrow") { viewModel.onPressKey("up") }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.onPressKey("enter") },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter")
        }
        Spacer(modifier = Modifier.height(16.dp))
        ArrowButton(
            icon = Icons.Default.ArrowDownward,
            description = "Down Arrow"
        ) { viewModel.onPressKey("down") }
    }
}

@Composable
fun ArrowButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(80.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(48.dp))
    }
}
