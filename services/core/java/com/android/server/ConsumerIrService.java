/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.IConsumerIrService;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.IControl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ConsumerIrService extends IConsumerIrService.Stub {
    private static final String TAG = "ConsumerIrService";

    private static final int MAX_XMIT_TIME = 2000000; /* in microseconds */

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final Object mHalLock = new Object();

    private final static int[] CONSUMERIR_CARRIER_FREQUENCIES = {30000, 30000, 33000, 33000, 36000, 36000, 38000, 38000, 40000, 40000, 56000, 56000};
    private final static String SYS_FILE_ENABLE_IR_BLASTER = "/sys/remote/enable";

    private boolean mBound = false;
    private IControl mControl;

    ConsumerIrService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        bindQuickSetService();
    }

    @Override
    public boolean hasIrEmitter() {
        return true;
    }

    @Override
    public void transmit(String packageName, int carrierFrequency, int[] pattern) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.TRANSMIT_IR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires TRANSMIT_IR permission");
        }

        long totalXmitTime = 0;

        for (int slice : pattern) {
            if (slice <= 0) {
                throw new IllegalArgumentException("Non-positive IR slice");
            }
            totalXmitTime += slice;
        }

        if (totalXmitTime > MAX_XMIT_TIME) {
            throw new IllegalArgumentException("IR pattern too long");
        }

        // Right now there is no mechanism to ensure fair queing of IR requests
        synchronized (mHalLock) {
            int err = -1;
            if (mControl == null || !mBound) {
                bindQuickSetService();
                return;
            }

            // Enable IR device
            if (!writeToSysFile("1")) {
                return;
            }


            try {
                mControl.transmit(carrierFrequency, pattern);
                err = mControl.getLastResultcode();
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            if (err < 0) {
                Slog.e(TAG, "Error transmitting: " + err);
            }

            // Disable IR device
            writeToSysFile("0");
        }
    }

    @Override
    public int[] getCarrierFrequencies() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.TRANSMIT_IR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires TRANSMIT_IR permission");
        }

        synchronized (mHalLock) {
            return CONSUMERIR_CARRIER_FREQUENCIES;
        }
    }

    private static boolean writeToSysFile(String value) {
        File file = new File(SYS_FILE_ENABLE_IR_BLASTER);
        if (!file.exists() || !file.isFile() || !file.canWrite()) {
            return false;
        }
        try {
            FileWriter fileWriter = new FileWriter(file);
            fileWriter.write(value);
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Service Connection used to control the bound QuickSet SDK Service
     */
    private ServiceConnection mControlServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBound = true;
            mControl = new IControl(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mControl = null;
        }
    };

    /**
     * Try to bind QuickSet SDK Service
     */
    public void bindQuickSetService() {
        Intent controlIntent = new Intent(IControl.ACTION);
        controlIntent.setClassName(IControl.QUICKSET_UEI_PACKAGE_NAME, IControl.QUICKSET_UEI_SERVICE_CLASS);
        mContext.bindService(controlIntent, mControlServiceConnection, Context.BIND_AUTO_CREATE);
    }
}
