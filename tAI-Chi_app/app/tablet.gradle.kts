// Helper tasks for running tAI-Chi on a test tablet. They work the same on macOS,
// Windows and Linux. Usually started through the wrapper scripts in tAI-Chi_app/:
//
//   ./tablet.sh  run | wifi | devices | pull | uninstall      (macOS / Linux)
//   tablet.cmd   run | wifi | devices | pull | uninstall      (Windows)
//
// tabletRun        Reconnect a Wi-Fi tablet if one was set up, build, install, launch.
// tabletWifi       With the tablet plugged in by USB, switch it to Wi-Fi debugging.
// tabletDevices    Show the connected tablets.
// tabletPull       Copy recorded sessions (tablet: Download/tAI-Chi) to tAI-Chi-repo/recordings/ on this computer.
// tabletUninstall  Remove the app from the tablet (needed once if it was signed with another key).
//                  Refuses while recordings are still in the app's private folder, unless -PtabletForce=true.
//
// Wi-Fi addresses are remembered per computer in tAI-Chi_app/.tablet-address (not committed).

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

val appId = "com.example.tai_chi"
val launchComponent = "$appId/.MainActivity"
val addressFile: File = rootProject.file(".tablet-address")
// Where finished recordings end up on the tablet (Download/tAI-Chi; kept if the app is uninstalled), and
// the app's private folder where they are written while recording (deleted if the app is uninstalled).
val remoteRecordings = "/sdcard/Download/tAI-Chi"
val remotePrivate = "/sdcard/Android/data/$appId/files/recordings"
val localRecordings: File = rootProject.projectDir.parentFile.resolve("recordings")
val forceUninstall = providers.gradleProperty("tabletForce").map { it == "true" }.getOrElse(false)

val adbExecutable: String = run {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) localProps.inputStream().use { props.load(it) }
    val sdk = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    val name = if (System.getProperty("os.name").lowercase().contains("windows")) "adb.exe" else "adb"
    if (sdk != null) File(sdk, "platform-tools/$name").path else name
}

/** Small adb wrapper using only plain Java APIs, so the tasks stay configuration-cache safe. */
object Adb {
    data class Device(val serial: String, val state: String) {
        val isWifi get() = serial.contains(':') || serial.contains("._adb-tls-connect.")
    }

    fun run(adb: String, vararg args: String, timeoutSeconds: Long = 30): String {
        val out = File.createTempFile("adb", ".txt")
        try {
            val process = ProcessBuilder(listOf(adb) + args)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "timed out: adb ${args.joinToString(" ")}"
            }
            return out.readText().trim()
        } finally {
            out.delete()
        }
    }

    fun startServer(adb: String) {
        if (!File(adb).exists() && adb.contains(File.separatorChar)) {
            throw GradleException(
                "adb not found at $adb.\n" +
                    "Install Android Studio and open its SDK Manager once, or fix sdk.dir in local.properties."
            )
        }
        // The adb server runs in the background after this; its output must not be piped
        // to us, or reading that output would never finish.
        ProcessBuilder(adb, "start-server")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(30, TimeUnit.SECONDS)
    }

    fun devices(adb: String): List<Device> =
        run(adb, "devices").lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) Device(parts[0], parts[1]) else null
        }

    fun savedAddresses(file: File): List<String> =
        if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    fun reconnectSaved(adb: String, file: File) {
        val connected = devices(adb).filter { it.state == "device" }.map { it.serial }
        for (address in savedAddresses(file)) {
            if (address in connected) continue
            println("Reconnecting to tablet over Wi-Fi at $address ...")
            println("  " + run(adb, "connect", address, timeoutSeconds = 15))
        }
    }

    /** Session folders saved on the tablet, newest last. */
    fun remoteSessions(adb: String, serial: String, remote: String): List<String> =
        run(adb, "-s", serial, "shell", "ls", "-1", remote).lines().map { it.trim() }
            .filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}_[A-Za-z0-9_-]+")) }
            .sorted()

    fun requireDevice(adb: String): List<Device> {
        val all = devices(adb)
        val ready = all.filter { it.state == "device" }
        if (ready.isNotEmpty()) return ready
        val message = StringBuilder("No tablet is connected.\n")
        if (all.any { it.state == "unauthorized" }) {
            message.append("  The tablet is plugged in but has not allowed this computer yet:\n")
            message.append("  unlock it and tap Allow on the \"Allow USB debugging?\" prompt.\n")
        } else {
            message.append("  - USB: plug the tablet in and make sure USB debugging is on.\n")
            message.append("  - Wi-Fi: the tablet must be on the same Wi-Fi network as this computer.\n")
            message.append("    If it was restarted, plug it in once and run the 'wifi' command again.\n")
        }
        throw GradleException(message.toString())
    }

    /** Finds the tablet's Wi-Fi IPv4 address, or null if it is not on Wi-Fi. */
    fun wifiAddress(adb: String, serial: String): String? {
        val inet = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)")
        val wlan0 = run(adb, "-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
        inet.find(wlan0)?.let { return it.groupValues[1] }
        // Some tablets name the Wi-Fi interface differently: take any wlan interface.
        val all = run(adb, "-s", serial, "shell", "ip", "-o", "-f", "inet", "addr", "show")
        return all.lines()
            .filter { it.contains("wlan") }
            .firstNotNullOfOrNull { inet.find(it)?.groupValues?.get(1) }
    }
}

tasks.register("tabletReconnect") {
    group = "tablet"
    description = "Reconnects a tablet that was set up for Wi-Fi debugging on this computer."
    val adb = adbExecutable
    val file = addressFile
    doLast {
        Adb.startServer(adb)
        Adb.reconnectSaved(adb, file)
        val ready = Adb.requireDevice(adb)
        println("Using: " + ready.joinToString { it.serial + if (it.isWifi) " (Wi-Fi)" else " (USB)" })
    }
}

// installDebug is created by the Android plugin later, so configure it lazily.
tasks.matching { it.name == "installDebug" }.configureEach { mustRunAfter("tabletReconnect") }

tasks.register("tabletRun") {
    group = "tablet"
    description = "Builds the debug app, installs it on the tablet and opens it."
    dependsOn("tabletReconnect", "installDebug")
    val adb = adbExecutable
    val component = launchComponent
    doLast {
        // A tablet that is both plugged in and on Wi-Fi shows up twice; launch once per tablet.
        val launched = mutableSetOf<String>()
        for (device in Adb.devices(adb).filter { it.state == "device" }) {
            val id = Adb.run(adb, "-s", device.serial, "shell", "getprop", "ro.serialno").ifEmpty { device.serial }
            if (!launched.add(id)) continue
            Adb.run(adb, "-s", device.serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            println("Opening tAI-Chi on ${device.serial}")
            println("  " + Adb.run(adb, "-s", device.serial, "shell", "am", "start", "-n", component))
        }
    }
}

tasks.register("tabletWifi") {
    group = "tablet"
    description = "Switches a USB-connected tablet to Wi-Fi debugging so the cable can be unplugged."
    val adb = adbExecutable
    val file = addressFile
    doLast {
        Adb.startServer(adb)
        val usb = Adb.devices(adb).filter { it.state == "device" && !it.isWifi }
        if (usb.isEmpty()) {
            throw GradleException(
                "Plug the tablet in with a USB cable first (and tap Allow if it asks), then run this again.\n" +
                    "The cable is only needed for this one step; after that you can unplug it."
            )
        }
        if (usb.size > 1) {
            throw GradleException("More than one device is plugged in by USB. Leave only the tablet plugged in.")
        }
        val serial = usb.single().serial
        val ip = Adb.wifiAddress(adb, serial)
            ?: throw GradleException("The tablet is not connected to Wi-Fi. Connect it to the same network as this computer.")
        val address = "$ip:5555"

        println("Tablet Wi-Fi address: $ip")
        println(Adb.run(adb, "-s", serial, "tcpip", "5555"))
        Thread.sleep(3000) // the tablet restarts its adb service on the new port
        var result = ""
        for (attempt in 1..5) {
            result = Adb.run(adb, "connect", address, timeoutSeconds = 15)
            if (Adb.devices(adb).any { it.serial == address && it.state == "device" }) break
            Thread.sleep(2000)
        }
        if (Adb.devices(adb).none { it.serial == address && it.state == "device" }) {
            throw GradleException(
                "Could not connect to $address ($result).\n" +
                    "Check that this computer and the tablet are on the same Wi-Fi network,\n" +
                    "and that the network allows devices to reach each other (some school/guest networks do not)."
            )
        }
        val saved = (Adb.savedAddresses(file).filter { !it.startsWith("$ip:") } + address).distinct()
        file.writeText(saved.joinToString("\n", postfix = "\n"))
        println()
        println("Connected over Wi-Fi at $address. You can unplug the cable now.")
        println("The 'run' command reconnects automatically. After the tablet restarts,")
        println("plug it in once and run 'wifi' again.")
    }
}

tasks.register("tabletDevices") {
    group = "tablet"
    description = "Lists connected tablets."
    val adb = adbExecutable
    val file = addressFile
    val id = appId
    doLast {
        Adb.startServer(adb)
        Adb.reconnectSaved(adb, file)
        val all = Adb.devices(adb)
        if (all.isEmpty()) println("No devices connected.")
        for (device in all) {
            if (device.state != "device") {
                println("${device.serial}  [${device.state}]")
                continue
            }
            val s = device.serial
            val model = Adb.run(adb, "-s", s, "shell", "getprop", "ro.product.model")
            val android = Adb.run(adb, "-s", s, "shell", "getprop", "ro.build.version.release")
            val installed = Adb.run(adb, "-s", s, "shell", "pm", "list", "packages", id)
                .lines().any { it.trim() == "package:$id" }
            println(
                "$s  ${if (device.isWifi) "Wi-Fi" else "USB"}  $model  Android $android  " +
                    "tAI-Chi ${if (installed) "installed" else "not installed"}"
            )
        }
        val saved = Adb.savedAddresses(file)
        if (saved.isNotEmpty()) println("Saved Wi-Fi address(es): ${saved.joinToString()}")
    }
}

tasks.register("tabletPull") {
    group = "tablet"
    description = "Copies recorded sessions from the tablet to tAI-Chi-repo/recordings/."
    val adb = adbExecutable
    val file = addressFile
    val public = remoteRecordings
    val private = remotePrivate
    val local = localRecordings
    doLast {
        Adb.startServer(adb)
        Adb.reconnectSaved(adb, file)
        val serial = Adb.requireDevice(adb).first().serial
        // Finished sessions are in Download/tAI-Chi. A session still in the private folder was not moved
        // yet (recording, or the app closed early): copy it from there.
        val inPublic = Adb.remoteSessions(adb, serial, public)
        val sessions = inPublic.map { it to public } +
            Adb.remoteSessions(adb, serial, private).filter { it !in inPublic }.map { it to private }
        if (sessions.isEmpty()) {
            println("No recordings on the tablet yet.")
            return@doLast
        }
        local.mkdirs()
        var copied = 0
        for ((id, remote) in sessions.sortedBy { it.first }) {
            // Skip sessions already copied in full: same session.json, and it says the session is complete.
            val remoteJson = Adb.run(adb, "-s", serial, "shell", "cat", "$remote/$id/session.json")
            val localJson = File(local, "$id/session.json").takeIf { it.exists() }?.readText()?.trim()
            if (localJson == remoteJson && remoteJson.contains("\"status\": \"complete\"")) {
                println("  $id  already copied")
                continue
            }
            if (remoteJson.contains("\"status\": \"recording\"")) {
                println("  $id  still recording, or the app closed before it finished: copying what is there")
            }
            println("  $id  copying ...")
            val out = Adb.run(adb, "-s", serial, "pull", "$remote/$id", local.path, timeoutSeconds = 600)
            if (!File(local, id).isDirectory) throw GradleException("Copy failed for $id:\n$out")
            copied++
        }
        println()
        println("Copied $copied new session(s). All recordings are in:")
        println("  ${local.path}")
    }
}

tasks.register("tabletUninstall") {
    group = "tablet"
    description = "Uninstalls tAI-Chi from the tablet (this deletes the app's data on the tablet)."
    val adb = adbExecutable
    val file = addressFile
    val id = appId
    val remote = remotePrivate
    val force = forceUninstall
    doLast {
        Adb.startServer(adb)
        Adb.reconnectSaved(adb, file)
        for (device in Adb.requireDevice(adb)) {
            val sessions = Adb.remoteSessions(adb, device.serial, remote)
            if (sessions.isNotEmpty() && !force) {
                throw GradleException(
                    "${sessions.size} recorded session(s) are still in the app's private folder on the tablet,\n" +
                        "and uninstalling the app deletes them. (Sessions already in Download/tAI-Chi are safe.)\n" +
                        "Open the app once so it moves them to Download/tAI-Chi, or:\n" +
                        "  1. Copy them to this computer first:   ./tablet.sh pull   (Windows: tablet.cmd pull)\n" +
                        "  2. Then uninstall anyway with:          ./tablet.sh uninstall force   (Windows: tablet.cmd uninstall force)"
                )
            }
            println("${device.serial}: " + Adb.run(adb, "-s", device.serial, "uninstall", id))
        }
    }
}
