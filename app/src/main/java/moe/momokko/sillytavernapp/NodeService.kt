package moe.momokko.sillytavernapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class NodeService : Service() {

    companion object {
        const val PORT = 8616
        const val DATA_ROOT = "/storage/emulated/0/Documents/SillyTavern"
        private const val CHANNEL_ID = "node_service"
        private const val NOTIF_ID = 1

        @Volatile
        private var nodeStarted = false

        // Set when node::Start returns, i.e. the Node event loop ended (server stopped/crashed).
        @Volatile
        var nodeExitCode: Int? = null
        const val ACTION_STOP = "moe.momokko.sillytavernapp.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Node cannot be restarted in-process; terminate so the next open is a clean start.
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startNodeOnce()
        // NOT sticky: we intentionally kill the process on idle (Node can't restart in-process).
        // START_STICKY would make the system resurrect this service headless after the kill —
        // which fails the Android 12+ background FGS-start rule and leaves a broken zombie that
        // blocks the next cold start. Reopening from the launcher cold-starts cleanly instead.
        return START_NOT_STICKY
    }

    private fun startNodeOnce() {
        if (nodeStarted) return
        nodeStarted = true
        Thread {
            val projectDir = AssetExtractor.ensureProject(applicationContext)
            File(DATA_ROOT).also { dir ->
                if (!dir.exists()) dir.mkdirs()
                // Keep exported images/files out of the gallery / media scanner.
                // A .nomedia at the tree root applies recursively.
                runCatching { File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
            }
            NodeBridge.setEnv("NODE_ENV", "production")
            NodeBridge.setEnv("TMPDIR", applicationContext.cacheDir.absolutePath)
            NodeBridge.setEnv("HOME", applicationContext.filesDir.absolutePath)
            // Put the bundled static git on PATH so SillyTavern's simple-git
            // calls (extension update/version/branches/switch) work.
            GitSetup.configure(applicationContext)
            // Default cwd is read-only; ST writes config.yaml/whitelist.txt relative to cwd.
            // Move into the writable extracted project dir so those writes succeed.
            NodeBridge.chDir(projectDir)
            val code = NodeBridge.startNodeWithArguments(
                arrayOf("node", "$projectDir/server.js", "--configPath", "$projectDir/config.yaml")
            )
            // Reached only if Node's event loop ended — the server is no longer running.
            nodeExitCode = code
        }.start()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "SillyTavern Server",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SillyTavern")
            .setContentText("Server running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
