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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
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

// Nasz model UI (taki sam jak wcześniej, ale tworzony z bazy)
data class PhotoData(
    val uri: Uri,
    val location: GeoPoint?
)

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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallRoundButton(Icons.Default.Collections, onNavigateToGallery)
                FloatingActionButton(
                    onClick = {
                        tempLocationCapture = myLocationOverlay?.myLocation
                        val uri = createImageUri(context)
                        if (uri != null) {
                            currentPhotoUri = uri
                            cameraLauncher.launch(uri)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
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

@Composable
fun GalleryScreenUI(photos: List<PhotoData>, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Text("Galeria", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (photos.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Brak zdjęć.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(photos) { photo ->
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.LightGray)
                    )
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