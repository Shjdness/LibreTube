package com.github.libretube.parcelable

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadData(
    val videoId: String
) : Parcelable
