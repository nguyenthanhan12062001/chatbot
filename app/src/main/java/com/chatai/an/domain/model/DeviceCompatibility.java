package com.chatai.an.domain.model;

public class DeviceCompatibility {
    private final long totalRamMb;
    private final long availableRamMb;
    private final int cpuCores;
    private final String primaryAbi;
    private final boolean arm64Supported;

    public DeviceCompatibility(
            long totalRamMb,
            long availableRamMb,
            int cpuCores,
            String primaryAbi,
            boolean arm64Supported
    ) {
        this.totalRamMb = totalRamMb;
        this.availableRamMb = availableRamMb;
        this.cpuCores = cpuCores;
        this.primaryAbi = primaryAbi;
        this.arm64Supported = arm64Supported;
    }

    public long getTotalRamMb() {
        return totalRamMb;
    }

    public long getAvailableRamMb() {
        return availableRamMb;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public String getPrimaryAbi() {
        return primaryAbi;
    }

    public boolean isArm64Supported() {
        return arm64Supported;
    }

    public boolean canFitModelMb(long estimatedRamRequiredMb) {
        return availableRamMb >= estimatedRamRequiredMb || totalRamMb >= (estimatedRamRequiredMb + 1500);
    }
}
