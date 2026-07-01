package tv.kyshu.xposed.utils.config

interface ConfigLoader {
    /**
     * Name of the loader strategy (for logging)
     */
    val name: String

    /**
     * Attempts to load configuration.
     * @return A map of key-value pairs if successful, or null if this loader failed/has no data.
     */
    fun load(): Map<String, Any?>?
}
