package ai.hermes.app

object UserAgents {
    // Chrome 130 on Linux x86_64 (desktop)
    const val DESKTOP =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    // Empty string → WebView falls back to default mobile UA
    const val MOBILE = ""
}
