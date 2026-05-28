package moe.momokko.sillytavernapp

object NodeBridge {
    init {
        // libnode must load before native-lib (which depends on its symbols).
        System.loadLibrary("node")
        System.loadLibrary("native-lib")
    }

    external fun startNodeWithArguments(arguments: Array<String>): Int
    external fun setEnv(key: String, value: String)
    external fun chDir(path: String): Int
}
