package com.vpnsdk.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Native tun2socks bridge.
 *
 * This mirrors the stable architecture used by production Shadowsocks clients:
 * TUN fd -> tun2socks -> local SOCKS endpoint provided by protocol core.
 */
class Tun2Socks(private val context: Context) {
    companion object {
        private const val TAG = "Tun2Socks"
        private const val TUN2SOCKS_BINARY = "libtun2socks.so"
        private const val DEFAULT_ROUTER_IPV4 = "172.19.0.2"
        private const val DEFAULT_MTU = 1400
        private const val EXTRACTED_TUN2SOCKS_PREFIX = "tun2socks_"

        fun preflight(context: Context): Result<File> {
            return try {
                val binary = resolveBinary(context)
                Result.success(binary)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private fun resolveBinary(context: Context): File {
            val nativeLibraryBinary = File(context.applicationInfo.nativeLibraryDir, TUN2SOCKS_BINARY)
            if (nativeLibraryBinary.exists() && nativeLibraryBinary.canExecute()) {
                return nativeLibraryBinary
            }

            // On some devices/builds, native libs are not extracted to nativeLibraryDir.
            // Fall back to extracting libtun2socks.so from the installed APK.
            val abi = Build.SUPPORTED_ABIS.firstOrNull()
                ?: throw IllegalStateException("No ABI reported by system")
            val apkLibPath = "lib/$abi/$TUN2SOCKS_BINARY"
            val extractedBinary = File(context.noBackupFilesDir, "$EXTRACTED_TUN2SOCKS_PREFIX$abi")
            if (extractedBinary.exists() && extractedBinary.canExecute() && extractedBinary.length() > 0L) {
                return extractedBinary
            }

            val sourceApk = context.applicationInfo.sourceDir
            ZipFile(sourceApk).use { zipFile ->
                val entry = zipFile.getEntry(apkLibPath)
                    ?: throw IllegalStateException(
                        "tun2socks binary not found in APK entry $apkLibPath (sourceApk=$sourceApk)"
                    )
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(extractedBinary).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            extractedBinary.setReadable(true, false)
            extractedBinary.setExecutable(true, false)
            if (!extractedBinary.exists() || !extractedBinary.canExecute() || extractedBinary.length() <= 0L) {
                throw IllegalStateException("Extracted tun2socks is not executable: ${extractedBinary.absolutePath}")
            }
            return extractedBinary
        }
    }

    private var process: Process? = null
    private var sockPathFile: File? = null

    suspend fun start(
        vpnFd: FileDescriptor,
        socksHost: String,
        socksPort: Int,
        dnsGatewayHost: String,
        dnsPort: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            stopInternal()

            val binary = preflight(context).getOrElse { error ->
                return@withContext Result.failure(
                    IllegalStateException(
                        "tun2socks preflight failed: ${error.message}",
                        error
                    )
                )
            }

            val sockPath = File(context.noBackupFilesDir, "tun2socks_${System.currentTimeMillis()}.sock")
            runCatching { sockPath.delete() }
            sockPathFile = sockPath

            val cmd = mutableListOf(
                binary.absolutePath,
                "--netif-ipaddr", DEFAULT_ROUTER_IPV4,
                "--socks-server-addr", "$socksHost:$socksPort",
                "--tunmtu", DEFAULT_MTU.toString(),
                "--sock-path", sockPath.absolutePath,
                "--dnsgw", "$dnsGatewayHost:$dnsPort",
                "--loglevel", "warning",
                "--enable-udprelay"
            )

            process = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()

            val runningProcess = process ?: return@withContext Result.failure(IllegalStateException("tun2socks did not start"))
            waitForSocketPath(sockPath)
            sendFdToTun2Socks(vpnFd, sockPath)

            if (!runningProcess.isAlive) {
                val output = runCatching { runningProcess.inputStream.bufferedReader().readText() }.getOrNull().orEmpty()
                stopInternal()
                return@withContext Result.failure(
                    IllegalStateException(
                        if (output.isNotBlank()) "tun2socks exited early: $output"
                        else "tun2socks exited early"
                    )
                )
            }

            Log.d(TAG, "tun2socks started and attached to TUN fd")
            Result.success(Unit)
        } catch (e: Exception) {
            stopInternal()
            Log.e(TAG, "Failed to start tun2socks", e)
            Result.failure(e)
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            stopInternal()
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private suspend fun waitForSocketPath(path: File) {
        repeat(40) {
            if (path.exists()) return
            delay(50)
        }
        throw IOException("tun2socks control socket not available: ${path.absolutePath}")
    }

    private fun sendFdToTun2Socks(fd: FileDescriptor, path: File) {
        val address = LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
        var lastError: Exception? = null

        repeat(6) { attempt ->
            try {
                LocalSocket().use { socket ->
                    socket.connect(address)
                    socket.setFileDescriptorsForSend(arrayOf(fd))
                    socket.outputStream.write(42)
                }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(50L shl attempt)
            }
        }

        throw IOException("Failed to pass TUN fd to tun2socks", lastError)
    }

    private fun stopInternal() {
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        }
        process = null

        sockPathFile?.let { runCatching { it.delete() } }
        sockPathFile = null
    }
}

