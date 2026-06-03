package com.bydmate.probe;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

// Phase 2b probe. Calls autoservice Binder transact directly from app_process
// (shell uid). Tests whether tx=6 (setInt) and tx=10 (setIntArray) are
// reachable on Leopard 3.
//
// Usage:
//   app_process -cp /data/local/tmp/probe.dex /system/bin \
//     com.bydmate.probe.WriteProbe <tx> <dev> <fid> [value]
//   app_process -cp /data/local/tmp/probe.dex /system/bin \
//     com.bydmate.probe.WriteProbe 10 <dev> <N> <fid1>..<fidN> <val1>..<valN>
//
// tx=5  -> getInt (no value arg). Prints "value=N".
// tx=6  -> setInt (value arg required). Prints "value=N".
// tx=7  -> getFloat (no value arg). Prints "value_float=F".
// tx=10 -> setIntArray. Layout per competitor source:
//          writeInt(dev), writeInt(N),
//          N x writeInt(fid_i), then N x writeInt(val_i).

public class WriteProbe {
    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.err.println("usage: WriteProbe <tx> <dev> <fid> [value]");
                System.err.println("       WriteProbe 10 <dev> <N> <fid1..N> <val1..N>");
                System.exit(2);
            }
            int tx = Integer.parseInt(args[0]);
            int dev = Integer.parseInt(args[1]);

            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder svc = (IBinder) getService.invoke(null, "autoservice");
            if (svc == null) {
                System.err.println("ERR autoservice not found");
                System.exit(3);
            }

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                String iface = svc.getInterfaceDescriptor();
                data.writeInterfaceToken(iface);
                data.writeInt(dev);

                StringBuilder argDump = new StringBuilder();
                if (tx == 10) {
                    int n = Integer.parseInt(args[2]);
                    if (args.length != 3 + 2 * n) {
                        System.err.println("ERR tx=10 expects " + (3 + 2 * n) + " args, got " + args.length);
                        System.exit(2);
                    }
                    int[] fids = new int[n];
                    int[] vals = new int[n];
                    for (int i = 0; i < n; i++) fids[i] = Integer.parseInt(args[3 + i]);
                    for (int i = 0; i < n; i++) vals[i] = Integer.parseInt(args[3 + n + i]);
                    data.writeInt(n);
                    for (int f : fids) data.writeInt(f);
                    for (int v : vals) data.writeInt(v);
                    argDump.append(" n=").append(n).append(" fids=[");
                    for (int i = 0; i < n; i++) argDump.append(fids[i]).append(i == n - 1 ? "" : ",");
                    argDump.append("] vals=[");
                    for (int i = 0; i < n; i++) argDump.append(vals[i]).append(i == n - 1 ? "" : ",");
                    argDump.append("]");
                } else {
                    int fid = Integer.parseInt(args[2]);
                    int val = (args.length > 3) ? Integer.parseInt(args[3]) : 0;
                    data.writeInt(fid);
                    if (tx == 6) data.writeInt(val);
                    argDump.append(" fid=").append(fid);
                    if (tx == 6) argDump.append(" wrote=").append(val);
                }

                boolean ok = svc.transact(tx, data, reply, 0);
                int dataAvail = reply.dataAvail();
                int status = -999;
                int retInt = -999;
                float retFloat = Float.NaN;
                if (dataAvail >= 4) status = reply.readInt();
                if (dataAvail >= 8) {
                    int pos = reply.dataPosition();
                    retInt = reply.readInt();
                    if (tx == 7) {
                        reply.setDataPosition(pos);
                        retFloat = reply.readFloat();
                    }
                }

                StringBuilder sb = new StringBuilder();
                sb.append("RESULT tx=").append(tx)
                  .append(" iface=\"").append(iface).append("\"")
                  .append(" dev=").append(dev)
                  .append(argDump)
                  .append(" ok=").append(ok)
                  .append(" data_avail=").append(dataAvail)
                  .append(" status=").append(status)
                  .append(" value=").append(retInt);
                if (tx == 7) sb.append(" value_float=").append(retFloat);
                System.out.println(sb.toString());
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            System.err.println("EXC " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(4);
        }
    }
}
