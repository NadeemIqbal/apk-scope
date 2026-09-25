// On-demand, bounded content preview for a single storage-inspector entry (one file, image, or
// database) inside the authenticated target process. Never runs as part of the bulk inventory scan
// in frida-storage-snapshot.js -- only when the user explicitly asks to view one entry.
//
// The caller (FridaStoragePreview.kt) prepends `var __previewRequest = {...};` naming exactly one of:
//   file_text  { root, path }
//   file_hex   { root, path }
//   file_image { root, path }
//   db_schema  { root, path }
//   db_rows    { root, path, table, page }
// `root`/`path` mirror exactly what frida-storage-snapshot.js already reported for that entry
// (root label + path relative to that root) -- this script re-derives the same roots and only ever
// resolves a path that scan itself would have reported, never an arbitrary absolute path.
//
// The result is JSON.stringify'd and truncated to 64 KiB by the command channel itself
// (frida-live-capture.js's own MAX_COMMAND_RESULT) before this ever runs -- truncating mid-JSON would
// corrupt the response, so every branch here self-bounds its own output well under that ceiling
// instead of relying on the channel's truncation.
(function () {
    return new Promise(function (resolve, reject) {
        var READY_POLL_INTERVAL_MS = 150;
        var READY_TIMEOUT_MS = 5000;
        var readyDeadline = Date.now() + READY_TIMEOUT_MS;

        // Comfortably under the command channel's 64 KiB truncation ceiling (frida-live-capture.js
        // MAX_COMMAND_RESULT) -- truncating this script's own JSON would corrupt it, so this script
        // must never let the channel's truncation actually fire.
        var MAX_RESULT_CHARS = 55000;
        var TEXT_MAX_BYTES = 32 * 1024;
        var HEX_MAX_BYTES = 3 * 1024;
        var IMAGE_MAX_INPUT_BYTES = 10 * 1024 * 1024;
        var IMAGE_MAX_DIMENSION = 320;
        var IMAGE_QUALITY_ATTEMPTS = [60, 40, 25];
        var DB_ROWS_PER_PAGE = 100;
        var DB_MAX_TABLES = 100;
        var DB_MAX_COLUMNS = 128;
        var DB_TEXT_VALUE_MAX_CHARS = 256;
        var DB_QUERY_DEADLINE_MS = 4000;

        var request = typeof __previewRequest !== "undefined" ? __previewRequest : null;

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
                handleRequest(context.getApplicationContext());
            } catch (error) {
                reject(error);
            }
        }

        function safeName(value, fallback) {
            try { return String(value); } catch (_) { return fallback; }
        }

        function isExcluded(file) {
            var name = safeName(file.getName(), "");
            return name === "apk_scope_channel_token" ||
                name.indexOf("apk_scope_channel_token.") === 0 ||
                name.indexOf(".apk_scope_instrumentation") === 0;
        }

        // Mirrors frida-storage-snapshot.js's own root discovery exactly, so a preview request can
        // only ever resolve a path within a root that scan would itself have reported.
        function discoverRoots(context) {
            var roots = [];
            function addRoot(label, file, ownerContext) {
                if (!file) return;
                try {
                    if (!file.exists() || !file.isDirectory()) return;
                    var canonical = safeName(file.getCanonicalPath(), "");
                    if (!canonical) return;
                    for (var i = 0; i < roots.length; i++) {
                        if (roots[i].canonical === canonical) return;
                    }
                    roots.push({ label: label, canonical: canonical, ownerContext: ownerContext });
                } catch (_) { /* an unreachable root is simply not offered for preview */ }
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

        function resolveRequestedFile(context) {
            if (!request || !request.root || typeof request.path !== "string") {
                throw new Error("Malformed preview request.");
            }
            var roots = discoverRoots(context);
            var root = null;
            for (var i = 0; i < roots.length; i++) {
                if (roots[i].label === request.root) { root = roots[i]; break; }
            }
            if (!root) throw new Error("The requested storage root is no longer available.");

            var JavaFile = Java.use("java.io.File");
            var candidate = request.path ? JavaFile.$new(root.canonical, request.path) : JavaFile.$new(root.canonical);
            var canonical = safeName(candidate.getCanonicalPath(), "");
            if (!canonical || (canonical !== root.canonical && canonical.indexOf(root.canonical + "/") !== 0)) {
                throw new Error("The requested path is outside its storage root.");
            }
            var resolved = JavaFile.$new(canonical);
            if (!resolved.exists() || !resolved.isFile()) throw new Error("The requested file no longer exists.");
            if (isExcluded(resolved)) throw new Error("This entry is not available for preview.");
            return resolved;
        }

        function readBoundedBytes(file, maxBytes) {
            var FileInputStream = Java.use("java.io.FileInputStream");
            var stream = FileInputStream.$new(file);
            try {
                var total = Math.min(file.length(), maxBytes);
                var buffer = Java.array('byte', new Array(total).fill(0));
                var offset = 0;
                while (offset < total) {
                    var read = stream.read(buffer, offset, total - offset);
                    if (read <= 0) break;
                    offset += read;
                }
                return { buffer: buffer, length: offset, truncated: file.length() > maxBytes };
            } finally {
                stream.close();
            }
        }

        function bytesToHex(buffer, length) {
            var HEX = "0123456789abcdef";
            var lines = [];
            for (var offset = 0; offset < length; offset += 16) {
                var hexParts = [];
                var asciiParts = [];
                var end = Math.min(offset + 16, length);
                for (var i = offset; i < end; i++) {
                    var b = buffer[i] & 0xFF;
                    hexParts.push(HEX.charAt((b >> 4) & 0xF) + HEX.charAt(b & 0xF));
                    asciiParts.push(b >= 32 && b < 127 ? String.fromCharCode(b) : ".");
                }
                var addressText = ("00000000" + offset.toString(16)).slice(-8);
                lines.push(addressText + "  " + hexParts.join(" ") + "  " + asciiParts.join(""));
            }
            return lines.join("\n");
        }

        function looksLikeText(buffer, length) {
            if (length === 0) return true;
            var controlCount = 0;
            var sampleLength = Math.min(length, 2048);
            for (var i = 0; i < sampleLength; i++) {
                var b = buffer[i] & 0xFF;
                if (b === 9 || b === 10 || b === 13) continue;
                if (b < 32 || b === 127) controlCount++;
            }
            return controlCount / sampleLength < 0.02;
        }

        function bytesToUtf8(buffer, length) {
            var sliced = Java.array('byte', Array.prototype.slice.call(buffer, 0, length));
            return Java.use("java.lang.String").$new(sliced, "UTF-8").toString();
        }

        function previewFileHex(file) {
            var read = readBoundedBytes(file, HEX_MAX_BYTES);
            return {
                op: "file_hex",
                hex: bytesToHex(read.buffer, read.length),
                byteLength: read.length,
                truncated: read.truncated || file.length() > HEX_MAX_BYTES,
            };
        }

        function previewFileText(file) {
            var read = readBoundedBytes(file, TEXT_MAX_BYTES);
            if (!looksLikeText(read.buffer, read.length)) {
                var hexResult = previewFileHex(file);
                hexResult.fellBackFromText = true;
                return hexResult;
            }
            return {
                op: "file_text",
                text: bytesToUtf8(read.buffer, read.length),
                byteLength: read.length,
                truncated: read.truncated,
            };
        }

        function previewFileImage(file) {
            if (file.length() > IMAGE_MAX_INPUT_BYTES) {
                throw new Error("Image exceeds the preview size limit.");
            }
            var BitmapFactory = Java.use("android.graphics.BitmapFactory");
            var Options = Java.use("android.graphics.BitmapFactory$Options");

            var boundsOptions = Options.$new();
            boundsOptions.inJustDecodeBounds.value = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), boundsOptions);
            var width = boundsOptions.outWidth.value;
            var height = boundsOptions.outHeight.value;
            if (width <= 0 || height <= 0) throw new Error("This file is not a decodable image.");

            var sampleSize = 1;
            while ((width / sampleSize) > IMAGE_MAX_DIMENSION || (height / sampleSize) > IMAGE_MAX_DIMENSION) {
                sampleSize *= 2;
            }

            var Base64 = Java.use("android.util.Base64");
            var CompressFormat = Java.use("android.graphics.Bitmap$CompressFormat");
            var ByteArrayOutputStream = Java.use("java.io.ByteArrayOutputStream");

            for (var q = 0; q < IMAGE_QUALITY_ATTEMPTS.length; q++) {
                var decodeOptions = Options.$new();
                decodeOptions.inSampleSize.value = sampleSize;
                var bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOptions);
                if (bitmap === null) throw new Error("This file is not a decodable image.");
                var outputStream = ByteArrayOutputStream.$new();
                bitmap.compress(CompressFormat.JPEG.value, IMAGE_QUALITY_ATTEMPTS[q], outputStream);
                var encodedBytes = outputStream.toByteArray();
                var base64 = Base64.encodeToString(encodedBytes, Base64.NO_WRAP.value).toString();
                bitmap.recycle();
                if (base64.length < (MAX_RESULT_CHARS - 2000)) {
                    return {
                        op: "file_image",
                        base64Jpeg: base64,
                        width: Math.ceil(width / sampleSize),
                        height: Math.ceil(height / sampleSize),
                        originalWidth: width,
                        originalHeight: height,
                        downsampled: sampleSize > 1,
                    };
                }
            }
            throw new Error("Image preview exceeds the command channel size limit even at the lowest quality.");
        }

        function openReadOnlyDatabase(context) {
            var file = resolveRequestedFile(context);
            var SQLiteDatabase = Java.use("android.database.sqlite.SQLiteDatabase");
            return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY.value);
        }

        function quoteIdentifier(value) {
            return "\"" + String(value).replace(/\"/g, "\"\"") + "\"";
        }

        function readTableColumns(db, tableName) {
            var cursor = db.rawQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")", null);
            var columns = [];
            var truncated = false;
            try {
                while (cursor.moveToNext()) {
                    if (columns.length >= DB_MAX_COLUMNS) {
                        truncated = true;
                        break;
                    }
                    columns.push({
                        name: cursor.getString(1),
                        declaredType: cursor.isNull(2) ? "" : cursor.getString(2),
                        notNull: cursor.getInt(3) !== 0,
                        primaryKeyPosition: cursor.getInt(5),
                    });
                }
            } finally {
                cursor.close();
            }
            return { columns: columns, truncated: truncated };
        }

        function previewDatabaseSchema(context) {
            var db = openReadOnlyDatabase(context);
            try {
                var cursor = db.rawQuery(
                    "SELECT type, name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name LIMIT " + (DB_MAX_TABLES + 1),
                    null,
                );
                var payload = { op: "db_schema", tables: [], truncated: false };
                var startedAt = Date.now();
                try {
                    while (cursor.moveToNext()) {
                        if (payload.tables.length >= DB_MAX_TABLES || Date.now() - startedAt >= DB_QUERY_DEADLINE_MS) {
                            payload.truncated = true;
                            break;
                        }
                        var objectType = cursor.getString(0);
                        var name = cursor.getString(1);
                        var columnInfo = readTableColumns(db, name);
                        var table = {
                            name: name,
                            objectType: objectType,
                            columns: columnInfo.columns,
                            columnsTruncated: columnInfo.truncated,
                        };
                        payload.tables.push(table);
                        if (JSON.stringify(payload).length > MAX_RESULT_CHARS - 1000) {
                            payload.tables.pop();
                            payload.truncated = true;
                            break;
                        }
                    }
                } finally {
                    cursor.close();
                }
                return payload;
            } finally {
                db.close();
            }
        }

        function previewDatabaseRows(context) {
            var tableName = request.table;
            if (typeof tableName !== "string" || tableName.length === 0 || tableName.length > 512) throw new Error("Unsupported table name.");
            var offset = Math.max(0, Math.min(100000, Number(request.offset) | 0));
            var searchQuery = typeof request.searchQuery === "string" ? request.searchQuery.slice(0, 160) : "";
            var db = openReadOnlyDatabase(context);
            try {
                var args = Java.array("java.lang.String", [tableName]);
                var existsCursor = db.rawQuery(
                    "SELECT type FROM sqlite_master WHERE name = ? AND type IN ('table', 'view') LIMIT 1",
                    args,
                );
                var objectType = null;
                try {
                    if (existsCursor.moveToFirst()) objectType = existsCursor.getString(0);
                } finally {
                    existsCursor.close();
                }
                if (!objectType) throw new Error("This table is no longer available in the database.");

                var metadata = readTableColumns(db, tableName);
                if (metadata.columns.length === 0) throw new Error("This database object has no browsable columns.");
                var whereClause = "";
                var searchArgs = [];
                if (searchQuery.length > 0) {
                    whereClause = " WHERE " + metadata.columns.map(function (column) {
                        searchArgs.push("%" + searchQuery + "%");
                        return "CAST(" + quoteIdentifier(column.name) + " AS TEXT) LIKE ?";
                    }).join(" OR ");
                }
                var countCursor = db.rawQuery(
                    "SELECT COUNT(*) FROM " + quoteIdentifier(tableName) + whereClause,
                    Java.array("java.lang.String", searchArgs),
                );
                // Frida's Java bridge can expose a Java `long` as a boxed value that JSON.stringify
                // does not serialize as the numeric value. Keep the count as a decimal string;
                // Android's JSONObject.optLong parses it back without losing precision.
                var rowCount = "0";
                try {
                    if (countCursor.moveToFirst()) rowCount = String(countCursor.getString(0));
                } finally {
                    countCursor.close();
                }
                var projections = [];
                var orderColumns = [];
                metadata.columns.forEach(function (column, index) {
                    var quoted = quoteIdentifier(column.name);
                    projections.push("CASE WHEN typeof(" + quoted + ") = 'text' THEN substr(" + quoted + ", 1, " + DB_TEXT_VALUE_MAX_CHARS + ") ELSE " + quoted + " END");
                    projections.push("typeof(" + quoted + ")");
                    projections.push("length(" + quoted + ")");
                    if (column.primaryKeyPosition > 0) orderColumns.push({ name: quoted, position: column.primaryKeyPosition });
                });
                orderColumns.sort(function (left, right) { return left.position - right.position; });

                var sql = "SELECT " + projections.join(", ") + " FROM " + quoteIdentifier(tableName);
                sql += whereClause;
                if (orderColumns.length > 0) sql += " ORDER BY " + orderColumns.map(function (column) { return column.name; }).join(", ");
                sql += " LIMIT ? OFFSET ?";
                var queryArgs = searchArgs.slice();
                queryArgs.push(String(DB_ROWS_PER_PAGE + 1), String(offset));
                var cursor = db.rawQuery(sql, Java.array("java.lang.String", queryArgs));
                try {
                    var rows = [];
                    var approxSize = 300;
                    var hasMore = false;
                    while (cursor.moveToNext()) {
                        if (rows.length >= DB_ROWS_PER_PAGE) { hasMore = true; break; }
                        var row = [];
                        for (var i = 0; i < metadata.columns.length; i++) {
                            var valueIndex = i * 3;
                            var typeIndex = valueIndex + 1;
                            var lengthIndex = valueIndex + 2;
                            var type = cursor.getString(typeIndex);
                            var originalLength = cursor.isNull(lengthIndex) ? 0 : cursor.getLong(lengthIndex);
                            var cell = { storageType: String(type || "null").toUpperCase(), value: null, truncated: false };
                            if (type === "integer") cell.value = cursor.getLong(valueIndex).toString();
                            else if (type === "real") cell.value = cursor.getDouble(valueIndex);
                            else if (type === "text") {
                                cell.value = cursor.getString(valueIndex);
                                cell.truncated = originalLength > DB_TEXT_VALUE_MAX_CHARS;
                            } else if (type === "blob") {
                                cell.blobBytes = originalLength;
                            }
                            row.push(cell);
                        }
                        var rowSize = JSON.stringify(row).length + 4;
                        if (approxSize + rowSize > MAX_RESULT_CHARS - 2500) {
                            if (rows.length === 0) throw new Error("This table row is too wide for the bounded preview.");
                            hasMore = true;
                            break;
                        }
                        approxSize += rowSize;
                        rows.push(row);
                    }
                    return {
                        op: "db_rows",
                        table: tableName,
                        offset: offset,
                        nextOffset: offset + rows.length,
                        columns: metadata.columns.map(function (column) { return column.name; }),
                        rows: rows,
                        rowCount: rowCount,
                        hasMore: hasMore,
                        columnsTruncated: metadata.truncated,
                    };
                } finally {
                    cursor.close();
                }
            } finally {
                db.close();
            }
        }

        function handleRequest(context) {
            if (!request || typeof request.op !== "string") {
                reject(new Error("Malformed preview request."));
                return;
            }
            var payload;
            if (request.op === "db_schema") {
                payload = previewDatabaseSchema(context);
            } else if (request.op === "db_rows") {
                payload = previewDatabaseRows(context);
            } else if (request.op === "file_image") {
                payload = previewFileImage(resolveRequestedFile(context));
            } else if (request.op === "file_hex") {
                payload = previewFileHex(resolveRequestedFile(context));
            } else if (request.op === "file_text") {
                payload = previewFileText(resolveRequestedFile(context));
            } else {
                reject(new Error("Unsupported preview operation."));
                return;
            }

            var serialized = JSON.stringify(payload);
            if (serialized.length > MAX_RESULT_CHARS) {
                reject(new Error("Preview result exceeds the command channel size limit."));
                return;
            }
            resolve(payload);
        }

        waitForJavaRuntime();
    });
})();
