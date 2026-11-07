/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.Map;
import java.util.Set;

public final class PreferenceUtils {
    private static final String TAG = "PreferenceUtils";

    private static volatile boolean sMigrated = false;

    private PreferenceUtils() {
        // Utility class
    }

    /**
     * Returns the default shared preferences of the app. The app defaults to device
     * protected storage, so preferences written to credential protected storage by
     * older builds are merged over once the user is unlocked.
     */
    public static SharedPreferences getSharedPreferences(Context context) {
        migrateToDeviceProtectedStorage(context);
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static void migrateToDeviceProtectedStorage(Context context) {
        if (sMigrated) {
            return;
        }

        synchronized (PreferenceUtils.class) {
            if (sMigrated) {
                return;
            }

            final UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager == null || !userManager.isUserUnlocked()) {
                // Credential protected storage is not available yet, retry later.
                return;
            }

            final String name = context.getPackageName() + "_preferences";
            final Context ceContext = context.createCredentialProtectedStorageContext();
            final Context deContext = context.createDeviceProtectedStorageContext();

            try {
                final Map<String, ?> legacy =
                        ceContext.getSharedPreferences(name, Context.MODE_PRIVATE).getAll();
                if (!legacy.isEmpty()) {
                    final SharedPreferences target =
                            deContext.getSharedPreferences(name, Context.MODE_PRIVATE);
                    final SharedPreferences.Editor editor = target.edit();
                    for (Map.Entry<String, ?> entry : legacy.entrySet()) {
                        // Keep anything already written to device protected storage.
                        if (target.contains(entry.getKey())) {
                            continue;
                        }
                        putValue(editor, entry.getKey(), entry.getValue());
                    }
                    editor.commit();
                    Log.i(TAG, "Migrated " + legacy.size() + " preferences to DE storage");
                }
                ceContext.deleteSharedPreferences(name);
            } catch (Exception e) {
                Log.e(TAG, "Failed to migrate preferences to DE storage", e);
            }

            sMigrated = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }
}
