package com.retrobox.tv

import com.retrobox.tv.emulator.Systems
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every core in this install ships with its license text, and so does the app (Settings → About & licenses). */
class LicenseAssetsDeviceTest {
    private val files = TestRoms.app.assets.list("licenses").orEmpty().toSet()

    private fun read(name: String) = TestRoms.app.assets.open("licenses/$name").bufferedReader().use { it.readText() }

    @Test fun appLicenseAndNoticesArePackaged() {
        assertTrue(files.toString(), "retrobox-GPL-2.0.txt" in files && "THIRD_PARTY_NOTICES.txt" in files && "Apache-2.0.txt" in files)
        assertTrue(read("retrobox-GPL-2.0.txt").contains("GNU GENERAL PUBLIC LICENSE"))
    }

    @Test fun everyInstalledCoreHasItsLicense() {
        val libDir = File(TestRoms.app.applicationInfo.nativeLibraryDir)
        val installed = Systems.all.flatMap { it.cores }.map { it.id }.distinct().filter { File(libDir, "lib${it}_libretro_android.so").exists() }
        val missing = installed.filterNot { "core-$it.txt" in files }
        assertTrue("cores without a license text: $missing (run cores/build-cores.sh)", missing.isEmpty())
        installed.forEach { assertTrue("core-$it.txt names no source commit", read("core-$it.txt").contains("commit ")) }
    }
}
