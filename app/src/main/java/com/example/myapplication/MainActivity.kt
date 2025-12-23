package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
// Upewnij się, że masz import do CameraAlt (lub Add, jeśli nie zmieniałeś biblioteki ikon)
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.ui.theme.MyApplicationTheme

// Importy dla OpenStreetMap (osmdroid)
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // KONFIGURACJA OSM:
        // Biblioteka musi wiedzieć, jak zapisywać kafelki mapy w pamięci telefonu.
        // Używamy do tego domyślnych ustawień aplikacji.
        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        // Ustawiamy User-Agent (wymagane przez serwery OSM, żeby nie blokowały połączeń)
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TrailoryAppScreen()
            }
        }
    }
}

@Composable
fun TrailoryAppScreen() {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: Zrób zdjęcie */ }
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Zrób zdjęcie"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // --- MAPA OPEN STREET MAP ---
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Tutaj tworzymy widok mapy w "starym stylu" (Views)
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK) // Styl mapy (standardowy)
                        setMultiTouchControls(true) // Włączamy przybliżanie palcami

                        // Ustawiamy startowy punkt (Polska)
                        controller.setZoom(6.0)
                        controller.setCenter(GeoPoint(52.0, 19.0))
                    }
                }
            )
        }
    }
}