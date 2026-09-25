// Reads one bounded chunk from a file named by a prior Storage Inspector snapshot.
// The request contains a root label and relative path, never an absolute filesystem path.
(function () {
    return new Promise(function (resolve, reject) {
        var READY_POLL_INTERVAL_MS = 150;
        var READY_TIMEOUT_MS = 5000;
        var MAX_FILE_BYTES = 100 * 1024 * 1024;
        var MAX_CHUNK_BYTES = 24 * 1024;
        var request = typeof __exportRequest !== "undefined" ? __exportRequest : null;

        function fail(message) { reject(new Error(message)); }

        function waitForJavaRuntime() {
            if (typeof Java !== "undefined" && Java.available) {
                Java.perform(waitForApplicationContext);
            } else if (Date.now() >= readyDeadline) {
                fail("The target Java runtime is not available.");
            } else {
                setTimeout(waitForJavaRuntime, READY_POLL_INTERVAL_MS);
            }
        }

        function waitForApplicationContext() {
            var context;
            try {
                context = Java.use("android.app.ActivityThread").currentApplication();
            } catch (error) {
                reject(error);
                return;
            }
            if (!context) {
                if (Date.now() >= readyDeadline) fail("The target application context is not ready.");
                else setTimeout(function () { Java.perform(waitForApplicationContext); }, READY_POLL_INTERVAL_MS);
                return;
            }
            try { readChunk(context.getApplicationContext()); } catch (error) { reject(error); }
        }

        function safeName(value) {
            try { return String(value); } catch (_) { return ""; }
        }

        function isExcluded(file) {
            var name = safeName(file.getName());
            return name === "apk_scope_channel_token" ||
                name.indexOf("apk_scope_channel_token.") === 0 ||
                name.indexOf(".apk_scope_instrumentation") === 0;
        }

        function discoverRoots(context) {
            var roots = [];
            function addRoot(label, file, ownerContext) {
                if (!file) return;
                try {
                    if (!file.exists() || !file.isDirectory()) return;
                    var canonical = safeName(file.getCanonicalPath());
                    if (!canonical) return;
                    for (var i = 0; i < roots.length; i++) {
                        if (roots[i].canonical === canonical) return;
                    }
                    roots.push({ label: label, canonical: canonical, ownerContext: ownerContext });
                } catch (_) { }
            }

            addRoot("App files", context.getFilesDir(), context);
            addRoot("App cache", context.getCacheDir(), context);
            addRoot("No-backup files", context.getNoBackupFilesDir(), context);
            addRoot("Database files", context.getDatabasePath("snapshot.db").getParentFile(), context);
            try {
                var dataDir = context.getDataDir();
                addRoot("Shared preferences", Java.use("java.io.File").$new(dataDir, "shared_prefs"), context);
            } catch (_) { }
            try {
                var external = context.getExternalFilesDirs(null);
                for (var x = 0; x < external.length; x++) addRoot("App external files", external[x], context);
                var externalCache = context.getExternalCacheDirs();
                for (var y = 0; y < externalCache.length; y++) addRoot("App external cache", externalCache[y], context);
            } catch (_) { }
            try {
                var deviceContext = context.createDeviceProtectedStorageContext();
                addRoot("Device-protected files", deviceContext.getFilesDir(), deviceContext);
                addRoot("Device-protected cache", deviceContext.getCacheDir(), deviceContext);
                addRoot("Device-protected no-backup files", deviceContext.getNoBackupFilesDir(), deviceContext);
                addRoot("Device-protected databases", deviceContext.getDatabasePath("snapshot.db").getParentFile(), deviceContext);
                var deviceData = deviceContext.getDataDir();
                addRoot("Device-protected preferences", Java.use("java.io.File").$new(deviceData, "shared_prefs"), deviceContext);
            } catch (_) { }
            return roots;
        }

        function readChunk(context) {
            if (!request || typeof request.root !== "string" || typeof request.path !== "string") {
                fail("Malformed export request.");
                return;
            }
            var expectedSize = Number(request.expectedSizeBytes);
            var offset = Number(request.offset);
            var length = Number(request.length);
            if (!Number.isSafeInteger(expectedSize) || expectedSize < 0 || expectedSize > MAX_FILE_BYTES ||
                !Number.isSafeInteger(offset) || offset < 0 || offset > expectedSize ||
                !Number.isSafeInteger(length) || length < 1 || length > MAX_CHUNK_BYTES) {
                fail("Invalid export bounds.");
                return;
            }

            var roots = discoverRoots(context);
            var root = null;
            for (var i = 0; i < roots.length; i++) {
                if (roots[i].label === request.root) { root = roots[i]; break; }
            }
            if (!root) { fail("The selected storage root is no longer available."); return; }

            var JavaFile = Java.use("java.io.File");
            var candidate = JavaFile.$new(root.canonical, request.path);
            var canonical = safeName(candidate.getCanonicalPath());
            if (!canonical || (canonical !== root.canonical && canonical.indexOf(root.canonical + "/") !== 0)) {
                fail("The selected path is outside its storage root.");
                return;
            }
            var file = JavaFile.$new(canonical);
            if (!file.exists() || !file.isFile() || isExcluded(file)) {
                fail("The selected file is no longer available for export.");
                return;
            }
            var totalBytes = Number(String(file.length()));
            if (totalBytes !== expectedSize) { fail("The file changed after the snapshot. Refresh and try again."); return; }
            if (!Number.isSafeInteger(totalBytes) || totalBytes > MAX_FILE_BYTES) {
                fail("The selected file exceeds the 100 MiB export limit.");
                return;
            }

            var RandomAccessFile = Java.use("java.io.RandomAccessFile");
            var raf = RandomAccessFile.$new(file, "r");
            try {
                raf.seek(offset);
                var wanted = Math.min(length, totalBytes - offset);
                var buffer = Java.array("byte", new Array(wanted).fill(0));
                var read = wanted === 0 ? 0 : raf.read(buffer, 0, wanted);
                if (read < 0) read = 0;
                var encoded = Java.use("android.util.Base64").encodeToString(buffer, 0, read, 2);
                resolve(JSON.stringify({ op: "export_chunk", offset: offset, totalBytes: totalBytes, base64: encoded }));
            } catch (error) {
                reject(error);
            } finally {
                raf.close();
            }
        }

        var readyDeadline = Date.now() + READY_TIMEOUT_MS;
        waitForJavaRuntime();
    });
})();
