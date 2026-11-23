package com.chatai.an.ai.integration.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    void destroy() = 16777114;
    int getUid() = 1;
    Bundle executeShell(String command, String workdir, int timeoutSeconds) = 2;
}
