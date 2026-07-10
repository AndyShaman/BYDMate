package com.bydmate.app.cluster

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

class BydProjectionBindProbeActivity : Activity() {
    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                data.readInt()
                data.readInt()
                reply?.writeNoException()
                reply?.writeInt(0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MANAGER_DESCRIPTOR)
                data.writeStrongBinder(callback)
                val accepted = service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                if (accepted) reply.readException()
                Log.i(TAG, "register callback transact accepted=$accepted")
            } catch (error: Exception) {
                Log.e(TAG, "register callback failed", error)
            } finally {
                data.recycle()
                reply.recycle()
                unbindService(this)
                finish()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent().setComponent(
            ComponentName(
                "com.example.amapservice",
                "com.byd.cluster.projectionmanager.service.BydProjectionService",
            ),
        )
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bind rejected")
            finish()
        }
    }

    companion object {
        private const val TAG = "BydProjectionProbe"
        private const val MANAGER_DESCRIPTOR =
            "com.byd.cluster.projectionmanager.service.IContentProjectionManager"
        private const val CALLBACK_DESCRIPTOR =
            "com.byd.cluster.projectionmanager.service.IContentProjectionCallback"
    }
}
