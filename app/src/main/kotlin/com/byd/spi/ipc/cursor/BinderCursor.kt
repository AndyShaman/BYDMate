package com.byd.spi.ipc.cursor

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep

/**
 * Stand-in for the firmware's own binder-over-cursor wrapper (DiCarServer, `com.byd.spi.ipc`).
 *
 * `CarServiceProvider/sync_binder` answers with a Cursor whose extras carry the service binder as a
 * parcelable of class `com.byd.spi.ipc.cursor.BinderCursor$BinderParcelable`. Unparcelling resolves
 * that class BY NAME in OUR classloader, and the DiCar SPI jar is not on our classpath, so the only
 * way to read the binder is to declare a class with exactly that name and a matching wire format
 * (a single strong binder). Hence the foreign package: it is the wire contract, not our code layout.
 *
 * [Keep] on both classes: the release build runs R8, and a renamed class breaks the by-name lookup.
 */
@Keep
class BinderCursor {

    @Keep
    class BinderParcelable(val binder: IBinder?) : Parcelable {

        override fun describeContents(): Int = 0

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeStrongBinder(binder)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<BinderParcelable> =
                object : Parcelable.Creator<BinderParcelable> {
                    override fun createFromParcel(source: Parcel): BinderParcelable =
                        BinderParcelable(source.readStrongBinder())

                    override fun newArray(size: Int): Array<BinderParcelable?> = arrayOfNulls(size)
                }
        }
    }
}
