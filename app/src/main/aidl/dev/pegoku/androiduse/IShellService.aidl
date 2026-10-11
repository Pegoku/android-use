package dev.pegoku.androiduse;

interface IShellService {
    // Reserved by Shizuku for tearing down the user service.
    void destroy() = 16777114;

    // Runs `sh -c command` as the shell user. Returns "<exitCode>\n<stdout+stderr>", output truncated.
    String run(String command, long timeoutMs) = 1;

    // Runs `sh -c command` and streams its raw stdout through the returned pipe.
    ParcelFileDescriptor stream(String command) = 2;
}
