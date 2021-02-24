package it.unige.hidedroid.view

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import it.unige.hidedroid.R
import it.unige.hidedroid.activity.SaveSettingApkActivity
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.models.AppItemRepacking
import it.unige.hidedroid.utils.Utilities
import it.unige.hidedroid.myinterface.ItemClickListener
import it.unige.hidedroid.models.ListAppsItemRepacking
import java.io.File
import java.io.IOException

class AppViewHolderRepacking(itemView:View): RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener{

    var iconApp: ImageView = itemView.findViewById(R.id.iconAppRepacking) as ImageView
    var packageName: TextView = itemView.findViewById(R.id.idPackageName) as TextView
    var isInstalled: TextView = itemView.findViewById(R.id.isInstalled) as TextView
    private var itemClickListener: ItemClickListener?=null

    init {
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)

    }

    fun setItemClickListener(itemClickListener: ItemClickListener){
        this.itemClickListener = itemClickListener
    }

    override fun onClick(v: View?) {
        // !! will return NON-NULL value of this variable
        itemClickListener!!.onClick(v, adapterPosition, false)
    }

    override fun onLongClick(v: View?): Boolean {
        itemClickListener!!.onClick(v, adapterPosition, true)
        return true
    }

}

class AppAdapterRepacking(
    public var listAppObject: ListAppsItemRepacking,
    public var listAppObjectFiltered: ListAppsItemRepacking,
    private val mContext: Context,
    private val mActivity: Activity): RecyclerView.Adapter<AppViewHolderRepacking>(), Filterable {

    companion object{
        private val TAG = AppAdapterRepacking::class.java.name
    }
    private val inflater:LayoutInflater = LayoutInflater.from(mContext)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolderRepacking {
        val itemView = inflater.inflate(R.layout.row_list_apps, parent, false)
        return AppViewHolderRepacking(itemView)
    }

    override fun getItemCount(): Int {
        return listAppObjectFiltered.appRepackings.size
    }

    override fun onBindViewHolder(holder: AppViewHolderRepacking, position: Int) {

        holder.packageName.text = listAppObjectFiltered.appRepackings[position].packageName
        holder.iconApp.setImageDrawable(listAppObjectFiltered.appRepackings[position].icon)
        holder.isInstalled.text = listAppObjectFiltered.appRepackings[position].status


        holder.setItemClickListener(object : ItemClickListener {
            override fun onClick(view: View?, position: Int, isLongClick: Boolean) {
                LoggerHideDroid.d(TAG, "Click on single app")
                if (!isLongClick) {
                    val appInfo = listAppObjectFiltered.appRepackings[position].appInfo
                    val packageName: String = appInfo.packageName

                    if (listAppObjectFiltered.appRepackings[position].isInstalled) {
                        val publicSourceDir: String = appInfo.publicSourceDir
                        val src = File(publicSourceDir)
                        val dst = File(File(Environment.getExternalStorageDirectory(), "HideDroid")!!.path + "/" + packageName + ".apk")
                        try {
                            Utilities.copyFile(src, dst)
                            val saveApkResultIntent = Intent(mContext, SaveSettingApkActivity::class.java)
                            val bundle = Bundle()
                            bundle.putStringArray(SaveSettingApkActivity.MSG_SAVEAPKSERVICE, arrayOf(dst.absolutePath, packageName, "true"))
                            saveApkResultIntent.putExtras(bundle)
                            mActivity.startActivity(saveApkResultIntent, ActivityOptions.makeSceneTransitionAnimation(mActivity).toBundle())
                        } catch (e: IOException) {
                            if (e.toString().contains("ENOSPC")) {
                                Utilities.ShowAlertDialog(
                                        mContext,
                                        "Errors",
                                        "No space left"
                                )
                            } else
                                throw AssertionError("FAIL: copy from $src to $dst")
                        }

                    } else {
                        val saveApkResultIntent = Intent(mContext, SaveSettingApkActivity::class.java)
                        val bundle = Bundle()
                        var apk_path = File(File(Environment.getExternalStorageDirectory(), "HideDroid")!!.path + "/" + "${packageName}_signed.apk")

                        bundle.putStringArray(SaveSettingApkActivity.MSG_SAVEAPKSERVICE, arrayOf(apk_path.absolutePath, packageName, "false"))
                        saveApkResultIntent.putExtras(bundle)
                        mActivity.startActivity(saveApkResultIntent, ActivityOptions.makeSceneTransitionAnimation(mActivity).toBundle())
                    }
                } else {
                    if(listAppObjectFiltered.appRepackings[position].isInstalled) {
                        val alertDialogBuilder = AlertDialog.Builder(mActivity, R.style.CustomAlertDialogRounded)
                        val appInfo = listAppObjectFiltered.appRepackings[position].appInfo
                        val packageName: String = appInfo.packageName

                        alertDialogBuilder
                                .setTitle("Removing app")
                                .setMessage("Would you like to uninstall the ${packageName} ?")
                                .setPositiveButton("Yes") { dialog, id ->
                                    val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
                                    mActivity.startActivity(intent)

                                }
                                .setNegativeButton("No") { dialog, id -> dialog.cancel() }
                        val alertDialog = alertDialogBuilder.create()
                        alertDialog.show()
                    }
                }
            }
        })
    }


    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(p0: CharSequence?): FilterResults {
                var charString = p0.toString();
                if (charString.isEmpty() || charString.equals("")) {
                    listAppObjectFiltered.appRepackings = listAppObject.appRepackings;
                } else {
                    var listAppFiltered = mutableListOf<AppItemRepacking>()
                    for (app in listAppObject.appRepackings) {

                        // name match condition. this might differ depending on your requirement
                        // here we are looking for name or package number match
                        if (app.packageName.toLowerCase().contains(charString.toLowerCase()) ||
                                (app?.name != null && app.name.toLowerCase().contains(charString.toLowerCase())) ) {
                            listAppFiltered.add(app);
                        }
                    }
                    listAppObjectFiltered.appRepackings = listAppFiltered
                }


                var filterResults = FilterResults();
                filterResults.values = listAppObjectFiltered
                return filterResults;
            }

            override fun publishResults(p0: CharSequence?, p1: FilterResults?) {
                listAppObjectFiltered = p1!!.values as ListAppsItemRepacking
                notifyDataSetChanged()
            }

        }
    }

}