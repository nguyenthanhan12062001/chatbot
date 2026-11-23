package com.chatai.an.ai.local;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import com.chatai.an.domain.model.DeviceCompatibility;

public class DeviceCapabilityChecker {
    private final Context context;

    public DeviceCapabilityChecker(Context context) {
        this.context = context.getApplicationContext();
    }

    public DeviceCompatibility inspect() {
        long totalRamMb = 4096L;
        long availRamMb = 1536L;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalRamMb = Math.max(512L, mi.totalMem / (1024L * 1024L));
            availRamMb = Math.max(128L, mi.availMem / (1024L * 1024L));
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        String[] abis = Build.SUPPORTED_ABIS;
        String primaryAbi = (abis != null && abis.length > 0) ? abis[0] : "unknown";
        boolean arm64 = false;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equalsIgnoreCase(abi)) {
                    arm64 = true;
                    break;
                }
            }
        }
        return new DeviceCompatibility(totalRamMb, availRamMb, cores, primaryAbi, arm64);
    }
}
