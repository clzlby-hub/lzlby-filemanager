/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import androidx.lifecycle.MutableLiveData

object LanStorageEventBus {
    val lastAddedStorage = MutableLiveData<Storage?>()
}
