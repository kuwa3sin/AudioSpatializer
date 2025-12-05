package com.example.audiospatializer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.audiospatializer.R
import com.example.audiospatializer.data.ConvertedTrack
import com.example.audiospatializer.util.FormatUtils
import com.google.android.material.button.MaterialButton

class MusicListAdapter(
    private val onPlay: (ConvertedTrack) -> Unit,
    private val onMore: (ConvertedTrack, View) -> Unit
) : RecyclerView.Adapter<MusicListAdapter.VH>() {

    private val items = mutableListOf<ConvertedTrack>()

    fun submit(list: List<ConvertedTrack>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items[position]
        holder.title.text = track.displayName
        val dateStr = FormatUtils.formatDateTime(track.createdAt)
        val sizeStr = FormatUtils.formatFileSize(track.fileSizeBytes)
        val duration = FormatUtils.formatDuration(track.durationMs)
        holder.subtitle.text = "$dateStr  •  $sizeStr  •  $duration"
        holder.itemView.setOnClickListener { onPlay(track) }
        holder.more.setOnClickListener { onMore(track, it) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val more: MaterialButton = v.findViewById(R.id.moreBtn)
    }
}
