// APK Scope POC.5: Frida Gadget Instrumentation Script
// Hook: okhttp3.CertificatePinner.check
// Purpose: Detect pinning checks and bypass verification for inspection

(function() {
    const TAG = "FridaGadget";
    const SESSION_ID = Java.use("android.os.Build").FINGERPRINT.value.substring(0, 8);

    console.log("[" + TAG + "] Frida Gadget loaded for APK Scope POC");
    console.log("[" + TAG + "] Session: " + SESSION_ID);

    try {
        // Hook okhttp3.CertificatePinner.check method
        const CertificatePinner = Java.use("okhttp3.CertificatePinner");

        CertificatePinner.check.overload("java.lang.String", "java.util.List").implementation = function(hostname, peerCertificates) {
            console.log("[" + TAG + "] CertificatePinner.check() called:");
            console.log("    Hostname: " + hostname);
            console.log("    Certificate count: " + peerCertificates.size());

            // Log to marker file for evidence collection
            try {
                const context = Java.use("android.app.ActivityThread").currentApplication().getApplicationContext();
                const filesDir = context.getFilesDir();
                const evidencePath = filesDir.getAbsolutePath() + "/poc_hook_fired.txt";
                const timestamp = new Date().toISOString();
                const evidence = "Hook fired: " + timestamp + " | Hostname: " + hostname + " | Certs: " + peerCertificates.size() + "\n";

                // Append to file
                const RandomAccessFile = Java.use("java.io.RandomAccessFile");
                const raf = RandomAccessFile.$new(evidencePath, "rw");
                raf.seek(raf.length());
                raf.write(evidence.getBytes());
                raf.close();

                console.log("[" + TAG + "] Evidence logged to: " + evidencePath);
            } catch (e) {
                console.log("[" + TAG + "] Failed to log evidence: " + e);
            }

            // For POC: Log but don't bypass (original behavior)
            // Future: Implement pinning bypass if needed for inspection
            return this.check.call(this, hostname, peerCertificates);
        };

        console.log("[" + TAG + "] Successfully hooked CertificatePinner.check()");

    } catch (e) {
        console.log("[" + TAG + "] Failed to hook CertificatePinner: " + e);
    }

    // Additional hooks can be added here for POC.5+ features

})();
