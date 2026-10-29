// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Hands the app's own APK to Bluetooth file transfer. Newer Android Bluetooth sends and accepts
 * only some kinds of file, zip among them but not "Android app"; an APK is a zip archive, so it is
 * declared as one. The file keeps its .apk name, and the receiving phone installs it from there.
 */
class ApkProvider : FileProvider() {
    override fun getType(uri: Uri): String = ZIP

    companion object {
        const val ZIP = "application/zip"
    }
}
