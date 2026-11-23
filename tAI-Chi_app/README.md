# tAI-Chi tablet app

tAI-Chi watches you through the tablet's front camera, compares your body with an instructor's poses, and tells you what to fix ("Bend your left knee"). Two small motion sensors (IMUs) can also stream movement data to the app over Bluetooth.

This guide starts from a brand-new computer and ends with you practicing. It works on **macOS and Windows**; where the steps differ, both are given. For how the code works, see the [main README](../README.md).

**Contents**

1. [What you need](#1-what-you-need)
2. [Set up your computer](#2-set-up-your-computer-once)
3. [Set up the tablet](#3-set-up-the-tablet-once)
4. [Install the app on the tablet](#4-install-the-app-on-the-tablet)
5. [Set up the IMU sensors](#5-set-up-the-imu-sensors-once-per-board)
6. [Use the app](#6-use-the-app)
7. [Troubleshooting](#7-troubleshooting)

---

## 1. What you need

| Item | Notes |
| --- | --- |
| A Mac or Windows computer | About 15 GB free disk space for Android Studio and the Android SDK |
| An Android tablet with a front camera | Android 7.0 or newer. The app is laid out for landscape. |
| A USB cable for the tablet | It must carry data. Some cables only charge. |
| Two **Seeed XIAO nRF52840 Sense** boards (optional) | The left and right IMU sensors. The app works without them. |
| A USB-C cable for the boards, and a way to power them | A USB power bank, or a small LiPo battery on the board's battery pads |

---

## 2. Set up your computer (once)

### 2.1 Install Git

- **Mac:** open **Terminal** (press Cmd+Space, type "Terminal") and run `git --version`. If Git is missing, macOS offers to install the developer tools: click **Install**.
- **Windows:** download Git from [git-scm.com](https://git-scm.com/download/win) and install it with the default options.

### 2.2 Download the code

In Terminal (Mac) or **Command Prompt** (Windows: Start menu, type "cmd"):

```
cd Documents
git clone https://github.com/jylin85/tAI-Chi.git tAI-Chi-repo
```

If GitHub asks you to sign in, use an account that has been given access to the repository. Ask the repository owner if you do not have access.

The app lives in the `tAI-Chi-repo/tAI-Chi_app` folder. Later, to get teammates' latest changes, run `git pull` inside `tAI-Chi-repo`.

### 2.3 Install Android Studio

You need Android Studio even if you never write code in it. The build uses its built-in Java and Android SDK, so you do not install Java separately.

1. Download the latest stable version from [developer.android.com/studio](https://developer.android.com/studio).
   - **Mac:** pick **Mac with Apple chip** or **Mac with Intel chip**. To check which you have, open the Apple menu → **About This Mac**.
   - **Windows:** run the installer with the default options.
2. Open Android Studio and choose the **Standard** setup. It downloads the Android SDK, which takes several minutes.
3. Choose **Open**, select the `tAI-Chi-repo/tAI-Chi_app` folder, and wait until the progress bar at the bottom ("Gradle sync") finishes. If it asks to install **Android SDK Platform 37**, accept.

You can close Android Studio after this.

### 2.4 Optional: VS Code

If you prefer [VS Code](https://code.visualstudio.com/), open the `tAI-Chi-repo` folder in it. The repository already includes VS Code tasks for the tablet: see [step 4.2](#42-updating-the-app-after-every-change).

---

## 3. Set up the tablet (once)

1. **Turn on developer options.** Open **Settings → About tablet** and tap **Build number** 7 times, until it says you are a developer. On Samsung tablets it is under **Settings → About tablet → Software information**.
2. **Turn on USB debugging.** Open **Settings → System → Developer options** (on Samsung: **Settings → Developer options**) and switch on **USB debugging**.
3. **Connect it to Wi-Fi** if you want to install without a cable later. It must be on the same network as your computer.
4. **Plug the tablet into your computer** and unlock it. When it asks **"Allow USB debugging?"**, tick **Always allow from this computer** and tap **Allow**. Every new computer gets this prompt once.
5. If the tablet asks what the USB connection is for, choose **File transfer**.

---

## 4. Install the app on the tablet

All commands in this section are run from the **`tAI-Chi_app` folder**:

```
cd Documents/tAI-Chi-repo/tAI-Chi_app
```

There are two versions of each command:

| | Mac (Terminal) | Windows (Command Prompt) | Windows (PowerShell) |
| --- | --- | --- | --- |
| Example | `./tablet.sh` | `tablet.cmd` | `.\tablet.cmd` |

### 4.1 First install

With the tablet plugged in, run:

- **Mac:** `./tablet.sh`
- **Windows:** `tablet.cmd`

The first run downloads the build tools and takes a few minutes. When it finishes, the app opens on the tablet by itself. The output ends with:

```
Opening tAI-Chi on ...
BUILD SUCCESSFUL
```

The app is now installed as **tAi-Chi** in the tablet's app drawer, and you can open it any time without the computer.

### 4.2 Updating the app after every change

> **The tablet does not update by itself.** Editing a file, saving it, or running `git pull` changes nothing on the tablet. The tablet keeps running the last version that was installed until you install the new one with the steps below. Do this after **every** change you want to see on the tablet, whoever made the change.

#### Which changes need an update

| What changed | Update the tablet app? |
| --- | --- |
| Any code in `tAI-Chi_app/app/` or `tAI-Chi_app/pose-engine/` (`.kt` files) | **Yes**: follow the steps below |
| Images, text, icons (`res/` folders), the pose routine or model (`pose-engine/src/main/assets/`) | **Yes** |
| Build settings (`*.gradle.kts`, `gradle/libs.versions.toml`) | **Yes** |
| Teammates' changes you got with `git pull` | **Yes**, if any of the above changed. If unsure, update anyway: it does no harm. |
| IMU board firmware (`XIAOinterfaceAppTablet/XIAOinterfaceAppTablet.ino`) | **No**: the tablet command does not touch the boards. Upload the sketch to each board instead ([section 5.2](#52-program-the-left-board)). |
| Python scripts in the repo root (`coach.py`, `extract_reference.py`, …) | **No**: they run on the computer. A new routine only reaches the app after you copy it into `pose-engine/src/main/assets/reference/`, which is then a "Yes". |
| Documentation (`README.md` files) | **No** |

#### Steps

1. **Save every file you edited.** Unsaved changes are not included. In VS Code: **File → Save All** (Mac: Option+Cmd+S; Windows: Ctrl+K, then S).
2. **Optional: get teammates' changes.** In the `tAI-Chi-repo` folder, run `git pull`.
3. **Make sure the tablet is reachable and awake.** Either it is plugged in by USB, or Wi-Fi mode is set up ([4.3](#43-debugging-tablet-app-without-a-cable-wi-fi)). Unlock it so you can watch the app open.
4. **Open a terminal in the `tAI-Chi_app` folder:**

   | Mac (Terminal) | Windows (Command Prompt) | Windows (PowerShell) |
   | --- | --- | --- |
   | `cd ~/Documents/tAI-Chi-repo/tAI-Chi_app` | `cd %USERPROFILE%\Documents\tAI-Chi-repo\tAI-Chi_app` | `cd ~\Documents\tAI-Chi-repo\tAI-Chi_app` |

5. **Run the update command:**

   | Mac | Windows (Command Prompt) | Windows (PowerShell) |
   | --- | --- | --- |
   | `./tablet.sh` | `tablet.cmd` | `.\tablet.cmd` |

   It rebuilds the app from your current files, installs it on the tablet in place of the old version, and opens it. This takes 10–30 seconds. The first run on a new computer takes a few minutes.
6. **Check that it worked.** The end of the output should contain these four lines, in this order:

   ```
   Using: 192.168.0.204:5555 (Wi-Fi)            <- the tablet it found (USB or Wi-Fi)
   Installed on 1 device.                        <- the new version is on the tablet
   Opening tAI-Chi on 192.168.0.204:5555         <- the app is being opened
   BUILD SUCCESSFUL
   ```

7. **On the tablet,** the app restarts with your changes. Permissions are kept, but the routine starts again from pose 1.

**If the output says `BUILD FAILED`, the tablet still has the old version.** Scroll up to the first line starting with `e:`. It names the file and line with the error, for example `e: file:///…/MainActivity.kt:120:5 Unresolved reference`. Fix it, save, and run the command again. For "No tablet is connected" and other messages, see [Troubleshooting](#7-troubleshooting).

#### Shortcuts that do exactly the same thing

- **VS Code:** press **Cmd+Shift+B** (Mac) or **Ctrl+Shift+B** (Windows). It runs the "Tablet: run app" task in VS Code's terminal panel, with the same output as step 6. Save first: step 1 still applies. **Terminal → Run Task…** lists the other tablet commands.
- **Android Studio:** open the `tAI-Chi_app` folder, pick the tablet in the device list at the top, and press the green **▶ Run** button.

Running the update when nothing has changed is harmless: it just reopens the app.

### 4.3 Debugging tablet app without a cable (Wi-Fi)

1. Make sure the tablet and the computer are on the **same Wi-Fi network**.
2. Plug the tablet in by USB and run `./tablet.sh wifi` (Windows: `tablet.cmd wifi`).
3. When it prints **"You can unplug the cable now"**, unplug it.

From then on, `./tablet.sh` installs over Wi-Fi and reconnects by itself. After the tablet restarts, or moves to another Wi-Fi network, repeat these steps once.

University networks such as eduroam usually block devices from talking to each other, so Wi-Fi mode may fail there. Use the cable, or a phone hotspot that both devices join.

### 4.4 Other commands

| Mac | Windows | What it does |
| --- | --- | --- |
| `./tablet.sh devices` | `tablet.cmd devices` | Shows connected tablets (USB or Wi-Fi) and whether the app is installed |
| `./tablet.sh uninstall` | `tablet.cmd uninstall` | Removes the app and its data from the tablet |
| `./tablet.sh test` | `tablet.cmd test` | Runs the pose-engine unit tests |

Android Studio works as well: open the `tAI-Chi_app` folder, pick the tablet in the device list at the top, and press the green **▶ Run** button.

### 4.5 Sharing the tablet with teammates

All teammates' builds are signed with the same shared test key (`app/debug.keystore`), so anyone can install over anyone else's version without uninstalling. Just hand the tablet over and run `./tablet.sh` / `tablet.cmd`.

---

## 5. Set up the IMU sensors (once per board)

Skip this section if you are not using the sensors. The app works without them.

The two IMU boards are Seeed XIAO nRF52840 Sense boards with a built-in motion sensor. Each one runs the sketch in [`XIAOinterfaceAppTablet/`](../XIAOinterfaceAppTablet/XIAOinterfaceAppTablet.ino), which sends accelerometer and gyroscope readings to the tablet 20 times a second over Bluetooth. Each reading carries the time the board took it, so the app can line up the two boards' readings with each other and with the camera (see [6.7](#67-time-sync-between-the-sensors)). One board is set up as **left** and the other as **right**.

### 5.1 Install the Arduino IDE

1. Download **Arduino IDE 2** from [arduino.cc/en/software](https://www.arduino.cc/en/software) and install it.
2. Add Seeed's board list:
   - **Mac:** **Arduino IDE → Settings…**. **Windows:** **File → Preferences…**
   - In **Additional boards manager URLs**, paste:
     ```
     https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
     ```
   - Click **OK**.
3. Install the board package: open **Tools → Board → Boards Manager…**, search for **Seeed nRF52**, and install **Seeed nRF52 Boards**. Do **not** install "Seeed nRF52 mbed-enabled Boards"; the sketch does not work with it.
4. Install the sensor library: open **Tools → Manage Libraries…**, search for **Seeed Arduino LSM6DS3**, and install it. Do **not** install ArduinoBLE; the board package has its own Bluetooth library.

### 5.2 Program the left board

1. In Arduino IDE, choose **File → Open…** and open `tAI-Chi-repo/XIAOinterfaceAppTablet/XIAOinterfaceAppTablet.ino`.
2. Near the top, set the side to left:
   ```cpp
   #define BOARD_SIDE 'L'          // 'L' for the left IMU, 'R' for the right IMU
   ```
3. Plug the first board into the computer with a USB-C cable.
4. Select **Tools → Board → Seeed nRF52 Boards → Seeed XIAO nRF52840 Sense**.
5. Select **Tools → Port** and pick the board's port. On Mac it looks like `/dev/cu.usbmodem…`; on Windows it is a `COM` port.
6. Click **Upload** (the → arrow button) and wait for "Done uploading".
7. To check it, open **Tools → Serial Monitor** and set the speed to **115200 baud**. You should see `Advertising as tAIChi-L`. If you see `IMU not found`, the board is probably the non-Sense XIAO, which has no motion sensor.
8. Put a label on the board, such as "L".

### 5.3 Program the right board

Repeat 5.2 with the second board, but set `#define BOARD_SIDE 'R'`. The Serial Monitor should show `Advertising as tAIChi-R`. Label it "R".

After uploading, change the line back if you don't want the edit committed. The sketch in git is set to `'R'`.

### 5.4 Power the boards

Once programmed, a board starts sending as soon as it has power. It does not need the computer. Power each board from a USB power bank, or from a LiPo battery soldered to the battery pads on the back. Then attach the **L** board on the left side of the body and the **R** board on the right.

You do **not** pair the boards in the tablet's Bluetooth settings. The app finds them by itself (see [6.6](#66-connecting-the-imu-sensors)).

---

## 6. Use the app

### 6.1 Open the app and allow permissions

1. On the tablet, open **tAi-Chi** from the app drawer, or run `./tablet.sh` from the computer.
2. The first time, the app asks for two permissions. Tap **Allow** for both:
   - **Camera**: needed to see you. Without it, the app only shows "Camera access is needed to give Tai-Chi form feedback."
   - **Nearby devices** (Bluetooth): needed to connect to the IMU sensors. On Android 11 and older this appears as **Location**.

### 6.2 Set up the space

- Turn the tablet **sideways (landscape)** and stand it up at about waist height, with the front camera facing the practice area. Turning off auto-rotate helps: turning the tablet restarts the routine.
- Step back until your **whole body, head to feet,** is visible on screen. This is usually 2–3 metres away.
- Use even lighting, and avoid standing in front of a bright window.
- Only one person should be in view.

### 6.3 What's on the screen

```
+--------------------------------------------+---------------------+
| ADJUST                                     | TAI CHI             |
| Bend your left knee                        | FORM FEEDBACK       |
| Raise your right arm                       | Prev Restart Skip   |
|                                            |                     |
|                                            | POSE TO COPY        |
|           (live camera: you)               |  · Pose 3/29        |
|                                            | +-----------------+ |
|                                            | | instructor pose | |
| +----------------+  +----------------+     | +-----------------+ |
| | LEFT IMU       |  | RIGHT IMU      |     | [Pose 3/29        ] |
| | Connected      |  | Searching...   |     | [Error 24° (<20°) ] |
| | ACCEL / GYRO   |  |                |     |                     |
| +----------------+  +----------------+     |                     |
+--------------------------------------------+---------------------+
```

| Area | What it shows |
| --- | --- |
| **Camera view** (whole screen) | You, as in a mirror |
| **Adjust banner** (top left) | Up to three corrections to make right now, in large text you can read from a distance. Red text is a warning, such as no person being detected. It disappears when there is nothing to correct. |
| **Side panel** (right) | The **Prev / Restart / Skip** buttons, the **pose to copy** with its number (for example "Pose 3/29"), and status cards |
| **IMU cards** (bottom left) | Connection status and live readings of the left and right sensors |

### 6.4 Practicing the routine

The routine has **29 poses** taken from an instructor video. For each one:

1. Look at **POSE TO COPY** in the side panel. The picture is shown as a mirror image, so make the camera view of yourself look like the picture.
2. Move into the pose. The **Adjust** banner tells you which joints to fix, for example "Bend your left knee" or "Straighten your right elbow". The status card shows how far off you are on average, for example **"Error 24° (need < 20°)"**.
3. When you are close enough, the card turns green and shows **"Good - hold it... (50%)"**. Hold still. It takes just over half a second.
4. **"Pose matched!"** appears and the next pose is shown automatically.
5. After the last pose, **"Sequence complete!"** appears. Tap **Restart** to go again.

### 6.5 Buttons

| Button | What it does |
| --- | --- |
| **Prev** | Go back one pose |
| **Restart** | Start again from pose 1 |
| **Skip** | Move on to the next pose without matching this one |

### 6.6 Connecting the IMU sensors

1. Turn on **Bluetooth** on the tablet: swipe down from the top and tap the Bluetooth icon.
2. Power on both programmed boards and keep them within a few metres of the tablet.
3. Open the app. The IMU cards connect by themselves within a few seconds. Nothing needs to be tapped or paired.

Each card shows one of these:

| Status | Meaning / what to do |
| --- | --- |
| **Searching for tAIChi-L…** / **tAIChi-R…** | Looking for the board. Check it has power and was programmed with the right side ([section 5](#5-set-up-the-imu-sensors-once-per-board)). |
| **Connecting…** | Board found, connecting |
| **Connected** + numbers | Working. **ACCEL** is acceleration in g: a board lying still shows about 1.00 on one axis, from gravity. **GYRO** is rotation speed in degrees per second, close to 0 when still. |
| **Disconnected, searching…** | The link dropped, for example because the board lost power or moved out of range. The app reconnects by itself. |
| **Bluetooth is off** | Turn Bluetooth on. The card updates within a few seconds. |
| **Bluetooth permission needed** | Allow **Nearby devices**: **Settings → Apps → tAi-Chi → Permissions**. |
| **Not an IMU board** | A different Bluetooth device answered. Re-upload the sketch to the board. |

At the moment the app only **displays** the IMU readings. They are not used for the coaching feedback yet.

### 6.7 Time sync between the sensors

Each board has its own clock, and no two clocks run at exactly the same speed. They drift apart by a few milliseconds per minute. The app corrects for this automatically; there is nothing to set up.

**How it works:** every reading carries the board's time when it was taken. The tablet compares that with the time the reading arrived. The fastest readings over the last 10 seconds show how far apart the two clocks are, and the app uses that to convert every reading to the tablet's clock, the same clock the camera uses. This is re-measured with every reading, so it keeps up with the drift. The board's clock is never changed. The code is in [ClockSync.kt](app/src/main/java/com/example/tai_chi/ClockSync.kt), and its tests in [ClockSyncTest.kt](app/src/test/java/com/example/tai_chi/ClockSyncTest.kt) check that left and right stay within 2 ms of each other.

Each IMU card shows a small sync line:

| Text | Meaning |
| --- | --- |
| **Syncing clock…** | The first 3 seconds after connecting, while the first measurements come in |
| **Clock synced · drift +18 ppm · delay 6 ms · lost 0** | Working. **Drift** is how fast this board's clock runs compared with the tablet's, in parts per million. It shows "measuring…" for the first minute, then usually settles somewhere between -50 and +50. **Delay** is how late the latest reading arrived compared with the fastest one. **Lost** counts readings that never arrived. |
| **No timestamps (old firmware)** | The board runs the old sketch. Upload the current `XIAOinterfaceAppTablet.ino` ([section 5](#5-set-up-the-imu-sensors-once-per-board)). |

A drift far outside ±100 ppm, or a "lost" count that keeps climbing, means something is wrong with the board or the Bluetooth link.

---

## 7. Troubleshooting

**Building and installing**

| Problem | Fix |
| --- | --- |
| `No tablet is connected` | Use a data cable, check that USB debugging is on ([section 3](#3-set-up-the-tablet-once)), unlock the tablet and tap **Allow** on the "Allow USB debugging?" prompt. Run `./tablet.sh devices` to check. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The tablet has an old copy signed with a different key, from before the shared key existed. Run `./tablet.sh uninstall` once, then `./tablet.sh`. |
| `Could not find a full Java JDK` | Install Android Studio ([2.3](#23-install-android-studio)). If it is installed somewhere unusual, set `JAVA_HOME` to its `jbr` folder. |
| `Could not find the Android SDK` / "SDK location not found" | Open Android Studio once so it downloads the SDK. If that doesn't help, delete `tAI-Chi_app/local.properties` and run the command again. The script recreates the file for your computer. |
| `jlink executable … does not exist` | Gradle was started with an incomplete Java, usually the one inside VS Code's Java extension. Build with `tablet.sh` / `tablet.cmd`, which always use Android Studio's Java. |
| Wi-Fi mode: `The tablet is not connected to Wi-Fi` | Join the tablet to Wi-Fi (**Settings → Wi-Fi**), on the same network as the computer. |
| Wi-Fi mode: `Could not connect to …` | The network blocks devices from reaching each other (common on university and guest Wi-Fi). Use the cable or a phone hotspot. |
| Mac: `permission denied: ./tablet.sh` | Run `chmod +x tablet.sh` once, or use `bash tablet.sh`. |

**In the app**

| Problem | Fix |
| --- | --- |
| "Camera access is needed…" | Go to **Settings → Apps → tAi-Chi → Permissions → Camera → Allow**, then close the app fully (swipe it away in the recent-apps view) and open it again. |
| "No person detected" | Step back until your whole body fits on screen. |
| "Can't see enough joints" | Step back, improve the lighting, and wear clothes that contrast with the background. |
| A pose never matches | Use **Skip** to move on. The pose picture is a mirror image: match what you see, not the instructor's left and right. |
| "Camera unavailable" | Another app is using the camera. Close it and reopen tAi-Chi. |
| An IMU card stays on "Searching…" | See the table in [6.6](#66-connecting-the-imu-sensors). Also try turning Bluetooth off and on. |
