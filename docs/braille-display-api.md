# Providing a braille display from another app

An app can give Backtalk a braille display that Backtalk can't reach on its own, such as the built-in keys and cells of a braille notetaker, or a display behind its maker's own software. Backtalk then works with it as it does with a display connected through Bluetooth or USB: the keys navigate and type, and the cells show what Backtalk shows.

## What the user controls

Backtalk only uses displays from apps that the user has allowed. An app is allowed when it has Backtalk's braille display permission. The user grants it when the app asks, in Android's own permission dialog, or with adb:

```
adb shell pm grant com.example.display fyi.quin.backtalk.permission.BRAILLE_DISPLAY
```

The user can take it back at any time in the app's permissions in Android's settings. A display's keys can do anything that a braille display's keys can, so Backtalk doesn't limit what an allowed display sends or how quickly.

## Your app's manifest

Ask for the permission, and provide a service that only Backtalk can bind:

```xml
<uses-permission android:name="fyi.quin.backtalk.permission.BRAILLE_DISPLAY" />

<application>
    <service
        android:name=".DisplayService"
        android:exported="true"
        android:permission="fyi.quin.backtalk.permission.BIND_BRAILLE_DISPLAY">
        <intent-filter>
            <action android:name="fyi.quin.backtalk.action.BRAILLE_DISPLAY" />
        </intent-filter>
    </service>
</application>
```

On Android 11 and later, also make Backtalk visible to your app, so that you can ask for the permission:

```xml
<queries>
    <package android:name="fyi.quin.backtalk" />
</queries>
```

Backtalk binds your service while it's running, so your app keeps running while Backtalk does, and Backtalk binds it again if it stops. Backtalk looks for allowed displays when it starts and when an app is installed or updated. Once the user allows your app, tell Backtalk to look again:

```kotlin
context.sendBroadcast(
  Intent("fyi.quin.backtalk.action.BRAILLE_DISPLAYS_CHANGED").setPackage("fyi.quin.backtalk"))
```

## The interface

Copy `braille/brailledisplay/src/phone/aidl/fyi/quin/backtalk/braille/IBrailleDisplay.aidl` and `IBrailleDisplayHost.aidl` into your app, keeping the package `fyi.quin.backtalk.braille`. Your service returns an `IBrailleDisplay`, and Backtalk gives it an `IBrailleDisplayHost` to call:

```kotlin
class DisplayService : Service() {
  private var host: IBrailleDisplayHost? = null

  private val display =
    object : IBrailleDisplay.Stub() {
      override fun onHostConnected(host: IBrailleDisplayHost) {
        this@DisplayService.host = host
        // When your hardware is ready: its name and number of text cells.
        host.start("Example display", 40)
      }

      override fun showCells(cells: ByteArray) {
        // Show cells on the hardware.
      }
    }

  override fun onBind(intent: Intent): IBinder = display
}
```

| `IBrailleDisplayHost` method | What it does |
| --- | --- |
| `start(name, cellCount)` | The display is ready, with this name and number of text cells. Use 0 for a display that only has keys. Call it again if either changes. |
| `stop()` | The display can't be used any more, for example because its hardware went away. Call `start` when it's back. |
| `brailleKeys(dots, space)` | Braille keys pressed together, with bit 0 for dot 1 up to bit 7 for dot 8, and whether the space bar was one of them. They run the same command as on any other braille display, and otherwise type. Send `brailleKeys(0, true)` for the space bar alone. |
| `command(name, argument)` | Runs a braille display command for one of your other keys. `name` is the name of a command in Backtalk's `BrailleInputEvent` without its `CMD_` prefix, such as `NAV_ITEM_NEXT`, `NAV_PAN_DOWN`, `ACTIVATE_CURRENT` or `GLOBAL_BACK`. For `ROUTE`, `argument` is the cell, counting from 0; otherwise it's 0. |

`showCells(cells)` gives your display what to show, one byte per cell, with the same bits as `brailleKeys`.

All of these calls are one-way, so they return at once.

## One display at a time

Backtalk uses one display at a time. If several allowed apps start a display, Backtalk uses the first, and the others wait until it stops. Don't connect a display through Bluetooth or USB while one from an app is in use; they would both drive the same part of Backtalk.
