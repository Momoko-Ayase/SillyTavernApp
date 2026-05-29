package moe.momokko.sillytavernapp

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Makes the bundled static git usable by SillyTavern's simple-git calls.
 *
 * Android (API 29+) refuses to execute binaries from writable app dirs; only
 * nativeLibraryDir is exec-allowed. The git executables ship there as lib*.so,
 * so we expose them under their real names via symlinks in a writable bin dir
 * placed on PATH. Executing a symlink resolves to the exec-allowed inode in
 * nativeLibraryDir (the technique Termux uses).
 *
 * Must be called before NodeBridge.startNodeWithArguments so the env is
 * inherited by child processes (simple-git spawns `git`, which spawns
 * `git-remote-https`).
 */
object GitSetup {

    fun configure(context: Context) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val gitBin = File(context.filesDir, "gitbin").apply { mkdirs() }

        link(File(gitBin, "git"), File(nativeLibDir, "libgit.so"))
        link(File(gitBin, "git-remote-https"), File(nativeLibDir, "libgitremotehttps.so"))
        link(File(gitBin, "git-remote-http"), File(nativeLibDir, "libgitremotehttps.so"))

        val caFile = ensureCaBundle(context)
        val existingPath = System.getenv("PATH") ?: "/system/bin"

        NodeBridge.setEnv("PATH", "${gitBin.absolutePath}:$existingPath")
        NodeBridge.setEnv("GIT_EXEC_PATH", gitBin.absolutePath)
        NodeBridge.setEnv("GIT_SSL_CAINFO", caFile.absolutePath)
        NodeBridge.setEnv("GIT_CONFIG_NOSYSTEM", "1")
        NodeBridge.setEnv("GIT_TERMINAL_PROMPT", "0")
        // A fixed identity so `git pull` can fast-forward/merge without a user config.
        NodeBridge.setEnv("GIT_AUTHOR_NAME", "SillyTavern")
        NodeBridge.setEnv("GIT_AUTHOR_EMAIL", "noreply@localhost")
        NodeBridge.setEnv("GIT_COMMITTER_NAME", "SillyTavern")
        NodeBridge.setEnv("GIT_COMMITTER_EMAIL", "noreply@localhost")
    }

    /** delete-then-symlink so stale links after an APK update are replaced. */
    private fun link(linkFile: File, target: File) {
        try {
            linkFile.delete()
            Os.symlink(target.absolutePath, linkFile.absolutePath)
        } catch (_: Exception) {
            // Best-effort; if the link already resolves correctly this is harmless.
        }
    }

    /** Overwrite each start (tiny file, keeps the CA bundle fresh across updates). */
    private fun ensureCaBundle(context: Context): File {
        val dir = File(context.filesDir, "git").apply { mkdirs() }
        val ca = File(dir, "cacert.pem")
        try {
            context.assets.open("git/cacert.pem").use { input ->
                ca.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (_: Exception) {
            // If the asset is missing, git falls back to isomorphic-git for install.
        }
        return ca
    }
}
