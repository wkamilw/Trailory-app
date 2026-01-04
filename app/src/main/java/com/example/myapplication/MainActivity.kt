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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog // Import do okienka
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.myapplication.ui.theme.MyApplicationTheme
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

// Model danych
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
        setContent {
            MyApplicationTheme {
                TrailoryNavigation()
            }
        }
    }
}

enum class AppScreen { MAP, GALLERY, SETTINGS }

@Composable
fun TrailoryNavigation() {
    var currentScreen by remember { mutableStateOf(AppScreen.MAP) }
    val capturedPhotos = remember { mutableStateListOf<PhotoData>() }

    var mapCenter by remember { mutableStateOf(GeoPoint(52.23, 21.01)) }
    var mapZoom by remember { mutableStateOf(15.0) }

    when (currentScreen) {
        AppScreen.MAP -> MapScreenUI(
            photos = capturedPhotos,
            onNavigateToGallery = { currentScreen = AppScreen.GALLERY },
            onNavigateToSettings = { currentScreen = AppScreen.SETTINGS },
            onPhotoCaptured = { photoData -> capturedPhotos.add(0, photoData) },
            initialCenter = mapCenter,
            initialZoom = mapZoom,
            onMapPositionChange = { newCenter, newZoom ->
                mapCenter = newCenter
                mapZoom = newZoom
            }
        )
        AppScreen.GALLERY -> GalleryScreenUI(
            photos = capturedPhotos,
            onBack = { currentScreen = AppScreen.MAP }
        )
        AppScreen.SETTINGS -> SettingsScreenUI(
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
    onMapPositionChange: (GeoPoint, Double) -> Unit
) {
    val context = LocalContext.current
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var tempLocationCapture by remember { mutableStateOf<GeoPoint?>(null) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // NOWE: Zmienna przechowująca zdjęcie wybranego znacznika
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
            Toast.makeText(context, "Dodano znacznik!", Toast.LENGTH_SHORT).show()
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
                    // Usuwamy stare markery (poza lokalizacją użytkownika)
                    val overlaysToRemove = map.overlays.filterIsInstance<Marker>()
                    map.overlays.removeAll(overlaysToRemove)

                    // Dodajemy nowe markery
                    photos.forEach { photo ->
                        if (photo.location != null) {
                            val marker = Marker(map)
                            marker.position = photo.location
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.title = "Kliknij, by zobaczyć zdjęcie"

                            // NOWE: Obsługa kliknięcia w marker
                            marker.setOnMarkerClickListener { _, _ ->
                                selectedPhoto = photo // Ustawiamy to zdjęcie jako wybrane
                                true // Zwracamy true, żeby nie pokazywał się domyślny dymek z tekstem
                            }

                            map.overlays.add(marker)
                        }
                    }
                    map.invalidate()
                }
            )

            // --- OKNO PODGLĄDU ZDJĘCIA (Dialog) ---
            if (selectedPhoto != null) {
                Dialog(onDismissRequest = { selectedPhoto = null }) {
                    // Wygląd okienka ze zdjęciem
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp) // Wysokość okienka
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = selectedPhoto!!.uri,
                                contentDescription = "Podgląd zdjęcia",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Przycisk zamykania (X)
                            IconButton(
                                onClick = { selectedPhoto = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Zamknij", tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Uprawnienia
            LaunchedEffect(myLocationOverlay) {
                if (myLocationOverlay != null && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            // UI - Przyciski
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
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

                SmallRoundButton(
                    icon = Icons.Default.MyLocation,
                    onClick = {
                        val overlay = myLocationOverlay
                        if (overlay != null && overlay.isMyLocationEnabled && overlay.myLocation != null) {
                            mapView?.controller?.animateTo(overlay.myLocation)
                            mapView?.controller?.setZoom(18.0)
                            onMapPositionChange(overlay.myLocation, 18.0)
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }
                )
            }
        }
    }
}

// Reszta kodu (Galeria, Ustawienia, Funkcje pomocnicze) bez zmian
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