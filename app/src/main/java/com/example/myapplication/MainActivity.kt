package com.example.myapplication

import android.content.ContentValues
import android.content.Context
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
import coil.compose.AsyncImage // To odpowiada za wyświetlanie zdjęć
import com.example.myapplication.ui.theme.MyApplicationTheme
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

// --- GŁÓWNA AKTYWNOŚĆ ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Konfiguracja mapy OSM
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

// --- NAWIGACJA I STAN APLIKACJI ---
enum class AppScreen { MAP, GALLERY, SETTINGS }

@Composable
fun TrailoryNavigation() {
    var currentScreen by remember { mutableStateOf(AppScreen.MAP) }

    // TU JEST KLUCZ: Lista zdjęć trzymana w pamięci aplikacji
    val capturedPhotos = remember { mutableStateListOf<Uri>() }

    when (currentScreen) {
        AppScreen.MAP -> MapScreenUI(
            onNavigateToGallery = { currentScreen = AppScreen.GALLERY },
            onNavigateToSettings = { currentScreen = AppScreen.SETTINGS },
            // Gdy zrobisz zdjęcie na mapie, dodajemy je do listy
            onPhotoCaptured = { newUri ->
                capturedPhotos.add(0, newUri)
            }
        )
        // Przekazujemy listę zdjęć do Galerii
        AppScreen.GALLERY -> GalleryScreenUI(
            photos = capturedPhotos,
            onBack = { currentScreen = AppScreen.MAP }
        )
        AppScreen.SETTINGS -> SettingsScreenUI(
            onBack = { currentScreen = AppScreen.MAP }
        )
    }
}

// --- EKRAN GALERII (POPRAWIONY) ---
@Composable
fun GalleryScreenUI(
    photos: List<Uri>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Górny pasek
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Text("Galeria w Aplikacji", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Logika wyświetlania: Pusto vs Zdjęcia
        if (photos.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Brak zdjęć w tej sesji. Zrób zdjęcie!", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            // SIATKA ZDJĘĆ
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(photos) { uri ->
                    // Komponent biblioteki Coil - wyświetla zdjęcie z URI
                    AsyncImage(
                        model = uri,
                        contentDescription = "Zdjęcie z mapy",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.LightGray)
                    )
                }
            }
        }
    }
}

// --- EKRAN MAPY ---
@Composable
fun MapScreenUI(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPhotoCaptured: (Uri) -> Unit
) {
    val context = LocalContext.current
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            Toast.makeText(context, "Zdjęcie dodane!", Toast.LENGTH_SHORT).show()
            // Przekazujemy zdjęcie "w górę" do nawigacji
            onPhotoCaptured(currentPhotoUri!!)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Mapa
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        controller.setCenter(GeoPoint(52.23, 21.01))
                    }
                }
            )

            // Przycisk Ustawienia
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
            ) { Icon(Icons.Default.Settings, null) }

            // Dolny pasek
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallRoundButton(Icons.Default.Collections, onNavigateToGallery)

                FloatingActionButton(
                    onClick = {
                        val uri = createImageUri(context)
                        if (uri != null) {
                            currentPhotoUri = uri
                            cameraLauncher.launch(uri)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp)
                ) { Icon(Icons.Default.CameraAlt, null, Modifier.size(40.dp)) }

                SmallRoundButton(Icons.Default.MyLocation, {})
            }
        }
    }
}

// --- EKRAN USTAWIEŃ ---
@Composable
fun SettingsScreenUI(onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        Text("Ustawienia", style = MaterialTheme.typography.headlineSmall)
    }
}

// --- ELEMENTY POMOCNICZE ---
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