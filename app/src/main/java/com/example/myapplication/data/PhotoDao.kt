package com.example.myapplication.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY id DESC")
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity)

    // NOWE: Usuwanie zdjęcia po ID
    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Int)
}