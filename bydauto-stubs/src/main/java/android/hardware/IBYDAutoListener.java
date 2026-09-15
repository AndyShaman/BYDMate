package android.hardware;

import android.hardware.bydauto.BYDAutoEventValue;

/** Compile-only stub of the firmware interface; see bydauto-stubs/build.gradle.kts. */
public interface IBYDAutoListener {
    void onDataChanged(IBYDAutoEvent event);

    void onDataEventChanged(int eventType, BYDAutoEventValue eventValue);

    void onError(int errCode, String errMessage);
}
