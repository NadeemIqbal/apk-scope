package com.nadeem.apkscope;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class FridaLoaderFactory extends AppComponentFactory {
    private static final String TAG = "FridaLoaderFactory";
    private static volatile boolean isGadgetLoaded = false;
    private volatile AppComponentFactory delegate = null;
    private volatile boolean delegateChecked = false;

    public FridaLoaderFactory() {
        super();
        ensureGadgetLoaded();
    }

    private synchronized void ensureGadgetLoaded() {
        if (isGadgetLoaded) return;
        try {
            System.loadLibrary("gadget");
            Log.i(TAG, "Frida Gadget loaded successfully via AppComponentFactory!");
            isGadgetLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load Frida Gadget: " + t.getMessage(), t);
        }
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
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateApplication(cl, className);
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        ensureGadgetLoaded();
        AppComponentFactory d = getDelegate(cl);
        if (d != null) {
            return d.instantiateClassLoader(cl, aInfo);
        }
        return super.instantiateClassLoader(cl, aInfo);
    }
}
