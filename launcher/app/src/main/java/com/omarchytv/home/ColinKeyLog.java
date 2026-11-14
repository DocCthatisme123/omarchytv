package com.omarchytv.home;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Colin's fork: logs every key press so remote buttons can be mapped. Read with: adb logcat -s OmarchyKey */
final class ColinKeyLog {
    private ColinKeyLog() {}

    static void log(String where, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        InputDevice d = e.getDevice();
        String dev = d == null ? "?" : String.format("%s %04x:%04x", d.getName(), d.getVendorId(), d.getProductId());
        Log.i("OmarchyKey", where + " " + KeyEvent.keyCodeToString(e.getKeyCode()) + " code=" + e.getKeyCode()
                + " scan=" + e.getScanCode() + " dev=" + dev);
    }
}
