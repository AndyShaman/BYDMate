package android.hardware;

/** Compile-only stub of the firmware interface; see bydauto-stubs/build.gradle.kts. */
public interface IBYDAutoEvent {
    byte[] getBufferData();

    Object getData();

    int getDeviceType();

    double getDoubleValue();

    int getEventType();

    int getValue();

    void setData(Object obj);
}
