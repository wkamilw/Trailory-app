package com.example.myapplication

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.PhotoEntity
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.clickable
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Model danych UI
data class PhotoData(
    val id: Int, // NOWE
    val uri: Uri,
    val location: GeoPoint?,
    val timestamp: Long = System.currentTimeMillis(),
    val name: String = "Photo",
    val size: Long = 0
)

// DataStore do ustawień
private val Context.dataStore by preferencesDataStore("settings")

object SettingsKeys {
    val DARK_MODE = booleanPreferencesKey("dark_mode")
    val MAP_DARK = booleanPreferencesKey("map_dark")
}

class SettingsRepository(private val context: Context) {
    val darkMode = context.dataStore.data.map { it[SettingsKeys.DARK_MODE] ?: false }
    val mapDark = context.dataStore.data.map { it[SettingsKeys.MAP_DARK] ?: false }

    suspend fun setDarkMode(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DARK_MODE] = value }
    }
    suspend fun setMapDark(value: Boolean) {
        context.dataStore.edit { it[SettingsKeys.MAP_DARK] = value }
    }
}

enum class SortOption(val label: String) {
    DATE_DESC("Data (Najnowsze)"),
    DATE_ASC("Data (Najstarsze)"),
    NAME("Nazwa"),
    SIZE_DESC("Rozmiar (Duże)"),
    SIZE_ASC("Rozmiar (Małe)")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(this)
        val settingsRepo = SettingsRepository(this)

        setContent {
            HideSystemBars()
            val isDark by settingsRepo.darkMode.collectAsState(initial = false)
            MyApplicationTheme(darkTheme = isDark) {
                TrailoryNavigation(database, settingsRepo)
            }
        }
    }
}

enum class AppScreen { MAP, GALLERY, SETTINGS }

@Composable
fun TrailoryNavigation(
    database: AppDatabase,
    settingsRepo: SettingsRepository
) {
    var currentScreen by remember { mutableStateOf(AppScreen.MAP) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isMapDark by settingsRepo.mapDark.collectAsState(initial = false)
    val photosEntityList by database.photoDao().getAllPhotos().collectAsState(initial = emptyList())

    // 1. ODCZYT Z BAZY (Teraz pobieramy też ID!)
    val photos = photosEntityList.map { entity ->
        PhotoData(
            id = entity.id, // Przekazujemy ID z bazy
            uri = Uri.parse(entity.uriString),
            location = if (entity.latitude != null && entity.longitude != null) {
                GeoPoint(entity.latitude, entity.longitude)
            } else null,
            name = entity.title,
            timestamp = entity.timestamp,
            size = entity.size
        )
    }

    var mapCenter by remember { mutableStateOf(GeoPoint(52.23, 21.01)) }
    var mapZoom by remember { mutableStateOf(15.0) }

    when (currentScreen) {
        AppScreen.MAP -> MapScreenUI(
            photos = photos,
            onNavigateToGallery = { currentScreen = AppScreen.GALLERY },
            onNavigateToSettings = { currentScreen = AppScreen.SETTINGS },
            onPhotoCaptured = { photoData ->
                scope.launch {
                    var fileSize: Long = 0
                    try {
                        context.contentResolver.openFileDescriptor(photoData.uri, "r")?.use {
                            fileSize = it.statSize
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    database.photoDao().insertPhoto(
                        PhotoEntity(
                            uriString = photoData.uri.toString(),
                            latitude = photoData.location?.latitude,
                            longitude = photoData.location?.longitude,
                            title = photoData.name,
                            timestamp = photoData.timestamp,
                            size = fileSize
                        )
                    )
                }
            },
            initialCenter = mapCenter,
            initialZoom = mapZoom,
            onMapPositionChange = { newCenter, newZoom ->
                mapCenter = newCenter
                mapZoom = newZoom
            },
            isMapDark = isMapDark
        )
        // 2. PRZEKAZANIE FUNKCJI USUWANIA DO GALERII
        AppScreen.GALLERY -> GalleryScreenUI(
            photos = photos,
            onBack = { currentScreen = AppScreen.MAP },
            onDeletePhoto = { photoToDelete ->
                scope.launch {
                    database.photoDao().deleteById(photoToDelete.id)
                    Toast.makeText(context, "Usunięto z aplikacji", Toast.LENGTH_SHORT).show()
                }
            }
        )
        AppScreen.SETTINGS -> SettingsScreenUI(
            settingsRepo = settingsRepo,
            onBack = { currentScreen = AppScreen.MAP }
        )
    }
}

@Composable
fun MapScreenUI(
    photos: List<PhotoData>,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPhotoCaptured: (PhotoData) -> Unit,
    initialCenter: GeoPoint,
    initialZoom: Double,
    onMapPositionChange: (GeoPoint, Double) -> Unit,
    isMapDark: Boolean
) {
    val context = LocalContext.current
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var tempLocationCapture by remember { mutableStateOf<GeoPoint?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    var selectedPhoto by remember { mutableStateOf<PhotoData?>(null) }
    var isGpsSignal by remember { mutableStateOf(false) }

    // Zmienne do okna zapisywania
    var showNameDialog by remember { mutableStateOf(false) }
    var photoNameInput by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (isGranted) {
            myLocationOverlay?.enableMyLocation()
            myLocationOverlay?.enableFollowLocation()
            mapView?.invalidate()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            // Po zrobieniu zdjęcia otwieramy dialog z podglądem i nazwą
            photoNameInput = ""
            showNameDialog = true
        }
    }
    // Definicja ciemnego stylu mapy (CartoDB Dark Matter)
    val darkTileSource = remember {
        object : org.osmdroid.tileprovider.tilesource.XYTileSource(
            "CartoDark",
            0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/"
            )
        ) {
            override fun getCopyrightNotice() = "© OpenStreetMap contributors, © CARTO"
        }
    }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // --- MAPA ---
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(
                            if (isMapDark) darkTileSource else TileSourceFactory.MAPNIK
                        )
                        setMultiTouchControls(true)
                        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(initialZoom)
                        controller.setCenter(initialCenter)
                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?) = run {
                                onMapPositionChange(GeoPoint(mapCenter.latitude, mapCenter.longitude), zoomLevelDouble)
                                true
                            }
                            override fun onZoom(event: ZoomEvent?) = run {
                                onMapPositionChange(GeoPoint(mapCenter.latitude, mapCenter.longitude), zoomLevelDouble)
                                true
                            }
                        })
                        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                        overlay.setPersonIcon(createBlueDot())
                        overlay.setDirectionIcon(createBlueArrow())
                        overlay.enableMyLocation()
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            overlay.enableFollowLocation()
                        }
                        overlays.add(overlay)
                        myLocationOverlay = overlay
                        mapView = this
                    }
                },
                update = { map ->
                    map.overlays.removeAll { it is Marker || it is RadiusMarkerClusterer }

                    val clusterer = RadiusMarkerClusterer(context)
                    val clusterIcon = createBlueDot()
                    clusterer.setIcon(clusterIcon)
                    clusterer.textPaint.textSize = 40f
                    clusterer.textPaint.color = android.graphics.Color.WHITE

                    photos.forEach { photo ->
                        if (photo.location != null) {
                            val marker = Marker(map)
                            marker.position = photo.location
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.title = photo.name
                            marker.setOnMarkerClickListener { _, _ ->
                                selectedPhoto = photo
                                true
                            }
                            clusterer.add(marker)
                        }
                    }
                    map.overlays.add(clusterer)
                    map.invalidate()
                }
            )

            // --- NOWE OKNO ZAPISYWANIA (PODGLĄD + NAZWA) ---
            if (showNameDialog && currentPhotoUri != null) {
                Dialog(
                    onDismissRequest = {
                        // Puste - blokujemy zamykanie klawiszem wstecz, jeśli chcesz
                        // Ale tutaj ważniejsze jest properties poniżej
                    },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false // TO BLOKUJE KLIKANIE W TŁO
                    )
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight() // Dopasuj wysokość do zawartości
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Zapisz zdjęcie",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // PODGLĄD ZDJĘCIA
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp) // Wysokość podglądu
                            ) {
                                AsyncImage(
                                    model = currentPhotoUri,
                                    contentDescription = "Podgląd",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // POLE TEKSTOWE
                            val maxChar = 30
                            OutlinedTextField(
                                value = photoNameInput,
                                onValueChange = {
                                    // Pozwalamy wpisać tylko jeśli nie przekracza limitu
                                    if (it.length <= maxChar) {
                                        photoNameInput = it
                                    }
                                },
                                label = { Text("Nazwa miejsca") },
                                placeholder = { Text("np. Stary dąb") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                // Licznik znaków pod polem: "12 / 30"
                                supportingText = {
                                    Text(
                                        text = "${photoNameInput.length} / $maxChar",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                        color = if (photoNameInput.length == maxChar) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // PRZYCISKI
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        // Anuluj - nie zapisujemy do bazy
                                        showNameDialog = false
                                    }
                                ) {
                                    Text("Anuluj")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        // Zapisz
                                        val finalName = if (photoNameInput.isBlank()) "Bez nazwy" else photoNameInput
                                        val newData = PhotoData(
                                            id = 0, // Dajemy 0, bo to nowe zdjęcie
                                            uri = currentPhotoUri!!,
                                            location = tempLocationCapture,
                                            name = finalName,
                                            timestamp = System.currentTimeMillis()
                                        )
                                        onPhotoCaptured(newData)
                                        Toast.makeText(context, "Zapisano: $finalName", Toast.LENGTH_SHORT).show()
                                        showNameDialog = false
                                    }
                                ) {
                                    Text("Zapisz")
                                }
                            }
                        }
                    }
                }
            }
            // -----------------------------------------------------

            LaunchedEffect(myLocationOverlay) {
                while (true) {
                    val location = myLocationOverlay?.myLocation
                    isGpsSignal = (location != null)
                    kotlinx.coroutines.delay(1000)
                }
            }

            // Przyciski ZOOM
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomIn() },
                    modifier = Modifier.size(50.dp),
                    // ZMIANA KOLORÓW NA MOTYW
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) { Icon(Icons.Default.Add, "Przybliż") }

                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomOut() },
                    modifier = Modifier.size(50.dp),
                    // ZMIANA KOLORÓW NA MOTYW
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) { Icon(Icons.Default.Remove, "Oddal") }
            }

            // Podgląd zdjęcia z mapy (kliknięcie w marker)
            if (selectedPhoto != null) {
                Dialog(onDismissRequest = { selectedPhoto = null }) {
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                                AsyncImage(
                                    model = selectedPhoto!!.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = { selectedPhoto = null },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        // Tło półprzezroczyste, pasuje do każdego trybu
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, "Zamknij", tint = Color.White)
                                }
                            }
                            Text(
                                text = selectedPhoto!!.name,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            LaunchedEffect(myLocationOverlay) {
                if (myLocationOverlay != null && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    // ZMIANA: Tło dostosowane do motywu
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
            ) {
                // Ikona dostosowana do motywu
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurface)
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallRoundButton(Icons.Default.Collections, onNavigateToGallery)

                FloatingActionButton(
                    onClick = {
                        if (isGpsSignal) {
                            tempLocationCapture = myLocationOverlay?.myLocation
                            val uri = createImageUri(context)
                            if (uri != null) {
                                currentPhotoUri = uri
                                cameraLauncher.launch(uri)
                            }
                        } else {
                            Toast.makeText(context, "Czekam na sygnał GPS...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = if (isGpsSignal) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.size(80.dp)
                ) { Icon(Icons.Default.CameraAlt, null, Modifier.size(40.dp)) }

                SmallRoundButton(Icons.Default.MyLocation, {
                    val overlay = myLocationOverlay
                    if (overlay != null && overlay.isMyLocationEnabled && overlay.myLocation != null) {
                        mapView?.controller?.animateTo(overlay.myLocation)
                        mapView?.controller?.setZoom(18.0)
                        onMapPositionChange(overlay.myLocation, 18.0)
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                })
            }
        }
    }
}

// --- Galeria ---
@Composable
fun PhotoItem(
    photo: PhotoData,
    onDeleteClick: () -> Unit // Nowy parametr: co robić jak klikniemy kosz
) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth().height(100.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                modifier = Modifier.width(120.dp).fillMaxHeight(),
                contentScale = ContentScale.Crop
            )

            // Środek - Teksty
            Column(
                modifier = Modifier
                    .weight(1f) // Zajmuje dostępne miejsce
                    .padding(12.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(photo.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                    Text(df.format(Date(photo.timestamp)), fontSize = 11.sp, color = Color.Gray)
                }
                Row {
                    Text("${photo.size / 1024} KB", fontSize = 10.sp, color = Color.LightGray)
                }
            }

            // Prawa strona - Przycisk Kosza
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Usuń",
                        tint = MaterialTheme.colorScheme.error // Czerwony kolor
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreenUI(
    photos: List<PhotoData>,
    onBack: () -> Unit,
    onDeletePhoto: (PhotoData) -> Unit // Funkcja usuwająca otrzymana z MainActivity
) {
    BackHandler { onBack() }

    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Zmienna do przechowywania zdjęcia, które chcemy usunąć (do dialogu)
    var photoToDelete by remember { mutableStateOf<PhotoData?>(null) }

    val collator = remember { java.text.Collator.getInstance(java.util.Locale("pl", "PL")) }

    val filteredPhotos = remember(photos, searchQuery, sortOption) {
        photos
            .filter { photo ->
                photo.name.contains(searchQuery, ignoreCase = true) ||
                        photo.location?.let {
                            "${it.latitude},${it.longitude}".contains(searchQuery)
                        } == true
            }
            .sortedWith { a, b ->
                when (sortOption) {
                    SortOption.DATE_DESC -> b.timestamp.compareTo(a.timestamp)
                    SortOption.DATE_ASC -> a.timestamp.compareTo(b.timestamp)
                    SortOption.NAME -> collator.compare(a.name, b.name)
                    SortOption.SIZE_DESC -> b.size.compareTo(a.size)
                    SortOption.SIZE_ASC -> a.size.compareTo(b.size)
                }
            }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { Text("Galeria") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, null) }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortOption.values().forEach {
                                    DropdownMenuItem(text = { Text(it.label) }, onClick = { sortOption = it; showSortMenu = false })
                                }
                            }
                        }
                    }
                )
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Szukaj...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    singleLine = true
                )
            }
        }
    ) { padding ->
        // OKNO POTWIERDZENIA USUWANIA
        if (photoToDelete != null) {
            AlertDialog(
                onDismissRequest = { photoToDelete = null },
                title = { Text("Usuń zdjęcie") },
                text = { Text("Czy na pewno chcesz usunąć zdjęcie \"${photoToDelete?.name}\" z aplikacji?") },
                confirmButton = {
                    Button(
                        onClick = {
                            photoToDelete?.let { onDeletePhoto(it) } // Wywołujemy usuwanie
                            photoToDelete = null // Zamykamy okno
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Usuń")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { photoToDelete = null }) {
                        Text("Anuluj")
                    }
                }
            )
        }

        if (filteredPhotos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Brak wyników", color = Color.Gray)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredPhotos) { photo ->
                    PhotoItem(
                        photo = photo,
                        onDeleteClick = {
                            // Kliknięcie kosza nie usuwa od razu, tylko otwiera dialog
                            photoToDelete = photo
                        }
                    )
                }
            }
        }
    }
}

// --- Settings UI ---
@OptIn(ExperimentalMaterial3Api::class) // Potrzebne do TopAppBar
@Composable
fun SettingsScreenUI(
    settingsRepo: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Pobieramy stany ustawień
    val darkMode by settingsRepo.darkMode.collectAsState(false)
    val mapDark by settingsRepo.mapDark.collectAsState(false)

    BackHandler { onBack() }

    // Używamy Scaffold - on automatycznie ustawia tło (background)
    // i kolor tekstu (onBackground) zgodnie z wybranym motywem (Ciemny/Jasny)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Powrót")
                    }
                }
            )
        }
    ) { padding ->
        // Zawartość ustawień
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding) // Uwzględniamy pasek górny
                .padding(16.dp)
        ) {
            SettingRow(
                title = "Tryb ciemny aplikacji",
                checked = darkMode
            ) {
                scope.launch {
                    settingsRepo.setDarkMode(it)
                }
            }

            // Opcjonalnie: Linia oddzielająca
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingRow(
                title = "Tryb ciemny mapy",
                checked = mapDark
            ) {
                scope.launch {
                    settingsRepo.setMapDark(it)
                }
            }

            // Tutaj możesz dodawać kolejne opcje w przyszłości
        }
    }
}

@Composable
fun SettingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// --- Funkcje pomocnicze (Helpers) ---
fun createBlueDot(): android.graphics.Bitmap {
    val size = 60
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = android.graphics.Color.BLUE
    canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 5, paint)
    return bitmap
}

fun createBlueArrow(): android.graphics.Bitmap {
    val width = 100
    val height = 100
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLUE
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    val path = android.graphics.Path()
    path.moveTo(width / 2f, 0f)
    path.lineTo(width.toFloat(), height.toFloat())
    path.lineTo(width / 2f, height.toFloat() - 20)
    path.lineTo(0f, height.toFloat())
    path.close()
    canvas.drawPath(path, paint)
    return bitmap
}

@Composable
fun SmallRoundButton(icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        // ZMIANA: Używamy kolorów z motywu, a nie na sztywno White/Black
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface, // Biały lub Ciemny
            contentColor = MaterialTheme.colorScheme.onSurface // Czarny lub Biały
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        modifier = Modifier.size(60.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(30.dp))
    }
}

fun createImageUri(context: Context): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Trailory_${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Trailory-App")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
}

fun createClusterBitmap(count: Int): android.graphics.Bitmap {
    val size = 100
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    paint.color = android.graphics.Color.parseColor("#FF9800")
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 50f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val textHeight = paint.descent() - paint.ascent()
    val textOffset = (textHeight / 2) - paint.descent()
    canvas.drawText(count.toString(), size / 2f, (size / 2f) + textOffset, paint)
    return bitmap
}
@Composable
fun HideSystemBars() {
    val context = LocalContext.current
    val window = (context as? android.app.Activity)?.window

    if (window != null) {
        // Uruchamiamy efekt uboczny (Side Effect), który wykona się po załadowaniu widoku
        LaunchedEffect(Unit) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

            // Ustawiamy zachowanie: paski pojawią się na chwilę przy przesunięciu krawędzi (swipe)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // Ukrywamy paski systemowe (Górny i Dolny)
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}