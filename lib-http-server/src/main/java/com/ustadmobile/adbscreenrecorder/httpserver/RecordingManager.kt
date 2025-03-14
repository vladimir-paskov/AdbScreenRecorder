package com.ustadmobile.adbscreenrecorder.httpserver

import com.ustadmobile.adbscreenrecorder.RecordingType
import com.ustadmobile.adbscreenrecorder.httpserver.AdbScreenRecorderHttpServer.Companion.runProcess
import net.coobird.thumbnailator.Thumbnails
import java.io.File
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit

internal fun File.getDirForTest(className: String, methodName: String): File {
    val clazzDestDir = File(this, className)
    val methodDestDir = File(clazzDestDir, methodName)
    methodDestDir.takeIf{ !it.exists() }?.mkdirs()

    return methodDestDir
}

class RecordingManager(
    private val recordingType: RecordingType = RecordingType.ADB,
    private val adbPath: String,
    private val destDir: File,
    private val scrcpyPath: String? = null) { // TODO: Test behaviour where no scrcpy is available

    companion object {
        private const val MIN_SDK = 22

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").lowercase().contains("win")
        }

        private fun getScrCpyProcessName(): String {
            return if(isWindows()) {
                "scrcpy.exe"
            } else {
                "scrcpy"
            }
        }

        private fun getScrCpyPid(): String? {
            if(isWindows()) {
                val processDetails = runProcess(
                    listOf(
                        "tasklist",
                        "/FI", "IMAGENAME eq scrcpy.exe",
                        "/FO", "CSV",
                        "/NH"
                    )
                ).bufferedReader().readLines()

                // Handle the case where there are no scrcpy tasks running
                val filteredDetails = processDetails.filter { !it.startsWith("INFO:") }

                val parsedProcesses = filteredDetails.mapNotNull { line ->
                    val values = line.split(",").map { it.trim().removeSurrounding("\"") }
                    if (values.size >= 2) {
                        values[1]
                    } else {
                        null
                    }
                }

                if(parsedProcesses.isEmpty()) {
                    return null
                }

                for(pid in parsedProcesses) {
                    println(pid)
                }

                // Assumption that PIDs are sorted by the order of starting
                val pid = parsedProcesses[parsedProcesses.size - 1]
                println("Pid is: $pid")
                return pid
            } else {
                // TODO: Get nix PIDs
                return ""
            }
        }
    }

    data class ProcessHolder(val process: Process,
                             val recordingType: RecordingType,
                             val pid: String? = null)

    private val recordings = mutableMapOf<String, ProcessHolder>()

    private val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)


    fun startRecording(deviceName: String, clazzName: String, testName: String) {
        println("(${dateFormatter.format(Date())}) Starting recording on $deviceName for $clazzName.$testName using ADB $adbPath")
        val sdkInt = AdbScreenRecorderHttpServer.getAndroidSdkVersion(adbPath, deviceName)

        //Take a screenshot and save it
        val testMethodDir = destDir.getDirForTest(clazzName, testName)
        val screenshotDest = File(testMethodDir, "$deviceName.png")
        runProcess(listOf(adbPath, "-s", deviceName, "shell", "screencap", "/sdcard/$clazzName-$testName.png"))
        runProcess(listOf(adbPath, "-s", deviceName, "pull", "/sdcard/$clazzName-$testName.png",
            screenshotDest.absolutePath))
        runProcess(listOf(adbPath, "-s", deviceName, "shell", "rm", "/sdcard/$clazzName-$testName.png"))

        if(screenshotDest.exists()) {
            Thumbnails.of(screenshotDest)
                .size(640, 640)
                .outputFormat("jpg")
                .toFile(File(screenshotDest.parent, "${screenshotDest.nameWithoutExtension}.jpg"))
            screenshotDest.delete()
        }

        if(sdkInt < MIN_SDK) {
            println("Screen recording is supported only on SDK $MIN_SDK or newer!")
            return
        }

        val recordProcessHolder: ProcessHolder = when(recordingType) {
            RecordingType.ADB -> createAndStartAdbScreenRecordProcess(deviceName, clazzName, testName)
            RecordingType.SCRCPY -> {
                if(scrcpyPath.isNullOrEmpty()) {
                    throw IllegalStateException("Recording with 'ScrCpy' requires valid 'ScrCpy' path.")
                } else {
                    createAndStartScrCpyProcess(deviceName, clazzName, testName)
                }
            }
        }

        recordings["$clazzName-$testName"] = recordProcessHolder
    }

    private fun createAndStartAdbScreenRecordProcess(deviceName: String,
                                                     clazzName: String,
                                                     testName: String) : ProcessHolder
    {
        val recordProcess = ProcessBuilder(listOf(adbPath, "-s", deviceName,
            "shell", "screenrecord", "/sdcard/$clazzName-$testName.mp4"))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        return ProcessHolder(recordProcess, RecordingType.ADB)
    }

    private fun createAndStartScrCpyProcess(deviceName: String, clazzName: String, testName: String): ProcessHolder {
        val dirPath = destDir.getDirForTest(clazzName, testName)

        val recordProcess = ProcessBuilder(listOf(scrcpyPath, "-s", deviceName,
            "--no-window", "--no-playback", "--no-audio", "--record=${dirPath.absolutePath}${File.separator}${deviceName}.mp4"))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        return ProcessHolder(recordProcess, RecordingType.SCRCPY, getScrCpyPid())
    }

    fun stopRecording(deviceName: String, clazzName: String, testName: String): File {
        val processHolder = recordings["$clazzName-$testName"] ?: throw IllegalStateException("no such recording")
        println("(${dateFormatter.format(Date())}) Stopping recording recording on $deviceName for $clazzName.$testName using ADB $adbPath")

        val destVideoFile: File

        val methodDestDir = destDir.getDirForTest(clazzName, testName)

        if(processHolder.recordingType == RecordingType.ADB) {
            //Note: screenrecord itself is actually running on the device. Thus we need to send SIGINT
            // on the device, NOT to the adb process
            ProcessBuilder(listOf(adbPath, "-s", deviceName, "shell", "kill", "-SIGINT",
                "$(pidof screenrecord)")).start().also {
                it.waitFor(20, TimeUnit.SECONDS)
            }

            processHolder.process.waitFor(20, TimeUnit.SECONDS)

            destVideoFile = File(methodDestDir, "$deviceName.mp4")

            ProcessBuilder(listOf(adbPath, "-s", deviceName, "pull",
                "/sdcard/$clazzName-$testName.mp4", destVideoFile.absolutePath))
                .start().also {
                    it.waitFor(60, TimeUnit.SECONDS)
                }

            ProcessBuilder(listOf(adbPath, "-s", deviceName, "shell", "rm",
                "/sdcard/$clazzName-$testName.mp4"))
                .start().also {
                    it.waitFor(60, TimeUnit.SECONDS)
                }

            recordings.remove("$clazzName-$testName")
        } else {
            println("Attempting to stop 'ScrCpy'. PID: ${processHolder.pid}")
            stopProcess(processHolder.pid)
            println("Waiting for 'ScrCpy' to stop.")
            processHolder.process.waitFor(60, TimeUnit.SECONDS)

            recordings.remove("$clazzName-$testName")

            destVideoFile = File(methodDestDir, "${deviceName}.mp4")
        }

        return destVideoFile
    }

    private fun stopProcess(processId: String?) {
        if(processId.isNullOrEmpty()) {
            // If we have no process id, kill all scrcpy instances
            if (isWindows()) {
                runProcess(listOf("taskkill", "/F", "/IM", getScrCpyProcessName()))
            } else {
                runProcess(listOf("pkill", "-SIGINT", getScrCpyProcessName()))
            }
        } else {
            if(isWindows()) {
                // Using windows-kill to send Ctrl - C to ScrCpy, because otherwise
                // the recording will be corrupted.
                // https://github.com/ElyDotDev/windows-kill
                runProcess(listOf("windows-kill", "-2", processId))
            } else {
                // TODO: This should kill by PID also
                runProcess(listOf("pkill", "-SIGINT", processId))
            }
        }
    }
}