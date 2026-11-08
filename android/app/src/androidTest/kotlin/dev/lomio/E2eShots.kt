package dev.lomio

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Screenshots for the e2e tests (pulled by scripts/ci/emulator-e2e.sh from [DIR]). The busy CI
 * emulator's own apps sometimes ANR; the script hides error dialogs, and as a fallback an
 * "isn't responding" dialog that still has focus is dismissed (Back = "Wait") before capturing.
 */
object E2eShots {
  const val DIR = "/data/local/tmp/e2e"

  fun shell(cmd: String): String {
    val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }
  }

  fun dismissAnrDialog() {
    repeat(2) {
      val focus = shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus=" in it }.orEmpty()
      if ("Application Not Responding" !in focus) return
      shell("input keyevent KEYCODE_BACK")
      Thread.sleep(500)
    }
  }

  fun capture(name: String) {
    dismissAnrDialog()
    shell("mkdir -p $DIR")
    shell("screencap -p $DIR/$name.png")
  }
}
