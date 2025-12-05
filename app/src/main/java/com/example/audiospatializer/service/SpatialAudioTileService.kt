package com.example.audiospatializer.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.audiospatializer.AudioSpatializerApp
import com.example.audiospatializer.R
import com.example.audiospatializer.settings.SpatialAudioSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SpatialAudioTileService : TileService() {

    private val scope = MainScope()
    private var tileJob: Job? = null
    private val repository by lazy { (application as AudioSpatializerApp).spatialSettings }

    override fun onStartListening() {
        super.onStartListening()
        tileJob?.cancel()
        tileJob = scope.launch {
            repository.settingsFlow.collectLatest { updateTileState(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        tileJob?.cancel()
        tileJob = null
    }

    override fun onClick() {
        super.onClick()
        SpatialAudioService.toggle(this)
    }

    private fun updateTileState(settings: SpatialAudioSettings) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_spatial_audio)
        tile.state = if (settings.immersiveEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
