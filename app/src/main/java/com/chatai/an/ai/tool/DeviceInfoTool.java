package com.chatai.an.ai.tool;

import android.os.Build;

import com.chatai.an.ai.local.DeviceCapabilityChecker;
import com.chatai.an.domain.model.DeviceCompatibility;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

public class DeviceInfoTool implements AiTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "device_info",
            "Reads non-sensitive Android device hardware & RAM compatibility specs.",
            "{\"type\":\"object\",\"properties\":{}}",
            ToolPermission.AUTO_ALLOW
    );

    private final DeviceCapabilityChecker checker;

    public DeviceInfoTool(DeviceCapabilityChecker checker) {
        this.checker = checker;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        DeviceCompatibility c = checker.inspect();
        String summary = "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android API " + Build.VERSION.SDK_INT
                + " | RAM: " + c.getAvailableRamMb() + " MB free / " + c.getTotalRamMb() + " MB total"
                + " | CPU cores: " + c.getCpuCores()
                + " | ABI: " + c.getPrimaryAbi()
                + " (arm64=" + c.isArm64Supported() + ")";
        return new ToolResult(DEF.getName(), true, summary, "");
    }
}
