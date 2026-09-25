// Opt-in, bounded metadata + SharedPreferences snapshot for the active instrumented target.
// This command runs in the authenticated target process. It does not write or change target data.
(function () {
    return new Promise(function (resolve, reject) {
        // frida-java-bridge's own bootstrap (scanning loaded classes, hooking the class loader)
        // finishes asynchronously after the gadget attaches -- `Java.available` and, once that is
        // true, `ActivityThread.currentApplication()` can both still be transiently false/null for
        // a short window right after the command channel first reports ready (confirmed on-device:
        // a scan fired immediately on attach surfaced "The target Java runtime is not available."
        // even though the target was a normal, fully-launched app). Poll for both, bounded, instead
        // of failing on the first observation -- the same "don't give up on a transient timing gap"
        // approach this script's own command channel already uses for its token/reconnect handshake.
        var READY_POLL_INTERVAL_MS = 150;
        var READY_TIMEOUT_MS = 5000;
        var readyDeadline = Date.now() + READY_TIMEOUT_MS;

        function waitForJavaRuntime() {
            if (typeof Java !== "undefined" && Java.available) {
                Java.perform(waitForApplicationContext);
                return;
            }
            if (Date.now() >= readyDeadline) {
                reject(new Error("The target Java runtime is not available."));
                return;
            }
            setTimeout(waitForJavaRuntime, READY_POLL_INTERVAL_MS);
        }

        function waitForApplicationContext() {
            var context;
            try {
                var ActivityThread = Java.use("android.app.ActivityThread");
                context = ActivityThread.currentApplication();
            } catch (error) {
                reject(error);
                return;
            }
            if (!context) {
                if (Date.now() >= readyDeadline) {
                    reject(new Error("The target application context is not ready."));
                    return;
                }
                setTimeout(function () { Java.perform(waitForApplicationContext); }, READY_POLL_INTERVAL_MS);
                return;
            }

            try {
                var result = {
                    schemaVersion: 1,
                    packageName: packageName,
                    pid: Process.id,
                    capturedAt: Date.now(),
                    entries: [],
                    warnings: [],
                    truncated: false
                };
                var MAX_ENTRIES = 180;
                var MAX_PREFS = 220;
                var MAX_VALUE_CHARS = 512;
                var MAX_RESULT_CHARS = 48000;
                var startedAt = Date.now();
                var visited = {};
                var roots = [];
                var prefCount = 0;

                function warn(message) {
                    if (result.warnings.indexOf(message) === -1 && result.warnings.length < 16) {
                        result.warnings.push(message);
                    }
                }

                function safeName(value, fallback) {
                    try { return String(value); } catch (_) { return fallback; }
                }

                function safeLong(value) {
                    try { return Number(String(value)); } catch (_) { return 0; }
                }

                function isExcluded(file) {
                    var name = safeName(file.getName(), "");
                    return name === "apk_scope_channel_token" ||
                        name.indexOf("apk_scope_channel_token.") === 0 ||
                        name.indexOf(".apk_scope_instrumentation") === 0;
                }

                function addRoot(label, file, type, ownerContext) {
                    if (!file) return;
                    try {
                        if (!file.exists() || !file.isDirectory()) return;
                        var canonical = safeName(file.getCanonicalPath(), "");
                        if (!canonical) return;
                        for (var i = 0; i < roots.length; i++) {
                            if (roots[i].canonical === canonical) return;
                        }
                        roots.push({ label: label, file: file, canonical: canonical, type: type, ownerContext: ownerContext });
                    } catch (error) {
                        warn(label + " could not be inspected");
                    }
                }

                var credentialContext = context.getApplicationContext();
                addRoot("App files", credentialContext.getFilesDir(), "files", credentialContext);
                addRoot("App cache", credentialContext.getCacheDir(), "cache", credentialContext);
                addRoot("No-backup files", credentialContext.getNoBackupFilesDir(), "no_backup", credentialContext);
                addRoot("Database files", credentialContext.getDatabasePath("snapshot.db").getParentFile(), "database", credentialContext);

                try {
                    var dataDir = credentialContext.getDataDir();
                    addRoot("Shared preferences", Java.use("java.io.File").$new(dataDir, "shared_prefs"), "preferences", credentialContext);
                } catch (_) { warn("Shared preference directory is unavailable"); }

                try {
                    var external = credentialContext.getExternalFilesDirs(null);
                    for (var x = 0; x < external.length; x++) addRoot("App external files", external[x], "external_files", credentialContext);
                    var externalCache = credentialContext.getExternalCacheDirs();
                    for (var y = 0; y < externalCache.length; y++) addRoot("App external cache", externalCache[y], "external_cache", credentialContext);
                } catch (_) { warn("App-specific external storage is unavailable"); }

                try {
                    var deviceContext = credentialContext.createDeviceProtectedStorageContext();
                    addRoot("Device-protected files", deviceContext.getFilesDir(), "device_files", deviceContext);
                    addRoot("Device-protected cache", deviceContext.getCacheDir(), "device_cache", deviceContext);
                    addRoot("Device-protected no-backup files", deviceContext.getNoBackupFilesDir(), "device_no_backup", deviceContext);
                    addRoot("Device-protected databases", deviceContext.getDatabasePath("snapshot.db").getParentFile(), "device_database", deviceContext);
                    var deviceData = deviceContext.getDataDir();
                    addRoot("Device-protected preferences", Java.use("java.io.File").$new(deviceData, "shared_prefs"), "device_preferences", deviceContext);
                } catch (_) { warn("Device-protected storage is unavailable"); }

                function fits(entry) {
                    if (result.entries.length >= MAX_ENTRIES) return false;
                    result.entries.push(entry);
                    var size = JSON.stringify(result).length;
                    if (size <= MAX_RESULT_CHARS) return true;
                    result.entries.pop();
                    return false;
                }

                function inventory(root, file, relativePath, depth) {
                    if (result.entries.length >= MAX_ENTRIES || Date.now() - startedAt > 2500) {
                        result.truncated = true;
                        return;
                    }
                    var path;
                    try {
                        if (isExcluded(file)) return;
                        path = safeName(file.getCanonicalPath(), "");
                        var lexical = safeName(file.getAbsolutePath(), "");
                        if (!path || (path !== root.canonical && path.indexOf(root.canonical + "/") !== 0) || path !== lexical) return;
                        if (visited[path]) return;
                        visited[path] = true;
                        var isDirectory = file.isDirectory();
                        var isPreferencesXml = root.type.indexOf("preferences") !== -1 && /\.xml$/i.test(safeName(file.getName(), ""));
                        if (!isDirectory && !isPreferencesXml) {
                            var name = safeName(file.getName(), "file");
                            var ext = name.indexOf(".") >= 0 ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
                            var kind = /^(png|jpe?g|webp|gif|bmp|heic|heif|avif)$/.test(ext) ? "image" :
                                /^(db|sqlite|sqlite3)$/.test(ext) ? "database" : "file";
                            if (!fits({
                                id: root.label + ":" + relativePath,
                                kind: kind,
                                root: root.label,
                                path: relativePath,
                                name: name,
                                sizeBytes: Math.max(0, safeLong(file.length())),
                                modifiedAt: Math.max(0, safeLong(file.lastModified())),
                                previewAvailable: false
                            })) result.truncated = true;
                        }
                        if (isDirectory && depth < 8) {
                            var children = file.listFiles();
                            if (!children) { warn(root.label + " contains a folder that could not be listed"); return; }
                            var count = Math.min(children.length, 240);
                            if (children.length > count) result.truncated = true;
                            for (var i = 0; i < count && result.entries.length < MAX_ENTRIES; i++) {
                                var child = children[i];
                                inventory(root, child, relativePath ? relativePath + "/" + safeName(child.getName(), "item") : safeName(child.getName(), "item"), depth + 1);
                            }
                        }
                    } catch (_) { warn(root.label + " has an item that could not be inspected"); }
                }

                for (var r = 0; r < roots.length; r++) {
                    var root = roots[r];
                    if (root.type.indexOf("preferences") !== -1) {
                        try {
                            var stores = root.file.listFiles();
                            if (!stores) { warn("Shared preference stores could not be listed"); continue; }
                            for (var s = 0; s < stores.length && prefCount < MAX_PREFS; s++) {
                                if (Date.now() - startedAt > 2500 || result.entries.length >= MAX_ENTRIES) {
                                    result.truncated = true;
                                    break;
                                }
                                var storeFile = stores[s];
                                if (isExcluded(storeFile) || !/\.xml$/i.test(safeName(storeFile.getName(), ""))) continue;
                                var storeName = safeName(storeFile.getName(), "preferences.xml").replace(/\.xml$/i, "");
                                try {
                                    var prefs = root.ownerContext.getSharedPreferences(storeName, 0);
                                    var map = prefs.getAll();
                                    var iterator = map.entrySet().iterator();
                                    while (iterator.hasNext() && prefCount < MAX_PREFS) {
                                        if (Date.now() - startedAt > 2500 || result.entries.length >= MAX_ENTRIES) {
                                            result.truncated = true;
                                            break;
                                        }
                                        var item = iterator.next();
                                        var key = safeName(item.getKey(), "(unnamed key)");
                                        var value = item.getValue();
                                        var text = value === null ? "null" : safeName(value, "[unavailable]");
                                        var originalChars = text.length;
                                        if (text.length > MAX_VALUE_CHARS) text = text.substring(0, MAX_VALUE_CHARS);
                                        var added = fits({
                                            id: "preference:" + storeName + ":" + key,
                                            kind: "preference",
                                            root: root.label,
                                            store: storeName,
                                            key: key.substring(0, 160),
                                            valueType: value === null ? "null" : safeName(value.getClass().getSimpleName(), "unknown"),
                                            value: text,
                                            valueTruncated: originalChars > MAX_VALUE_CHARS
                                        });
                                        if (!added) { result.truncated = true; break; }
                                        prefCount++;
                                    }
                                } catch (_) { warn("A preference store could not be read"); }
                            }
                            if (prefCount >= MAX_PREFS) result.truncated = true;
                        } catch (_) { warn("Shared preferences could not be inspected"); }
                    } else {
                        inventory(root, root.file, "", 0);
                    }
                }

                result.elapsedMs = Date.now() - startedAt;
                result.coverage = {
                    storageRoots: roots.length,
                    preferencesStores: prefCount,
                    inventoryEntries: result.entries.length,
                    observation: "snapshot_only",
                    liveReadWriteHooks: "not_started",
                    processScope: "connected_process_only"
                };
                resolve(result);
            } catch (error) {
                reject(error);
            }
        }

        waitForJavaRuntime();
    });
})();
