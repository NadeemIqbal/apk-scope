import Java from 'frida-java-bridge';

// APK Scope: Live HTTPS/WSS Traffic Monitor via BoringSSL
// Hooks plaintext I/O, bypasses SSL pinning, streams to ServerSocket on 127.0.0.1:9999
// Protocol: JSON-lines over TCP to 127.0.0.1:9999

(function() {
    'use strict';

    var TAG = "FridaLiveCapture";
    var MONITOR_PORT = 9999;
    var MONITOR_HOST = "127.0.0.1";

    // --- Robust Symbol Lookup Helper (Compatible with all Frida/GumJS runtimes) ---
    function findExport(moduleName, exportName) {
        if (exportName === undefined) {
            exportName = moduleName;
            moduleName = null;
        }

        // 1. Try specific module via Process.findModuleByName
        if (moduleName) {
            try {
                if (typeof Process.findModuleByName === 'function') {
                    var mod = Process.findModuleByName(moduleName);
                    if (mod && typeof mod.findExportByName === 'function') {
                        var addr = mod.findExportByName(exportName);
                        if (addr) return addr;
                    }
                }
            } catch (e) {}

            try {
                if (typeof Module.findExportByName === 'function') {
                    var addr = Module.findExportByName(moduleName, exportName);
                    if (addr) return addr;
                }
            } catch (e) {}
        }

        // 2. Try global export lookup via Module.findGlobalExportByName
        try {
            if (typeof Module.findGlobalExportByName === 'function') {
                var addr = Module.findGlobalExportByName(exportName);
                if (addr) return addr;
            }
        } catch (e) {}

        try {
            if (typeof Module.findExportByName === 'function') {
                var addr = Module.findExportByName(null, exportName);
                if (addr) return addr;
            }
        } catch (e) {}

        // 3. Fallback: Enumerate loaded modules
        try {
            if (typeof Process.enumerateModules === 'function') {
                var modules = Process.enumerateModules();
                for (var i = 0; i < modules.length; i++) {
                    var m = modules[i];
                    if (!moduleName || m.name === moduleName || m.name.indexOf(moduleName) !== -1 || m.path.indexOf(moduleName) !== -1) {
                        if (typeof m.findExportByName === 'function') {
                            var addr = m.findExportByName(exportName);
                            if (addr) return addr;
                        }
                    }
                }
            }
        } catch (e) {}

        return null;
    }

    // --- Native Logging via __android_log_write (Direct to Android logcat) ---
    var logWriteAddr = findExport("liblog.so", "__android_log_write");
    var logWriteFn = null;
    var tagPtr = null;

    if (logWriteAddr) {
        try {
            logWriteFn = new NativeFunction(logWriteAddr, 'int', ['int', 'pointer', 'pointer']);
            tagPtr = Memory.allocUtf8String(TAG);
        } catch (e) {}
    }

    var nativeLog = function(msg) {
        if (logWriteFn && tagPtr) {
            try {
                var textPtr = Memory.allocUtf8String(String(msg));
                logWriteFn(4, tagPtr, textPtr); // 4 = ANDROID_LOG_INFO
            } catch (e) {}
        }
        console.log("[" + TAG + "] " + msg);
    };

    nativeLog("=== Frida Live Capture starting in PID " + Process.id + " ===");

    try {
        // --- Pure JavaScript Base64 Encoder (Zero Java dependencies, thread-safe) ---
        var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        function toBase64(bytes) {
            var result = "";
            var len = bytes.length;
            var i = 0;
            for (i = 0; i < len - 2; i += 3) {
                var b0 = bytes[i];
                var b1 = bytes[i + 1];
                var b2 = bytes[i + 2];
                result += B64.charAt(b0 >> 2);
                result += B64.charAt(((b0 & 3) << 4) | (b1 >> 4));
                result += B64.charAt(((b1 & 15) << 2) | (b2 >> 6));
                result += B64.charAt(b2 & 63);
            }
            if (i < len) {
                var b0 = bytes[i];
                var b1 = (i + 1 < len) ? bytes[i + 1] : 0;
                result += B64.charAt(b0 >> 2);
                result += B64.charAt(((b0 & 3) << 4) | (b1 >> 4));
                if (i + 1 < len) {
                    result += B64.charAt((b1 & 15) << 2);
                    result += "=";
                } else {
                    result += "==";
                }
            }
            return result;
        }

        // --- POSIX Sockets via libc ---
        var AF_INET = 2;
        var SOCK_STREAM = 1;

        var pSocket = findExport("libc.so", "socket");
        var pConnect = findExport("libc.so", "connect");
        var pSend = findExport("libc.so", "send");
        var pRecv = findExport("libc.so", "recv");
        var pClose = findExport("libc.so", "close");
        var pOpen = findExport("libc.so", "open");
        var pRead = findExport("libc.so", "read");
        var pGetuid = findExport("libc.so", "getuid");

        nativeLog("POSIX symbols: socket=" + pSocket + ", connect=" + pConnect + ", send=" + pSend + ", recv=" + pRecv + ", close=" + pClose);

        var socket_fn = pSocket ? new NativeFunction(pSocket, 'int', ['int', 'int', 'int']) : null;
        var connect_fn = pConnect ? new NativeFunction(pConnect, 'int', ['int', 'pointer', 'int']) : null;
        var send_fn = pSend ? new NativeFunction(pSend, 'int', ['int', 'pointer', 'int', 'int']) : null;
        var recv_fn = pRecv ? new NativeFunction(pRecv, 'int', ['int', 'pointer', 'int', 'int']) : null;
        var close_fn = pClose ? new NativeFunction(pClose, 'int', ['int']) : null;
        var open_fn = pOpen ? new NativeFunction(pOpen, 'int', ['pointer', 'int']) : null;
        var read_fn = pRead ? new NativeFunction(pRead, 'long', ['int', 'pointer', 'long']) : null;
        var getuid_fn = pGetuid ? new NativeFunction(pGetuid, 'uint', []) : null;

        // --- Package Name Resolution ---
        var packageName = "unknown";

        try {
            if (open_fn && read_fn && close_fn) {
                var pCmdPath = Memory.allocUtf8String("/proc/self/cmdline");
                var fdCmd = open_fn(pCmdPath, 0); // O_RDONLY = 0
                if (fdCmd >= 0) {
                    var bufCmd = Memory.alloc(256);
                    var nRead = read_fn(fdCmd, bufCmd, 255);
                    close_fn(fdCmd);
                    if (nRead > 0) {
                        packageName = bufCmd.readCString();
                        var colonIdx = packageName.indexOf(":");
                        if (colonIdx !== -1) packageName = packageName.substring(0, colonIdx);
                        packageName = packageName.trim();
                        nativeLog("Resolved package from /proc/self/cmdline: " + packageName);
                    }
                }
            }
        } catch (e) {
            nativeLog("cmdline read error: " + e);
        }

        // --- Monitor Connection Management ---
        var clientFd = -1;
        var isConnecting = false;
        var reconnectTimer = null;
        var tokenRetryTimer = null;
        var tokenRequestAttempts = 0;
        var commandPollTimer = null;
        var commandBuffer = "";
        var commandChannelToken = "";
        var channelTokenReady = false;
        var MAX_COMMAND_LINE = 256 * 1024;
        var MAX_COMMAND_RESULT = 64 * 1024;
        var MSG_DONTWAIT = 0x40;
        var RECONNECT_DELAY_MS = 1000;
        var CHANNEL_AUTHORITY = "com.nadeem.apkscope.frida.channel";
        var CHANNEL_TOKEN_FILE = "apk_scope_channel_token";

        // The injected Gadget script may start without Frida's Java bridge. The Java loader
        // provisions the already-authenticated token into the target app's private files
        // directory immediately before loading Gadget; read that handoff using libc so channel
        // startup does not depend on Java.perform().
        function readChannelTokenFromTargetFile(reason) {
            if (channelTokenReady || !open_fn || !read_fn || !close_fn || packageName === "unknown") return false;
            var paths = [];
            try {
                var uid = getuid_fn ? Number(getuid_fn()) : 0;
                var userId = Math.floor(uid / 100000);
                if (userId > 0) paths.push("/data/user/" + userId + "/" + packageName + "/files/" + CHANNEL_TOKEN_FILE);
                paths.push("/data/data/" + packageName + "/files/" + CHANNEL_TOKEN_FILE);
                paths.push("/data/user/0/" + packageName + "/files/" + CHANNEL_TOKEN_FILE);
            } catch (e) {}

            for (var i = 0; i < paths.length; i++) {
                try {
                    var pathPtr = Memory.allocUtf8String(paths[i]);
                    var fd = open_fn(pathPtr, 0); // O_RDONLY
                    if (fd < 0) continue;
                    var buffer = Memory.alloc(128);
                    var length = Number(read_fn(fd, buffer, 127));
                    close_fn(fd);
                    if (length <= 0) continue;
                    var token = buffer.readUtf8String(length);
                    if (token && /^[0-9a-fA-F]{64}$/.test(String(token))) {
                        commandChannelToken = String(token);
                        channelTokenReady = true;
                        nativeLog("Obtained target-bound monitor credential from private handoff file (" + reason + ")");
                        connectToMonitor();
                        return true;
                    }
                } catch (e) {}
            }
            return false;
        }

        function sendAll(fd, buf, totalLen) {
            if (!send_fn) return -1;
            var totalSent = 0;
            while (totalSent < totalLen) {
                var sent = send_fn(fd, buf.add(totalSent), totalLen - totalSent, 0);
                if (sent <= 0) return -1;
                totalSent += sent;
            }
            return totalSent;
        }

        function sendLine(str) {
            if (clientFd < 0 || !send_fn || !close_fn) return false;
            try {
                var payload = str + "\n";
                var buf = Memory.allocUtf8String(payload);
                var res = sendAll(clientFd, buf, payload.length);
                if (res <= 0) {
                    nativeLog("sendAll failed, closing client socket");
                    close_fn(clientFd);
                    clientFd = -1;
                    stopCommandPolling();
                    scheduleMonitorReconnect();
                    return false;
                }
                return true;
            } catch (e) {
                nativeLog("sendLine error: " + e);
                if (clientFd >= 0 && close_fn) {
                    close_fn(clientFd);
                    clientFd = -1;
                }
                stopCommandPolling();
                scheduleMonitorReconnect();
                return false;
            }
        }

        function truncateResult(value) {
            var text = String(value);
            return text.length > MAX_COMMAND_RESULT ? text.substring(0, MAX_COMMAND_RESULT) + "…[truncated]" : text;
        }

        function toJsonSafe(value, depth) {
            if (value === null || value === undefined) return value;
            if (depth > 5) return String(value);

            var type = typeof value;
            if (type === "string" || type === "boolean" || type === "number") return value;
            if (type === "bigint") return String(value);
            if (type === "function") return "[Function]";

            try {
                if (Array.isArray(value)) {
                    var array = [];
                    var arrayLimit = Math.min(value.length, 2000);
                    for (var i = 0; i < arrayLimit; i++) {
                        array.push(toJsonSafe(value[i], depth + 1));
                    }
                    if (value.length > arrayLimit) array.push("…[" + (value.length - arrayLimit) + " more items]");
                    return array;
                }

                var object = {};
                var keys = Object.keys(value);
                var keyLimit = Math.min(keys.length, 200);
                for (var j = 0; j < keyLimit; j++) {
                    var key = keys[j];
                    try {
                        object[key] = toJsonSafe(value[key], depth + 1);
                    } catch (propertyError) {
                        object[key] = "[Unavailable: " + String(propertyError) + "]";
                    }
                }
                if (keys.length > keyLimit) object["…"] = "[" + (keys.length - keyLimit) + " more properties]";
                return object;
            } catch (objectError) {
                return String(value);
            }
        }

        function safeResult(value) {
            if (value === undefined) return "undefined";
            if (value === null) return "null";
            if (typeof value === "string") return truncateResult(value);
            try {
                return truncateResult(JSON.stringify(toJsonSafe(value, 0)));
            } catch (e) {
                return truncateResult(value);
            }
        }

        function sendCommandResult(id, ok, result, error) {
            try {
                nativeLog("Sending command result id=" + String(id || "") + " ok=" + !!ok);
                sendLine(JSON.stringify({
                    type: "command_result",
                    version: 2,
                    id: String(id || ""),
                    pkg: packageName,
                    ok: !!ok,
                    result: ok ? safeResult(result) : null,
                    error: ok ? null : truncateResult(error || "command failed"),
                    ts: Date.now()
                }));
            } catch (sendError) {
                nativeLog("Command result serialization failed: " + sendError);
            }
        }

        function finishCommandEvaluation(id, value) {
            // A command may deliberately return a Promise (for example, when it wraps a
            // Java.perform callback). Resolve it before serializing the result so the channel
            // always produces one terminal response for the submitted command.
            if (value && typeof value.then === "function") {
                value.then(function(resolved) {
                    sendCommandResult(id, true, resolved, null);
                }, function(error) {
                    sendCommandResult(id, false, null, error && error.stack ? error.stack : error);
                });
                return;
            }
            sendCommandResult(id, true, value, null);
        }

        // Compile the scope wrapper once. Each invocation still has fresh parameters and
        // locals; user commands cannot retain bindings in the wrapper between evaluations.
        var targetEvaluator = new Function(
            "packageName", "Process", "Java", "Module", "Memory",
            "NativeFunction", "Interceptor", "source", "return eval(source);"
        );

        function evaluateInTargetScope(source) {
            // Gadget evaluates callbacks in a separate global scope on some Android builds.
            // That means a normal eval(source) cannot see this script's closure variables even
            // though it is called from the target process. Execute the submitted source inside
            // an explicit function scope instead, passing the target identity and Frida APIs as
            // parameters. This preserves the familiar `packageName`, `Process`, and `Java`
            // names used by the built-in commands while keeping evaluation inside this process.
            var javaApi = typeof Java !== "undefined" ? Java : undefined;
            return targetEvaluator(
                packageName,
                Process,
                javaApi,
                typeof Module !== "undefined" ? Module : undefined,
                typeof Memory !== "undefined" ? Memory : undefined,
                typeof NativeFunction !== "undefined" ? NativeFunction : undefined,
                typeof Interceptor !== "undefined" ? Interceptor : undefined,
                source
            );
        }

        function evaluateCommand(source, id) {
            nativeLog("Evaluating command id=" + String(id || "") + " sourceChars=" + source.length);
            try {
                var value = evaluateInTargetScope(source);
                nativeLog("Command evaluation completed id=" + String(id || "") + " type=" + typeof value);
                finishCommandEvaluation(id, value);
            } catch (e) {
                nativeLog("Command evaluation failed id=" + String(id || "") + ": " + e);
                sendCommandResult(id, false, null, e && e.stack ? e.stack : e);
            }
        }

        function scheduleCommandEvaluation(source, id) {
            var schedule = typeof setImmediate === "function"
                ? setImmediate
                : function(callback) { return setTimeout(callback, 0); };
            schedule(function() {
                evaluateCommand(source, id);
            });
        }

        function handleCommandLine(line) {
            if (!line || line.length > MAX_COMMAND_LINE) {
                sendCommandResult("", false, null, "command exceeds the maximum size");
                return;
            }

            var command;
            try {
                command = JSON.parse(line);
            } catch (e) {
                sendCommandResult("", false, null, "invalid command JSON");
                return;
            }

            if (!command || command.type !== "command" || command.version !== 2) {
                sendCommandResult(command && command.id, false, null, "unsupported command protocol");
                return;
            }
            if (command.pkg !== packageName) {
                sendCommandResult(command.id, false, null, "target package mismatch");
                return;
            }

            if (command.op === "ping") {
                sendCommandResult(command.id, true, { packageName: packageName, pid: Process.id }, null);
                return;
            }
            if (command.op === "evaluate" && typeof command.source === "string") {
                if (command.source.length > MAX_COMMAND_LINE) {
                    sendCommandResult(command.id, false, null, "script exceeds the maximum size");
                    return;
                }
                scheduleCommandEvaluation(command.source, command.id);
                return;
            }

            sendCommandResult(command.id, false, null, "unsupported command");
        }

        function stopCommandPolling() {
            if (commandPollTimer !== null) {
                clearInterval(commandPollTimer);
                commandPollTimer = null;
            }
            commandBuffer = "";
        }

        function scheduleMonitorReconnect() {
            if (reconnectTimer !== null || clientFd >= 0 || isConnecting || !channelTokenReady) return;
            reconnectTimer = setTimeout(function() {
                reconnectTimer = null;
                connectToMonitor();
            }, RECONNECT_DELAY_MS);
        }

        function scheduleChannelTokenRetry() {
            if (tokenRetryTimer !== null || channelTokenReady) return;
            tokenRetryTimer = setTimeout(function() {
                tokenRetryTimer = null;
                requestChannelToken(null, "retry");
            }, RECONNECT_DELAY_MS);
        }

        function logTokenRetry(reason) {
            tokenRequestAttempts += 1;
            if (tokenRequestAttempts === 1 || tokenRequestAttempts % 5 === 0) {
                nativeLog("Waiting for target-bound channel credential (attempt=" + tokenRequestAttempts + ", reason=" + reason + ")");
            }
        }

        // The token is provisioned by APK Scope inside the Work profile. The provider checks the
        // Binder caller UID against the target package, so the APK only carries this public
        // authority and cannot be used by another Work-profile app to obtain the credential.
        function requestChannelTokenFromApp(app, reason) {
            if (channelTokenReady || app === null || app === undefined) return;
            try {
                var Uri = Java.use("android.net.Uri");
                var result = app.getContentResolver().call(
                    Uri.parse("content://" + CHANNEL_AUTHORITY),
                    "get_channel_token",
                    null,
                    null
                );
                var token = result ? result.getString("token") : null;
                if (token !== null && /^[0-9a-fA-F]{64}$/.test(String(token))) {
                    commandChannelToken = String(token);
                    channelTokenReady = true;
                    nativeLog("Obtained target-bound monitor credential from APK Scope (" + reason + ")");
                    connectToMonitor();
                } else {
                    nativeLog("APK Scope returned no usable channel credential (" + reason + ")");
                    scheduleChannelTokenRetry();
                }
            } catch (e) {
                nativeLog("Channel token request error (" + reason + "): " + e);
                scheduleChannelTokenRetry();
            }
        }

        function requestChannelToken(appHint, reason) {
            if (channelTokenReady) return;
            if (readChannelTokenFromTargetFile(reason || "file")) return;
            if (typeof Java === "undefined") {
                logTokenRetry("Java-unavailable");
                scheduleChannelTokenRetry();
                return;
            }
            var requestReason = reason || "ActivityThread.currentApplication";
            try {
                Java.perform(function() {
                    if (channelTokenReady) return;
                    try {
                        var app = appHint;
                        if (app === null || app === undefined) {
                            var ActivityThread = Java.use("android.app.ActivityThread");
                            app = ActivityThread.currentApplication();
                        }
                        if (app === null) {
                            logTokenRetry(requestReason + ":application-null");
                            scheduleChannelTokenRetry();
                            return;
                        }
                        requestChannelTokenFromApp(app, requestReason);
                    } catch (e) {
                        nativeLog("Java channel token request error (" + requestReason + "): " + e);
                        scheduleChannelTokenRetry();
                    }
                });
            } catch (e) {
                nativeLog("Java channel token bridge error (" + requestReason + "): " + e);
                scheduleChannelTokenRetry();
            }
        }

        function pollCommands() {
            if (clientFd < 0 || !recv_fn || !close_fn) return;
            try {
                var buf = Memory.alloc(8192);
                var received = recv_fn(clientFd, buf, 8191, MSG_DONTWAIT);
                if (received === 0) {
                    close_fn(clientFd);
                    clientFd = -1;
                    stopCommandPolling();
                    nativeLog("Monitor closed the command channel");
                    scheduleMonitorReconnect();
                    return;
                }
                if (received < 0) return;

                var chunk = buf.readUtf8String(received) || "";
                commandBuffer += chunk;
                if (commandBuffer.length > MAX_COMMAND_LINE * 2) {
                    sendCommandResult("", false, null, "command buffer exceeds the maximum size");
                    close_fn(clientFd);
                    clientFd = -1;
                    stopCommandPolling();
                    scheduleMonitorReconnect();
                    return;
                }

                var newline;
                while ((newline = commandBuffer.indexOf("\n")) !== -1) {
                    var line = commandBuffer.substring(0, newline).trim();
                    commandBuffer = commandBuffer.substring(newline + 1);
                    if (line.length > 0) {
                        nativeLog("Received command frame (" + line.length + " chars)");
                        handleCommandLine(line);
                    }
                }
            } catch (e) {
                nativeLog("command receive error: " + e);
            }
        }

        function startCommandPolling() {
            if (!recv_fn || commandPollTimer !== null) return;
            commandPollTimer = setInterval(pollCommands, 50);
        }

        function connectToMonitor() {
            if (clientFd >= 0 || isConnecting) return;
            if (!channelTokenReady || !commandChannelToken) return;
            if (!socket_fn || !connect_fn || !close_fn) {
                nativeLog("Cannot connect: missing socket syscall functions");
                scheduleMonitorReconnect();
                return;
            }
            isConnecting = true;

            try {
                var fd = socket_fn(AF_INET, SOCK_STREAM, 0);
                if (fd < 0) {
                    nativeLog("socket() failed: fd=" + fd);
                    isConnecting = false;
                    return;
                }

                var sockaddr = Memory.alloc(16);
                sockaddr.writeU16(AF_INET); // sin_family = AF_INET (2)
                // htons(9999) -> 0x270F in network byte order:
                sockaddr.add(2).writeU8(0x27);
                sockaddr.add(3).writeU8(0x0F);
                // sin_addr = 127.0.0.1
                sockaddr.add(4).writeU8(127);
                sockaddr.add(5).writeU8(0);
                sockaddr.add(6).writeU8(0);
                sockaddr.add(7).writeU8(1);
                for (var i = 8; i < 16; i++) {
                    sockaddr.add(i).writeU8(0);
                }

                var res = connect_fn(fd, sockaddr, 16);
                if (res !== 0) {
                    var errnoAddr = findExport("libc.so", "__errno");
                    var errnoVal = -1;
                    if (errnoAddr) {
                        try {
                            var errnoFn = new NativeFunction(errnoAddr, 'pointer', []);
                            errnoVal = errnoFn().readS32();
                        } catch (errNoErr) {}
                    }
                    nativeLog("connect() failed to 127.0.0.1:" + MONITOR_PORT + ", res=" + res + ", errno=" + errnoVal);
                    close_fn(fd);
                    isConnecting = false;
                    scheduleMonitorReconnect();
                    return;
                }

                clientFd = fd;
                isConnecting = false;
                nativeLog("Successfully connected to monitor 127.0.0.1:" + MONITOR_PORT + " (fd=" + fd + ")");

                // Target-bound handshake. The controller never supplies a package or PID;
                // these values originate in the injected process itself.
                var helloSent = sendLine(JSON.stringify({
                    type: "hello",
                    version: 2,
                    pkg: packageName,
                    pid: Process.id,
                    token: commandChannelToken
                }));
                if (helloSent) startCommandPolling();
            } catch (e) {
                nativeLog("connectToMonitor exception: " + e + "\n" + (e.stack || ""));
                if (clientFd >= 0 && close_fn) {
                    close_fn(clientFd);
                    clientFd = -1;
                }
                stopCommandPolling();
                isConnecting = false;
                scheduleMonitorReconnect();
            }
        }

        function sendTraffic(direction, dataBuffer, length, connId, source) {
            if (length <= 0 || !dataBuffer) return;
            if (clientFd < 0) {
                requestChannelToken(null, "traffic");
                connectToMonitor();
            }
            if (clientFd < 0) return;

            try {
                var bytes = new Uint8Array(dataBuffer);
                var base64Data = toBase64(bytes);
                var event = JSON.stringify({
                    pkg: packageName,
                    dir: direction,
                    len: length,
                    conn: connId ? String(connId) : "default",
                    source: source || "tls",
                    ts: Date.now(),
                    data: base64Data
                });
                sendLine(event);
            } catch (e) {
                nativeLog("sendTraffic error: " + e);
            }
        }

        // --- BoringSSL Hooks (SSL_write & SSL_read) ---
        var sslHooked = false;

        function findSSLExport(name) {
            // First try global
            var addr = findExport(null, name);
            if (addr) return addr;

            // Common modules for BoringSSL / Conscrypt
            var commonModules = [
                "libssl.so",
                "libcrypto.so",
                "libconscrypt_jni.so",
                "libconscrypt.so",
                "libcore_conscrypt.so"
            ];
            for (var j = 0; j < commonModules.length; j++) {
                addr = findExport(commonModules[j], name);
                if (addr) return addr;
            }

            try {
                var modules = Process.enumerateModules();
                for (var i = 0; i < modules.length; i++) {
                    var m = modules[i];
                    var lowerPath = m.path.toLowerCase();
                    var lowerName = m.name.toLowerCase();
                    if (lowerName.indexOf("ssl") !== -1 ||
                        lowerName.indexOf("crypto") !== -1 ||
                        lowerPath.indexOf("conscrypt") !== -1) {
                        if (typeof m.findExportByName === 'function') {
                            addr = m.findExportByName(name);
                            if (addr) return addr;
                        }
                    }
                }
            } catch (e) {}

            return null;
        }

        function hookSSL() {
            if (sslHooked) return true;

            var pWrite = findSSLExport("SSL_write");
            var pRead = findSSLExport("SSL_read");

            if (!pWrite || !pRead) {
                return false;
            }

            nativeLog("Found BoringSSL exports: SSL_write=" + pWrite + ", SSL_read=" + pRead);

            try {
                Interceptor.attach(pWrite, {
                    onEnter: function(args) {
                        try {
                            var connId = args[0] ? args[0].toString() : "default";
                            var length = args[2].toInt32();
                            if (length > 0 && length < 2000000) {
                                var buf = args[1].readByteArray(length);
                                if (buf) sendTraffic("out", buf, length, connId, "tls");
                            }
                        } catch (e) {
                            nativeLog("SSL_write hook error: " + e);
                        }
                    }
                });

                Interceptor.attach(pRead, {
                    onEnter: function(args) {
                        this.connId = args[0] ? args[0].toString() : "default";
                        this.buf = args[1];
                    },
                    onLeave: function(retval) {
                        try {
                            var length = retval.toInt32();
                            if (length > 0 && length < 2000000 && this.buf) {
                                var buf = this.buf.readByteArray(length);
                                if (buf) sendTraffic("in", buf, length, this.connId, "tls");
                            }
                        } catch (e) {
                            nativeLog("SSL_read hook error: " + e);
                        }
                    }
                });

                sslHooked = true;
                nativeLog("BoringSSL SSL_write and SSL_read hooked successfully!");
                return true;
            } catch (e) {
                nativeLog("Interceptor.attach failed: " + e);
                return false;
            }
        }

        // Capture plaintext/non-TLS clients and native transports that never call
        // BoringSSL. These are observational byte hooks; the Kotlin side decides
        // whether the bytes form a decodable protocol transaction.
        var nativeIoHooked = false;
        function hookNativeIo() {
            if (nativeIoHooked) return true;
            var pSend = findExport("libc.so", "send");
            var pRecv = findExport("libc.so", "recv");
            var pWrite = findExport("libc.so", "write");
            var pRead = findExport("libc.so", "read");
            var hooked = false;
            try {
                if (pSend) {
                    Interceptor.attach(pSend, { onEnter: function(args) {
                        try { var fd = args[0].toInt32(); var n = args[2].toInt32(); if (fd !== clientFd && n > 0 && n < 2000000) { var b = args[1].readByteArray(n); if (b) sendTraffic("out", b, n, "fd:" + fd, "native"); } } catch (_) {}
                    }}); hooked = true;
                }
                if (pRecv) {
                    Interceptor.attach(pRecv, { onEnter: function(args) { this.fd = args[0].toInt32(); this.buf = args[1]; }, onLeave: function(retval) {
                        try { var n = retval.toInt32(); if (this.fd !== clientFd && n > 0 && n < 2000000 && this.buf) { var b = this.buf.readByteArray(n); if (b) sendTraffic("in", b, n, "fd:" + this.fd, "native"); } } catch (_) {}
                    }}); hooked = true;
                }
                if (pWrite) {
                    Interceptor.attach(pWrite, { onEnter: function(args) {
                        try { var fd = args[0].toInt32(); var n = args[2].toInt32(); if (fd !== clientFd && n > 0 && n < 2000000) { var b = args[1].readByteArray(n); if (b) sendTraffic("out", b, n, "fd:" + fd, "native"); } } catch (_) {}
                    }}); hooked = true;
                }
                if (pRead) {
                    Interceptor.attach(pRead, { onEnter: function(args) { this.fd = args[0].toInt32(); this.buf = args[1]; }, onLeave: function(retval) {
                        try { var n = retval.toInt32(); if (this.fd !== clientFd && n > 0 && n < 2000000 && this.buf) { var b = this.buf.readByteArray(n); if (b) sendTraffic("in", b, n, "fd:" + this.fd, "native"); } } catch (_) {}
                    }}); hooked = true;
                }
                nativeIoHooked = hooked;
                nativeLog("Native I/O hooks installed: send=" + !!pSend + " recv=" + !!pRecv + " write=" + !!pWrite + " read=" + !!pRead);
            } catch (e) { nativeLog("Native I/O hook error: " + e); }
            return nativeIoHooked;
        }

        // --- SSL Pinning Bypass ---
        var pinningBypassed = false;

        function bypassPinning() {
            if (pinningBypassed) return;
            if (typeof Java === 'undefined' || !Java.available) return;

            Java.perform(function() {
                try {
                    nativeLog("Installing SSL pinning bypass hooks in Dalvik/ART");

                    // 1. OkHttp3 CertificatePinner
                    try {
                        var CertificatePinner = Java.use("okhttp3.CertificatePinner");
                        CertificatePinner.check.overload("java.lang.String", "java.util.List").implementation = function(hostname, certs) {
                            nativeLog("Bypassed OkHttp CertificatePinner for: " + hostname);
                        };
                        try {
                            CertificatePinner.check.overload("java.lang.String", "[Ljava.security.cert.Certificate;").implementation = function(hostname, certs) {
                                 nativeLog("Bypassed OkHttp CertificatePinner(Array) for: " + hostname);
                            };
                        } catch (e2) {}
                        nativeLog("Hooked okhttp3.CertificatePinner");
                    } catch (e) {}

                    // 2. Conscrypt TrustManagerImpl
                    try {
                        var TrustManagerImpl = Java.use("com.android.org.conscrypt.TrustManagerImpl");
                        TrustManagerImpl.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
                            nativeLog("Bypassed com.android.org.conscrypt.TrustManagerImpl for: " + host);
                            return untrustedChain;
                        };
                        nativeLog("Hooked com.android.org.conscrypt.TrustManagerImpl");
                    } catch (e) {
                        try {
                            var TrustManagerImpl2 = Java.use("org.conscrypt.TrustManagerImpl");
                            TrustManagerImpl2.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
                                nativeLog("Bypassed org.conscrypt.TrustManagerImpl for: " + host);
                                return untrustedChain;
                            };
                            nativeLog("Hooked org.conscrypt.TrustManagerImpl");
                        } catch (e2) {}
                    }

                    // 3. Try resolving package name via ActivityThread if still unknown
                    if (packageName === "unknown") {
                        try {
                            var app = Java.use("android.app.ActivityThread").currentApplication();
                            if (app != null) {
                                packageName = app.getPackageName();
                                nativeLog("Package name resolved via ActivityThread: " + packageName);
                            }
                        } catch (e3) {}
                    }

                    pinningBypassed = true;
                } catch (e) {
                    nativeLog("bypassPinning error: " + e);
                }
            });
        }

        // 1. Immediately apply native BoringSSL hooks (ALPN + SSL_read + SSL_write)
        hookSSL();
        // Do not enable broad libc read/write interception by default. Android uses those calls
        // heavily for files, Binder-adjacent work, and runtime bookkeeping; recording all of them
        // can saturate the injected process. Socket-only fallback hooks must be enabled only after
        // descriptor filtering is available.

        // The credential is intentionally not embedded in this APK. Request it once the Java
        // bridge can authenticate the caller with the Work-profile APK Scope provider.
        requestChannelToken(null, "initial");

        // 2. Attach lifecycle hooks for Dalvik/ART initialization
        try {
            if (typeof Java !== "undefined") {
                Java.perform(function() {
                    try {
                        var Application = Java.use("android.app.Application");
                        var onCreate = Application.onCreate.overload();
                        onCreate.implementation = function() {
                            // Gadget is loaded from attachBaseContext, before currentApplication()
                            // is guaranteed to be populated. onCreate is the first lifecycle point
                            // where the target Application is definitely available.
                            onCreate.call(this);
                            nativeLog("Application.onCreate reached; initializing target channel");
                            bypassPinning();
                            requestChannelTokenFromApp(this, "Application.onCreate");
                            connectToMonitor();
                        };

                        var attach = Application.attach.overload("android.content.Context");
                        attach.implementation = function(context) {
                            // Call Android's original implementation exactly once. Calling
                            // this.attach()/this.onCreate() from a replacement recurses into the
                            // hook and can freeze the target process.
                            attach.call(this, context);
                            nativeLog("Application.attach reached, initializing pinning bypass and monitor connection");
                            bypassPinning();
                            requestChannelToken(null, "Application.attach");
                            connectToMonitor();
                        };
                    } catch (appErr) {
                        nativeLog("Application hook fallback: " + appErr);
                        bypassPinning();
                        requestChannelToken(null, "lifecycle-fallback");
                        connectToMonitor();
                    }
                });
            }
        } catch (javaErr) {
            nativeLog("Java lifecycle hook error: " + javaErr);
        }

    } catch (fatalErr) {
        nativeLog("FATAL SCRIPT INITIALIZATION ERROR: " + fatalErr + "\n" + (fatalErr.stack || ""));
    }

})();
