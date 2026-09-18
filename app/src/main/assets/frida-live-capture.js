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
        var pClose = findExport("libc.so", "close");
        var pOpen = findExport("libc.so", "open");
        var pRead = findExport("libc.so", "read");

        nativeLog("POSIX symbols: socket=" + pSocket + ", connect=" + pConnect + ", send=" + pSend + ", close=" + pClose);

        var socket_fn = pSocket ? new NativeFunction(pSocket, 'int', ['int', 'int', 'int']) : null;
        var connect_fn = pConnect ? new NativeFunction(pConnect, 'int', ['int', 'pointer', 'int']) : null;
        var send_fn = pSend ? new NativeFunction(pSend, 'int', ['int', 'pointer', 'int', 'int']) : null;
        var close_fn = pClose ? new NativeFunction(pClose, 'int', ['int']) : null;
        var open_fn = pOpen ? new NativeFunction(pOpen, 'int', ['pointer', 'int']) : null;
        var read_fn = pRead ? new NativeFunction(pRead, 'long', ['int', 'pointer', 'long']) : null;

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
                    return false;
                }
                return true;
            } catch (e) {
                nativeLog("sendLine error: " + e);
                if (clientFd >= 0 && close_fn) {
                    close_fn(clientFd);
                    clientFd = -1;
                }
                return false;
            }
        }

        function connectToMonitor() {
            if (clientFd >= 0 || isConnecting) return;
            if (!socket_fn || !connect_fn || !close_fn) {
                nativeLog("Cannot connect: missing socket syscall functions");
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
                    return;
                }

                clientFd = fd;
                isConnecting = false;
                nativeLog("Successfully connected to monitor 127.0.0.1:" + MONITOR_PORT + " (fd=" + fd + ")");

                // Handshake line 1: package name
                sendLine(packageName);
            } catch (e) {
                nativeLog("connectToMonitor exception: " + e + "\n" + (e.stack || ""));
                if (clientFd >= 0 && close_fn) {
                    close_fn(clientFd);
                    clientFd = -1;
                }
                isConnecting = false;
            }
        }

        function sendTraffic(direction, dataBuffer, length, connId, source) {
            if (length <= 0 || !dataBuffer) return;
            if (clientFd < 0) {
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

        // 2. Attach lifecycle hooks for Dalvik/ART initialization
        try {
            if (typeof Java !== "undefined") {
                Java.perform(function() {
                    try {
                        var Application = Java.use("android.app.Application");
                        var attach = Application.attach.overload("android.content.Context");
                        attach.implementation = function(context) {
                            // Call Android's original implementation exactly once. Calling
                            // this.attach()/this.onCreate() from a replacement recurses into the
                            // hook and can freeze the target process.
                            attach.call(this, context);
                            nativeLog("Application.attach reached, initializing pinning bypass and monitor connection");
                            bypassPinning();
                            connectToMonitor();
                        };
                    } catch (appErr) {
                        nativeLog("Application hook fallback: " + appErr);
                        bypassPinning();
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
