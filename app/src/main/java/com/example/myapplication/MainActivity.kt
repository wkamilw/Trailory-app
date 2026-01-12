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

// Nasz model UI (taki sam jak wcześniej, ale tworzony z bazy)
data class PhotoData(
    val uri: Uri,
    val location: GeoPoint?,
    val timestamp: Long = System.currentTimeMillis(),
    val name: String = "Photo",
    val size: Long = 0
)

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

        // Inicjalizacja bazy danych
        val database = AppDatabase.getDatabase(this)

        setContent {
            MyApplicationTheme {
                TrailoryNavigation(database)
            }
        }
    }
}

enum class AppScreen { MAP, GALLERY, SETTINGS }

@Composable
fun TrailoryNavigation(database: AppDatabase) {
    var currentScreen by remember { mutableStateOf(AppScreen.MAP) }
    val scope = rememberCoroutineScope() // Potrzebne do zapisu w tle

    // --- KLUCZOWA ZMIANA: CZYTANIE Z BAZY ---
    // Pobieramy listę 'Entity' z bazy i zamieniamy ją na żywo na naszą listę 'PhotoData'
    val photosEntityList by database.photoDao().getAllPhotos().collectAsState(initial = emptyList())

    // Konwersja danych z bazy na format używany w UI
    val photos = photosEntityList.map { entity ->
        val loc = if (entity.latitude != null && entity.longitude != null) {
            GeoPoint(entity.latitude, entity.longitude)
        } else null
        PhotoData(Uri.parse(entity.uriString), loc)
    }

    var mapCenter by remember { mutableStateOf(GeoPoint(52.23, 21.01)) }
    var mapZoom by remember { mutableStateOf(15.0) }

    when (currentScreen) {
        AppScreen.MAP -> MapScreenUI(
            photos = photos, // Przekazujemy listę z bazy!
            onNavigateToGallery = { currentScreen = AppScreen.GALLERY },
            onNavigateToSettings = { currentScreen = AppScreen.SETTINGS },
            onPhotoCaptured = { photoData ->
                // ZAPIS DO BAZY (w tle)
                scope.launch {
                    val entity = PhotoEntity(
                        uriString = photoData.uri.toString(),
                        latitude = photoData.location?.latitude,
                        longitude = photoData.location?.longitude
                    )
                    database.photoDao().insertPhoto(entity)
                }
            },
            initialCenter = mapCenter,
            initialZoom = mapZoom,
            onMapPositionChange = { newCenter, newZoom ->
                mapCenter = newCenter
                mapZoom = newZoom
            }
        )
        AppScreen.GALLERY -> GalleryScreenUI(
            photos = photos, // Lista z bazy
            onBack = { currentScreen = AppScreen.MAP }
        )
        AppScreen.SETTINGS -> SettingsScreenUI(
            onBack = { currentScreen = AppScreen.MAP }
        )
    }
}

// --- RESZTA UI (MapScreen, GalleryScreen itp.) POZOSTAJE TAKA SAMA JAK WCZEŚNIEJ ---
// (Wklej tutaj całą resztę kodu z poprzedniego rozwiązania, zaczynając od fun MapScreenUI...)
// DLA UŁATWIENIA WKLEJAM CAŁOŚĆ PONIŻEJ:

@Composable
fun MapScreenUI(
    photos: List<PhotoData>,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPhotoCaptured: (PhotoData) -> Unit,
    initialCenter: GeoPoint,
    initialZoom: Double,
    onMapPositionChange: (GeoPoint, Double) -> Unit
) {
    val context = LocalContext.current
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var tempLocationCapture by remember { mutableStateOf<GeoPoint?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var selectedPhoto by remember { mutableStateOf<PhotoData?>(null) }
    var isGpsSignal by remember { mutableStateOf(false) }

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
            Toast.makeText(context, "Zapisano w bazie!", Toast.LENGTH_SHORT).show()
            val newData = PhotoData(currentPhotoUri!!, tempLocationCapture)
            onPhotoCaptured(newData)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(initialZoom)
                        controller.setCenter(initialCenter)
                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                onMapPositionChange(GeoPoint(mapCenter.latitude, mapCenter.longitude), zoomLevelDouble)
                                return true
                            }
                            override fun onZoom(event: ZoomEvent?): Boolean {
                                onMapPositionChange(GeoPoint(mapCenter.latitude, mapCenter.longitude), zoomLevelDouble)
                                return true
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
                    // 1. Usuwamy stare markery i stare klastry
                    // Ale musimy uważać, żeby NIE usunąć nakładki lokalizacji (MyLocationNewOverlay)
                    map.overlays.removeAll { it is Marker || it is RadiusMarkerClusterer }

                    // 2. Tworzymy KLASTER (Grupowacz)
                    val clusterer = RadiusMarkerClusterer(context)

                    // Ustawiamy ikonę klastra (domyślna jest brzydka, użyjemy własnej logiki lub domyślnej z biblioteki)
                    // Biblioteka wymaga ustawienia ikony bazowej, na której wypisuje liczbę.
                    // Użyjemy prostej bitmapy.
                    val clusterIcon = createBlueDot() // Możemy użyć naszej kropki jako tła
                    clusterer.setIcon(clusterIcon)

                    // Konfiguracja wyglądu tekstu na klastrze
                    clusterer.textPaint.textSize = 40f
                    clusterer.textPaint.color = android.graphics.Color.WHITE

                    // 3. Tworzymy markery dla każdego zdjęcia i dodajemy DO KLASTRA (a nie do mapy!)
                    photos.forEach { photo ->
                        if (photo.location != null) {
                            val marker = Marker(map)
                            marker.position = photo.location
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.title = "Kliknij, by zobaczyć zdjęcie"

                            // Tutaj możesz też zmienić ikonę samej pinezki na ładniejszą, np. fioletową
                            // marker.icon = ...

                            marker.setOnMarkerClickListener { _, _ ->
                                selectedPhoto = photo
                                true
                            }

                            // WAŻNE: Dodajemy do klastra!
                            clusterer.add(marker)
                        }
                    }

                    // 4. Na koniec dodajemy klaster do mapy
                    map.overlays.add(clusterer)

                    // Odświeżamy mapę
                    map.invalidate()
                }
            )
            LaunchedEffect(myLocationOverlay) {
                while (true) {
                    val location = myLocationOverlay?.myLocation
                    isGpsSignal = (location != null) // Jeśli location nie jest null, to mamy sygnał!
                    kotlinx.coroutines.delay(1000) // Czekamy 1 sekundę przed kolejnym sprawdzeniem
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd) // Ustawia po prawej na środku
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Plus (+)
                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomIn() },
                    modifier = Modifier.size(50.dp),
                    containerColor = Color.White,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Przybliż")
                }

                // Minus (-)
                FloatingActionButton(
                    onClick = { mapView?.controller?.zoomOut() },
                    modifier = Modifier.size(50.dp),
                    containerColor = Color.White,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Oddal")
                }
            }
            if (selectedPhoto != null) {
                Dialog(onDismissRequest = { selectedPhoto = null }) {
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(400.dp)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = selectedPhoto!!.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { selectedPhoto = null },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) { Icon(Icons.Default.Close, "Zamknij", tint = Color.White) }
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
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.White.copy(alpha = 0.8f), CircleShape)
            ) { Icon(Icons.Default.Settings, null) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Przycisk Galerii (bez zmian)
                SmallRoundButton(Icons.Default.Collections, onNavigateToGallery)

                // --- PRZYCISK APARATU (ZMIENIONY) ---
                FloatingActionButton(
                    onClick = {
                        if (isGpsSignal) {
                            // JEST SYGNAŁ: Robimy zdjęcie
                            tempLocationCapture = myLocationOverlay?.myLocation
                            val uri = createImageUri(context)
                            if (uri != null) {
                                currentPhotoUri = uri
                                cameraLauncher.launch(uri)
                            }
                        } else {
                            // BRAK SYGNAŁU: Wyświetlamy komunikat
                            Toast.makeText(context, "Czekam na sygnał GPS...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    // Zmiana koloru: Niebieski (gdy jest GPS) / Szary (gdy brak)
                    containerColor = if (isGpsSignal) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(40.dp))
                }

                // Przycisk Lokalizacji (bez zmian)
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

@Composable
fun PhotoItem(photo: PhotoData) {
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
            Column(
                modifier = Modifier.padding(12.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(photo.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                    Text(df.format(Date(photo.timestamp)), fontSize = 11.sp, color = Color.Gray)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${photo.size / 1024} KB", fontSize = 10.sp, color = Color.LightGray)
                    photo.location?.let {
                        Text("%.4f, %.4f".format(it.latitude, it.longitude), fontSize = 10.sp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreenUI(photos: List<PhotoData>, onBack: () -> Unit) {
    BackHandler { onBack() }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Filtering and sorting logic
    val filteredPhotos = remember(photos, searchQuery, sortOption) {
        photos.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.location?.let { loc -> "${loc.latitude}, ${loc.longitude}".contains(searchQuery) } ?: false)
        }.sortedWith { a, b ->
            when (sortOption) {
                SortOption.DATE_DESC -> b.timestamp.compareTo(a.timestamp)
                SortOption.DATE_ASC -> a.timestamp.compareTo(b.timestamp)
                SortOption.NAME -> a.name.compareTo(b.name)
                SortOption.SIZE_DESC -> b.size.compareTo(a.size)
                SortOption.SIZE_ASC -> a.size.compareTo(b.size)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Galeria") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Wstecz") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, "Sortuj")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sortOption = option
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    placeholder = { Text("Szukaj zdjęć...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent
                )
                )
            }
        }
    ) { padding ->
        if (filteredPhotos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Brak zdjęć do wyświetlenia.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredPhotos) { photo ->
                    PhotoItem(photo)
                }
            }
        }
    }
}


@Composable
fun SettingsScreenUI(onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        Text("Ustawienia", style = MaterialTheme.typography.headlineSmall)
    }
}

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
        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        modifier = Modifier.size(60.dp)
    ) { Icon(icon, null, tint = Color.Black, modifier = Modifier.size(30.dp)) }
}

fun createImageUri(context: Context): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Trailory_${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Trailory-App")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
}
// Ikona klastra (Kółko, które pokazuje ile jest zdjęć w jednym miejscu)
fun createClusterBitmap(count: Int): android.graphics.Bitmap {
    val size = 100
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }

    // Rysujemy pomarańczowe kółko
    paint.color = android.graphics.Color.parseColor("#FF9800") // Pomarańczowy
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    // Rysujemy białą liczbę w środku
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 50f
    paint.textAlign = android.graphics.Paint.Align.CENTER

    // Centrowanie tekstu w pionie
    val textHeight = paint.descent() - paint.ascent()
    val textOffset = (textHeight / 2) - paint.descent()

    canvas.drawText(count.toString(), size / 2f, (size / 2f) + textOffset, paint)

    return bitmap
}