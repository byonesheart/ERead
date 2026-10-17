package com.eyue.reader.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudBackupServiceTest : CloudServiceContract() {
    override fun context(): Context = ApplicationProvider.getApplicationContext()
}
