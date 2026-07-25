package com.bydmate.app.cluster

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Hosts the projection sink on DiLink builds that hide the cluster display from app DisplayManager. */
class ClusterAnchorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val anchorView = SurfaceView(this).apply {
            holder.setFixedSize(1920, 720)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    ClusterProjectionManager.registerClusterSurface(this@ClusterAnchorActivity, holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    ClusterProjectionManager.unregisterClusterSurface(holder.surface)
                }
            })
        }
        setContentView(anchorView)
        listOf(50L, 150L, 400L, 800L).forEach { delay ->
            anchorView.postDelayed({ ClusterProjectionManager.registerClusterAnchor(this) }, delay)
        }
    }

    override fun onResume() {
        super.onResume()
        ClusterProjectionManager.registerClusterAnchor(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClusterProjectionManager.registerClusterAnchor(this)
    }

    override fun onDestroy() {
        ClusterProjectionManager.unregisterClusterAnchor(this)
        super.onDestroy()
    }
}
