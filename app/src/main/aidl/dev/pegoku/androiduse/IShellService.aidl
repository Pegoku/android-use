package dev.pegoku.androiduse;

interface IShellService {
    // Reserved by Shizuku for tearing down the user service.
    void destroy() = 16777114;

    // Runs `sh -c command` as the shell user. Returns "<exitCode>\n<stdout+stderr>", output truncated.
    String run(String command, long timeoutMs) = 1;

    // Runs `sh -c command` and streams its raw stdout through the returned pipe.
    ParcelFileDescriptor stream(String command) = 2;

    // Active-window hierarchy as uiautomator-style XML, or null if UiAutomation is unavailable.
    ParcelFileDescriptor dumpUi() = 3;

    // Waits until no accessibility events arrive for idleMs (bounded by timeoutMs). False on timeout or failure.
    boolean waitForIdle(long idleMs, long timeoutMs) = 4;

    // Injects a tap, or a long press when durationMs > 0. False when injection is unavailable.
    boolean tap(int x, int y, long durationMs) = 5;

    boolean swipe(int x1, int y1, int x2, int y2, long durationMs) = 6;

    boolean key(int keyCode) = 7;

    // Sets (or appends to) the text of the focused editable node. False if there is none or it refused.
    boolean setText(String text, boolean append) = 8;
}
