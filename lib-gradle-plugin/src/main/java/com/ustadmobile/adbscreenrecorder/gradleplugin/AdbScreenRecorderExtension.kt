package com.ustadmobile.adbscreenrecorder.gradleplugin

import com.ustadmobile.adbscreenrecorder.RecordingType

open class AdbScreenRecorderExtension {
    var recordingType: RecordingType = RecordingType.ADB
    var adbPath: String? = null
    var scrcpyPath: String? = null

    var destDir: Any? = null
}