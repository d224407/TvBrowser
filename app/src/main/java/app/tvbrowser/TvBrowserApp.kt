package app.tvbrowser

import android.app.Application
import app.tvbrowser.adblock.AdBlockEngine

class TvBrowserApp : Application() {
    val adBlock = AdBlockEngine()
}
