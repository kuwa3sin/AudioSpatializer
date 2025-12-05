package com.example.audiospatializer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.audiospatializer.R
import com.example.audiospatializer.audio.HeadTrackingDeviceManager

/**
 * サポート対象デバイス一覧のアダプター
 */
class SupportedDeviceAdapter(
    private val devices: List<HeadTrackingDeviceManager.SupportedDevice>
) : RecyclerView.Adapter<SupportedDeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textDeviceName: TextView = itemView.findViewById(R.id.textDeviceName)
        val textManufacturer: TextView = itemView.findViewById(R.id.textManufacturer)
        val imageHeadTracking: ImageView = itemView.findViewById(R.id.imageHeadTracking)
        val textHeadTrackingStatus: TextView = itemView.findViewById(R.id.textHeadTrackingStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_supported_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        holder.textDeviceName.text = device.namePattern
        holder.textManufacturer.text = device.manufacturer
        
        if (device.headTrackingSupported) {
            holder.imageHeadTracking.setImageResource(android.R.drawable.ic_menu_compass)
            holder.imageHeadTracking.setColorFilter(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark)
            )
            holder.textHeadTrackingStatus.text = "ヘッドトラッキング対応"
            holder.textHeadTrackingStatus.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark)
            )
        } else {
            holder.imageHeadTracking.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            holder.imageHeadTracking.setColorFilter(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )
            holder.textHeadTrackingStatus.text = "ヘッドトラッキング非対応"
            holder.textHeadTrackingStatus.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )
        }
    }

    override fun getItemCount(): Int = devices.size
}
