/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.brailledisplay.platform.external;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.content.ContextCompat;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.SupportedCommand;
import com.google.android.accessibility.braille.brailledisplay.controller.utils.BrailleKeyBindingUtils;
import com.google.android.accessibility.braille.brailledisplay.platform.Controller;
import com.google.android.accessibility.braille.brltty.BrailleInputEvent;
import fyi.quin.backtalk.braille.IBrailleDisplay;
import fyi.quin.backtalk.braille.IBrailleDisplayHost;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Braille displays that other apps provide. Backtalk binds the display service of each app that the
 * user has allowed. One display at a time drives the braille controller, as a display connected
 * through BRLTTY does, and any others that have started wait for it to stop. See
 * docs/braille-display-api.md.
 */
public final class ExternalBrailleDisplays {
  private static final String TAG = "ExternalBrailleDisplays";

  /** The action of a display app's service. */
  public static final String ACTION_BRAILLE_DISPLAY = "fyi.quin.backtalk.action.BRAILLE_DISPLAY";

  /** A display app sends this to Backtalk when it has been allowed, so that Backtalk binds it. */
  public static final String ACTION_BRAILLE_DISPLAYS_CHANGED =
      "fyi.quin.backtalk.action.BRAILLE_DISPLAYS_CHANGED";

  /** The permission the user grants to a display app. */
  public static final String PERMISSION_BRAILLE_DISPLAY =
      "fyi.quin.backtalk.permission.BRAILLE_DISPLAY";

  /** The permission a display app's service requires, which only Backtalk holds. */
  public static final String PERMISSION_BIND_BRAILLE_DISPLAY =
      "fyi.quin.backtalk.permission.BIND_BRAILLE_DISPLAY";

  private static final long UNLOCK_CHECK_MS = 1000;

  private final Context context;
  private final Controller controller;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Map<ComponentName, Connection> connections = new HashMap<>();
  private Connection active;
  private boolean started;
  // Looks again as soon as the user is unlocked; see refresh().
  private final Runnable refreshWhenUnlocked = this::refresh;

  private final BroadcastReceiver displaysChanged =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          String packageName =
              intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
          // Apps change all the time, so look again only when one that can be a display changed.
          if (packageName == null || isDisplayPackage(packageName)) {
            refresh();
          }
        }
      };

  public ExternalBrailleDisplays(Context context, Controller controller) {
    this.context = context;
    this.controller = controller;
  }

  /** Binds the displays of the apps that the user has allowed. */
  public void start() {
    if (started) {
      return;
    }
    started = true;
    IntentFilter packages = new IntentFilter();
    packages.addAction(Intent.ACTION_PACKAGE_ADDED);
    packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
    packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
    packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
    packages.addDataScheme("package");
    ContextCompat.registerReceiver(
        context, displaysChanged, packages, ContextCompat.RECEIVER_NOT_EXPORTED);
    // Apps that can't run until the user is unlocked aren't found before then; see refresh().
    ContextCompat.registerReceiver(
        context,
        displaysChanged,
        new IntentFilter(Intent.ACTION_USER_UNLOCKED),
        ContextCompat.RECEIVER_NOT_EXPORTED);
    // Only an app that holds the permission can send this.
    ContextCompat.registerReceiver(
        context,
        displaysChanged,
        new IntentFilter(ACTION_BRAILLE_DISPLAYS_CHANGED),
        PERMISSION_BRAILLE_DISPLAY,
        /* scheduler= */ null,
        ContextCompat.RECEIVER_EXPORTED);
    refresh();
  }

  /** Unbinds every display. */
  public void stop() {
    if (!started) {
      return;
    }
    started = false;
    mainHandler.removeCallbacks(refreshWhenUnlocked);
    context.unregisterReceiver(displaysChanged);
    for (Connection connection : connections.values()) {
      connection.unbind();
    }
    connections.clear();
    stopActive();
  }

  /**
   * Passes an event on to the controller, so that a display with cells shows what changed. A
   * display connected through BRLTTY gets events already, so while one is, nothing is passed on
   * here.
   */
  public void onAccessibilityEvent(AccessibilityEvent event, boolean brlttyDisplayConnected) {
    if (active != null && active.cellCount > 0 && !brlttyDisplayConnected) {
      controller.onAccessibilityEvent(event);
    }
  }

  /** Binds newly allowed displays, and unbinds those that are no longer allowed or installed. */
  private void refresh() {
    if (!started) {
      return;
    }
    mainHandler.removeCallbacks(refreshWhenUnlocked);
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
      // After a reboot, Backtalk starts before the user is unlocked, and a display app that can't
      // run until then isn't found. The broadcast that says the user is unlocked can come many
      // seconds later, behind the rest of the boot, so if such a display is waiting, check every
      // second.
      if (hasAllowedDisplays(
          PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE)) {
        mainHandler.postDelayed(refreshWhenUnlocked, UNLOCK_CHECK_MS);
      }
    }
    Set<ComponentName> allowed = allowedDisplays(0);
    Iterator<Map.Entry<ComponentName, Connection>> it = connections.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<ComponentName, Connection> entry = it.next();
      if (!allowed.contains(entry.getKey())) {
        stopDisplay(entry.getValue());
        entry.getValue().unbind();
        it.remove();
      }
    }
    for (ComponentName component : allowed) {
      if (!connections.containsKey(component)) {
        Connection connection = new Connection(component);
        if (connection.bind()) {
          connections.put(component, connection);
        }
      }
    }
  }

  private boolean isDisplayPackage(String packageName) {
    for (ComponentName component : connections.keySet()) {
      if (component.getPackageName().equals(packageName)) {
        return true;
      }
    }
    return context.getPackageManager().checkPermission(PERMISSION_BRAILLE_DISPLAY, packageName)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasAllowedDisplays(int flags) {
    return !allowedDisplays(flags).isEmpty();
  }

  private Set<ComponentName> allowedDisplays(int flags) {
    PackageManager packageManager = context.getPackageManager();
    Set<ComponentName> allowed = new HashSet<>();
    for (ResolveInfo resolveInfo :
        packageManager.queryIntentServices(new Intent(ACTION_BRAILLE_DISPLAY), flags)) {
      ServiceInfo service = resolveInfo.serviceInfo;
      // The service must be one that only Backtalk can bind, as an accessibility service must be
      // one that only Android can bind.
      if (PERMISSION_BIND_BRAILLE_DISPLAY.equals(service.permission)
          && packageManager.checkPermission(PERMISSION_BRAILLE_DISPLAY, service.packageName)
              == PackageManager.PERMISSION_GRANTED) {
        allowed.add(new ComponentName(service.packageName, service.name));
      }
    }
    return allowed;
  }

  private void startDisplay(Connection connection, String name, int cellCount) {
    connection.name = name;
    connection.cellCount = Math.max(cellCount, 0);
    connection.waiting = true;
    if (active == connection) {
      controller.onStop();
      active = null;
    }
    if (active == null) {
      activate(connection);
    }
  }

  private void stopDisplay(Connection connection) {
    connection.waiting = false;
    if (active == connection) {
      stopActive();
      for (Connection waiting : connections.values()) {
        if (waiting.waiting && waiting.display != null) {
          activate(waiting);
          break;
        }
      }
    }
  }

  private void activate(Connection connection) {
    active = connection;
    controller.onStart(
        new ExternalDisplayer(connection.name, connection.cellCount, connection.display));
  }

  private void stopActive() {
    if (active != null) {
      active = null;
      controller.onStop();
    }
  }

  private void input(Connection connection, BrailleInputEvent event) {
    if (active == connection && event != null) {
      controller.onBrailleInputEvent(event);
    }
  }

  /** Braille keys run the command they are bound to, as on any display, or else type. */
  private BrailleInputEvent brailleKeysEvent(int dots, boolean space) {
    long time = SystemClock.uptimeMillis();
    if (dots == 0) {
      return space ? new BrailleInputEvent(BrailleInputEvent.CMD_BRAILLE_KEY, 0, time) : null;
    }
    SupportedCommand command = BrailleKeyBindingUtils.convertToCommand(context, space, dots);
    if (command != null) {
      return new BrailleInputEvent(command.getCommand(), 0, time);
    }
    return space ? null : new BrailleInputEvent(BrailleInputEvent.CMD_BRAILLE_KEY, dots, time);
  }

  private static BrailleInputEvent commandEvent(String name, int argument) {
    Integer command = Commands.CODES.get(name);
    if (command == null) {
      BrailleDisplayLog.w(TAG, "unknown command " + name);
      return null;
    }
    return new BrailleInputEvent(command, argument, SystemClock.uptimeMillis());
  }

  /** The command codes of {@link BrailleInputEvent}, by name without the CMD_ prefix. */
  private static final class Commands {
    static final Map<String, Integer> CODES = new HashMap<>();

    static {
      for (Field field : BrailleInputEvent.class.getFields()) {
        int modifiers = field.getModifiers();
        if (field.getName().startsWith("CMD_")
            && field.getType() == int.class
            && Modifier.isStatic(modifiers)) {
          try {
            CODES.put(field.getName().substring(4), field.getInt(null));
          } catch (IllegalAccessException e) {
            // Public constants are always readable.
          }
        }
      }
    }
  }

  /** The binding to one display app's service. */
  private final class Connection implements ServiceConnection {
    final ComponentName component;
    IBrailleDisplay display;
    String name;
    int cellCount;
    // Started by its app, whether or not it is the display in use.
    boolean waiting;
    private boolean bound;

    Connection(ComponentName component) {
      this.component = component;
    }

    boolean bind() {
      Intent intent = new Intent(ACTION_BRAILLE_DISPLAY).setComponent(component);
      bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
      if (!bound) {
        BrailleDisplayLog.w(TAG, "couldn't bind " + component.flattenToShortString());
      }
      return bound;
    }

    void unbind() {
      if (bound) {
        bound = false;
        context.unbindService(this);
      }
      display = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
      // Taking the permission back stops the app, but Android starts its service again for this
      // binding, so check again.
      if (context
              .getPackageManager()
              .checkPermission(PERMISSION_BRAILLE_DISPLAY, component.getPackageName())
          != PackageManager.PERMISSION_GRANTED) {
        unbind();
        connections.remove(component);
        return;
      }
      display = IBrailleDisplay.Stub.asInterface(binder);
      try {
        display.onHostConnected(new Host(this));
      } catch (RemoteException e) {
        BrailleDisplayLog.w(TAG, "couldn't connect " + name.flattenToShortString(), e);
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      // The app stopped; Android binds it again when it can.
      display = null;
      stopDisplay(this);
    }

    @Override
    public void onBindingDied(ComponentName name) {
      // The app was updated or force stopped, and this binding won't come back.
      stopDisplay(this);
      unbind();
      connections.remove(component);
      refresh();
    }
  }

  /** What a display app calls. Calls from a display that has since been unbound are ignored. */
  private final class Host extends IBrailleDisplayHost.Stub {
    private final Connection connection;

    Host(Connection connection) {
      this.connection = connection;
    }

    private void post(Runnable runnable) {
      mainHandler.post(
          () -> {
            if (connection.bound && connections.get(connection.component) == connection) {
              runnable.run();
            }
          });
    }

    @Override
    public void start(String name, int cellCount) {
      post(() -> startDisplay(connection, name == null ? "" : name, cellCount));
    }

    @Override
    public void stop() {
      post(() -> stopDisplay(connection));
    }

    @Override
    public void brailleKeys(int dots, boolean space) {
      post(() -> input(connection, brailleKeysEvent(dots & 0xFF, space)));
    }

    @Override
    public void command(String name, int argument) {
      post(() -> input(connection, commandEvent(name, argument)));
    }
  }
}
