package com.rdm.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val onRecordingToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var apps = listOf<AppInfo>()
    private var recordingApps = setOf<String>()

    fun submitList(newApps: List<AppInfo>) {
        val diffCallback = AppDiffCallback(apps, newApps)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        apps = newApps
        diffResult.dispatchUpdatesTo(this)
    }

    fun updateRecordingApps(recordingPackages: Set<String>) {
        recordingApps = recordingPackages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_list, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position], recordingApps)
    }

    override fun getItemCount(): Int = apps.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val switchRecord: Switch = itemView.findViewById(R.id.switchRecord)

        fun bind(app: AppInfo, recordingApps: Set<String>) {
            tvAppName.text = app.appName ?: app.packageName
            tvPackageName.text = app.packageName

            val isRecording = app.packageName in recordingApps
            switchRecord.isChecked = isRecording

            switchRecord.setOnCheckedChangeListener { _, isChecked ->
                onRecordingToggle(app, isChecked)
            }
        }
    }

    private class AppDiffCallback(
        private val oldList: List<AppInfo>,
        private val newList: List<AppInfo>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].packageName == newList[newPos].packageName
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos] == newList[newPos]
        }
    }
}
