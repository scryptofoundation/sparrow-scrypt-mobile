package com.sparrowwallet.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals

/** The debug QR receiver is exported for adb, so only senders holding DUMP (the adb shell) may reach it. */
class DebugReceiverManifestTest {
    @Test fun onlyTheAdbShellCanSendFrames() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/debug/AndroidManifest.xml"))
        val receiver = doc.getElementsByTagName("receiver").item(0) as Element
        assertEquals(".DebugUrReceiver", receiver.getAttributeNS(ANDROID, "name"))
        assertEquals("android.permission.DUMP", receiver.getAttributeNS(ANDROID, "permission"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
