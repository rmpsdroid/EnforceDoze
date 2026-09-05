package com.akylas.enforcedoze;

import android.os.Bundle;

interface IShizukuCommandService {
    Bundle execute(String command) = 1;

    // Reserved by Shizuku for UserService destruction.
    void destroy() = 16777114;
}
