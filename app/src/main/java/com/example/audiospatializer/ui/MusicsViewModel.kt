package com.example.audiospatializer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.example.audiospatializer.AudioSpatializerApp
import com.example.audiospatializer.data.ConvertedTrack

class MusicsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AudioSpatializerApp).repository
    val tracks = repository.tracks.asLiveData()

    suspend fun delete(track: ConvertedTrack) {
        repository.delete(track)
    }

    /**
     * ファイルとDBエントリを削除
     * @return 成功した場合true
     */
    suspend fun deleteWithFile(track: ConvertedTrack): Boolean {
        return repository.deleteWithFile(track)
    }

    /**
     * ファイルをリネーム
     * @return 成功した場合は新しいトラック、失敗した場合はnull
     */
    suspend fun renameFile(track: ConvertedTrack, newDisplayName: String): ConvertedTrack? {
        return repository.renameFile(track, newDisplayName)
    }
}
