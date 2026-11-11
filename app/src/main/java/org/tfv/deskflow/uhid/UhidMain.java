package org.tfv.deskflow.uhid;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Helper process that owns a virtual HID keyboard on /dev/uhid.
 *
 * <p>Runs as the shell user (launched through Shizuku, or {@code adb shell}):
 *
 * <pre>CLASSPATH=&lt;base.apk&gt; app_process / org.tfv.deskflow.uhid.UhidMain</pre>
 *
 * <p>This is a plain app_process main(): no Application or LoadedApk is created, so OEM framework
 * hooks in LoadedApk.makeApplication() (which break Shizuku UserServices on MediaTek builds) are
 * never reached. Written in Java with no dependencies so it does not need the Kotlin runtime or
 * any app class.
 *
 * <p>Protocol on stdin, repeated: {@code [type:u8][len:u8][payload:len bytes]}
 *
 * <ul>
 *   <li>type 1 = keyboard input report (8 bytes: modifiers, reserved, 6 key usages)
 * </ul>
 *
 * EOF on stdin destroys the device and exits, so the keyboard disappears with the app.
 *
 * <p>{@code --demo} types a short test string instead of reading stdin.
 */
public final class UhidMain {
  private static final String TAG = "DeskflowUhid";

  // linux/uhid.h
  private static final int UHID_DESTROY = 1;
  private static final int UHID_START = 2;
  private static final int UHID_STOP = 3;
  private static final int UHID_OPEN = 4;
  private static final int UHID_CLOSE = 5;
  private static final int UHID_OUTPUT = 6;
  private static final int UHID_GET_REPORT = 9;
  private static final int UHID_CREATE2 = 11;
  private static final int UHID_INPUT2 = 12;
  private static final int UHID_SET_REPORT = 13;
  private static final int BUS_VIRTUAL = 0x06;

  public static final String DEVICE_NAME = "Deskflow Keyboard";

  /**
   * pid.codes test VID/PID. A non-zero ID plus a unique id keep Android from
   * deriving the same input device descriptor as some other virtual device
   * with zero IDs, in which case it merges both into one InputDevice (seen
   * on Lenovo, where ours became part of the internal "uinput_nav").
   */
  public static final int VENDOR_ID = 0x1209;
  public static final int PRODUCT_ID = 0x0001;
  private static final String PHYS = "deskflow/uhid0";
  private static final String UNIQ = "deskflow-uhid-keyboard";
  public static final int MSG_KEYBOARD_REPORT = 1;
  public static final int KEYBOARD_REPORT_SIZE = 8;

  /** Boot-protocol keyboard: modifiers, reserved, 5 LEDs (output), 6 key slots. */
  private static final int[] KEYBOARD_REPORT_DESC = {
    0x05, 0x01, // Usage Page (Generic Desktop)
    0x09, 0x06, // Usage (Keyboard)
    0xA1, 0x01, // Collection (Application)
    0x05, 0x07, //   Usage Page (Key Codes)
    0x19, 0xE0, //   Usage Minimum (224)
    0x29, 0xE7, //   Usage Maximum (231)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x01, //   Logical Maximum (1)
    0x75, 0x01, //   Report Size (1)
    0x95, 0x08, //   Report Count (8)
    0x81, 0x02, //   Input (Data, Variable, Absolute): modifiers
    0x75, 0x08, //   Report Size (8)
    0x95, 0x01, //   Report Count (1)
    0x81, 0x01, //   Input (Constant): reserved
    0x05, 0x08, //   Usage Page (LEDs)
    0x19, 0x01, //   Usage Minimum (1)
    0x29, 0x05, //   Usage Maximum (5)
    0x75, 0x01, //   Report Size (1)
    0x95, 0x05, //   Report Count (5)
    0x91, 0x02, //   Output (Data, Variable, Absolute): LEDs
    0x75, 0x03, //   Report Size (3)
    0x95, 0x01, //   Report Count (1)
    0x91, 0x01, //   Output (Constant): padding
    0x05, 0x07, //   Usage Page (Key Codes)
    0x19, 0x00, //   Usage Minimum (0)
    0x29, 0xFF, //   Usage Maximum (255)
    0x15, 0x00, //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08, //   Report Size (8)
    0x95, 0x06, //   Report Count (6)
    0x81, 0x00, //   Input (Data, Array): keys
    0xC0, // End Collection
  };

  private UhidMain() {}

  public static void main(String[] args) {
    boolean demo = false;
    long demoDelayMs = 1500;
    for (String arg : args) {
      if (arg.equals("--demo")) demo = true;
      else if (arg.startsWith("--demo-delay=")) {
        demo = true;
        demoDelayMs = Long.parseLong(arg.substring("--demo-delay=".length()));
      }
    }
    FileDescriptor fd;
    try {
      fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
    } catch (Throwable err) {
      log("open /dev/uhid failed: " + err);
      System.exit(2);
      return;
    }
    try {
      write(fd, create2(DEVICE_NAME));
      log("UHID keyboard created (uid=" + Os.getuid() + ")");
      startOutputReader(fd);
      if (demo) runDemo(fd, demoDelayMs);
      else pump(fd);
    } catch (Throwable err) {
      log("fatal: " + err);
    } finally {
      try {
        write(fd, header(UHID_DESTROY, 0).array());
      } catch (Throwable ignored) {
      }
      try {
        Os.close(fd);
      } catch (Throwable ignored) {
      }
      log("UHID keyboard destroyed");
    }
    System.exit(0);
  }

  private static void pump(FileDescriptor fd) throws Exception {
    DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
    byte[] payload = new byte[255];
    while (true) {
      int type;
      try {
        type = input.readUnsignedByte();
      } catch (EOFException e) {
        log("stdin closed");
        return;
      }
      int len = input.readUnsignedByte();
      input.readFully(payload, 0, len);
      if (type == MSG_KEYBOARD_REPORT) {
        write(fd, input2(payload, len));
      } else {
        log("unknown message type " + type);
      }
    }
  }

  /** Types "hello " + the US apostrophe and semicolon keys (ä and ö on a Finnish layout). */
  private static void runDemo(FileDescriptor fd, long delayMs) throws Exception {
    // Give InputReader time to add the device (and the user time to focus a field).
    Thread.sleep(delayMs);
    int[] usages = {0x0B, 0x08, 0x0F, 0x0F, 0x12, 0x2C, 0x34, 0x33};
    for (int usage : usages) {
      byte[] down = new byte[KEYBOARD_REPORT_SIZE];
      down[2] = (byte) usage;
      write(fd, input2(down, down.length));
      Thread.sleep(30);
      byte[] up = new byte[KEYBOARD_REPORT_SIZE];
      write(fd, input2(up, up.length));
      Thread.sleep(30);
    }
    Thread.sleep(500);
  }

  /** Drain kernel-to-userspace events so the queue never fills; logs them for debugging. */
  private static void startOutputReader(FileDescriptor fd) {
    Thread t =
        new Thread(
            () -> {
              byte[] buf = new byte[4380];
              try {
                while (true) {
                  int n = Os.read(fd, buf, 0, buf.length);
                  if (n <= 0) return;
                  int type = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                  switch (type) {
                    case UHID_START:
                    case UHID_STOP:
                    case UHID_OPEN:
                    case UHID_CLOSE:
                      log("uhid event " + type);
                      break;
                    case UHID_OUTPUT:
                      break; // LED state; ignored for now
                    case UHID_GET_REPORT:
                    case UHID_SET_REPORT:
                      log("uhid report request " + type + " (unanswered)");
                      break;
                    default:
                      break;
                  }
                }
              } catch (Throwable ignored) {
              }
            },
            "uhid-reader");
    t.setDaemon(true);
    t.start();
  }

  private static ByteBuffer header(int type, int extra) {
    return ByteBuffer.allocate(4 + extra).order(ByteOrder.LITTLE_ENDIAN).putInt(type);
  }

  private static byte[] create2(String name) {
    byte[] desc = new byte[KEYBOARD_REPORT_DESC.length];
    for (int i = 0; i < desc.length; i++) desc[i] = (byte) KEYBOARD_REPORT_DESC[i];
    // name[128] phys[64] uniq[64] rd_size:u16 bus:u16 vendor product version country:u32 rd_data
    ByteBuffer buf = header(UHID_CREATE2, 256 + 2 + 2 + 16 + desc.length);
    buf.put(Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8), 128)); // NUL-padded
    buf.put(Arrays.copyOf(PHYS.getBytes(StandardCharsets.UTF_8), 64));
    buf.put(Arrays.copyOf(UNIQ.getBytes(StandardCharsets.UTF_8), 64));
    buf.putShort((short) desc.length);
    buf.putShort((short) BUS_VIRTUAL);
    buf.putInt(VENDOR_ID);
    buf.putInt(PRODUCT_ID);
    buf.putInt(1); // version
    buf.putInt(0); // country
    buf.put(desc);
    return buf.array();
  }

  private static byte[] input2(byte[] data, int len) {
    return header(UHID_INPUT2, 2 + len).putShort((short) len).put(data, 0, len).array();
  }

  /** One uhid_event per write() call, as the kernel requires. */
  private static void write(FileDescriptor fd, byte[] bytes) throws Exception {
    int n = Os.write(fd, bytes, 0, bytes.length);
    if (n != bytes.length) throw new IllegalStateException("short write: " + n + "/" + bytes.length);
  }

  private static void log(String msg) {
    System.err.println(TAG + ": " + msg);
    try {
      Log.i(TAG, msg);
    } catch (Throwable ignored) {
    }
  }
}
