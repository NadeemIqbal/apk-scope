package com.nadeem.apkscope;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class FridaLoaderFactory extends AppComponentFactory {
    private static final String TAG = "FridaLoaderFactory";
    private static final String CHANNEL_AUTHORITY = "com.nadeem.apkscope.frida.channel";
    private static final String CHANNEL_METHOD_GET_TOKEN = "get_channel_token";
    private static final String CHANNEL_TOKEN_KEY = "token";
    private static final String CHANNEL_TOKEN_FILE = "apk_scope_channel_token";
    private static volatile boolean isGadgetLoaded = false;
    private static volatile boolean gadgetLoadScheduled = false;
    // Activity#onResume is still inside ActivityThread's resume transaction on some recent
    // Android builds. Loading Gadget synchronously from the lifecycle callback can therefore
    // start the script before Frida has attached its ART bridge, leaving Java undefined for the
    // lifetime of that script. Give the framework a short idle window after the first resume.
    private static final long FIRST_RESUME_LOAD_DELAY_MS = 3000L;
    private static final long NO_ACTIVITY_FALLBACK_DELAY_MS = 10000L;
    private volatile AppComponentFactory delegate = null;
    private volatile boolean delegateChecked = false;
    private volatile Application targetApplication = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedActivityLoad = new Runnable() {
        @Override public void run() {
            ensureGadgetLoaded();
        }
    };

    public FridaLoaderFactory() {
        super();
    }

    private synchronized void ensureGadgetLoaded() {
        Log.i(TAG, "ensureGadgetLoaded tokenHandoffVersion=private-file-v1 targetApplication="
                + (targetApplication != null));
        if (isGadgetLoaded) return;
        try {
            provisionChannelToken(targetApplication);
            System.loadLibrary("gadget");
            Log.i(TAG, "Frida Gadget loaded successfully via AppComponentFactory!");
            isGadgetLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load Frida Gadget: " + t.getMessage(), t);
        }
    }

    /**
     * Frida Gadget's script runtime is not guaranteed to expose the ART Java bridge when Gadget
     * is loaded from an injected AppComponentFactory. The script still needs a target-bound
     * credential, so obtain it while we are in ordinary target Java code and hand it to the
     * script through a private file in the target application's own data directory.
     *
     * The provider authenticates the Binder caller UID. The token is never embedded in the APK,
     * passed through an Intent, or exposed through a user-selectable package/PID. The file is
     * created immediately before Gadget loads and inherits the target app's private storage.
     */
    private void provisionChannelToken(Application application) {
        Log.i(TAG, "provisionChannelToken entered application=" + (application != null));
        if (application == null) {
            Log.w(TAG, "Cannot provision Frida channel token: target Application is null");
            return;
        }
        try {
            Bundle result = application.getContentResolver().call(
                    Uri.parse("content://" + CHANNEL_AUTHORITY),
                    CHANNEL_METHOD_GET_TOKEN,
                    null,
                    null
            );
            String token = result == null ? null : result.getString(CHANNEL_TOKEN_KEY);
            if (token == null || !token.matches("[0-9a-fA-F]{64}")) {
                Log.w(TAG, "APK Scope returned no usable Frida channel token");
                return;
            }

            File tokenFile = new File(application.getFilesDir(), CHANNEL_TOKEN_FILE);
            try (FileOutputStream output = new FileOutputStream(tokenFile, false)) {
                output.write(token.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
            // Keep the handoff file private even on filesystems where the default mode is
            // influenced by process umask or a previous run.
            tokenFile.setReadable(false, false);
            tokenFile.setWritable(false, false);
            tokenFile.setExecutable(false, false);
            tokenFile.setReadable(true, true);
            tokenFile.setWritable(true, true);
            Log.i(TAG, "Provisioned target-bound Frida channel token for Gadget script");
        } catch (Throwable t) {
            // Gadget can still load; the script will retry reading the handoff file. This keeps
            // the loader compatible with targets where the provider is temporarily unavailable.
            Log.w(TAG, "Could not provision Frida channel token: " + t.getMessage());
        }
    }

    /**
     * AppComponentFactory callbacks run during ActivityThread startup, before the target has
     * reached a stable Activity lifecycle. Loading Gadget from that callback is racy: the script
     * can start while Frida's ART bridge still reports Java as unavailable. Register the load at
     * the target Application's first resumed Activity instead. That is the first lifecycle point
     * at which the Android runtime, Application, class loader, and Activity are all fully live.
     *
     * The delayed fallback keeps service/receiver-only targets supported. It is deliberately much
     * later than bindApplication and is cancelled logically by isGadgetLoaded when an Activity
     * resumes first.
     */
    private synchronized void scheduleGadgetLoad(Application application) {
        if (isGadgetLoaded || gadgetLoadScheduled) return;
        targetApplication = application;
        gadgetLoadScheduled = true;
        try {
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    // Do not load inline: this callback can run before ActivityThread has
                    // finished the resume transaction and before Frida's Java bridge is ready.
                    mainHandler.postDelayed(delayedActivityLoad, FIRST_RESUME_LOAD_DELAY_MS);
                }

                @Override public void onActivityCreated(Activity activity, android.os.Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
            Log.i(TAG, "Frida Gadget load armed for first resumed Activity");
        } catch (Throwable t) {
            Log.w(TAG, "Could not register Activity lifecycle callback: " + t.getMessage(), t);
        }

        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                ensureGadgetLoaded();
            }
        }, NO_ACTIVITY_FALLBACK_DELAY_MS);
        Log.i(TAG, "Frida Gadget load fallback scheduled after target startup");
    }

    private AppComponentFactory getDelegate(ClassLoader cl) {
        if (delegateChecked) return delegate;
        synchronized (this) {
            if (delegateChecked) return delegate;
            delegateChecked = true;
            try {
                InputStream is = cl.getResourceAsStream("assets/poc_orig_factory.txt");
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        String className = line.trim();
                        Class<?> clazz = cl.loadClass(className);
                        delegate = (AppComponentFactory) clazz.getDeclaredConstructor().newInstance();
                        Log.i(TAG, "Loaded delegate AppComponentFactory: " + className);
                    }
                    reader.close();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not load delegate AppComponentFactory: " + t.getMessage());
            }
        }
        return delegate;
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory d = getDelegate(cl);
        Application application;
        if (d != null) {
            application = d.instantiateApplication(cl, className);
        } else {
            application = super.instantiateApplication(cl, className);
        }
        scheduleGadgetLoad(application);
        return application;
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateClassLoader(cl, aInfo);
        }
        return super.instantiateClassLoader(cl, aInfo);
    }
}
