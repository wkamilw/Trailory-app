package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uriString: String, // Ścieżka do zdjęcia
    val latitude: Double?, // Szerokość geogr. (może być null)
    val longitude: Double? // Długość geogr. (może być null)
)