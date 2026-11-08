/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.providers.contacts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * IchthysOS: the contacts the user picked for an app whose Guard contacts mode is "picked"
 * ({@code pkg:picked} in {@code ichthys_contacts_allowed_pkgs}). Such an app reads only these
 * contacts; every other contact stays invisible, as in the lockdown.
 *
 * <p>The set is kept per package as contact <em>lookup keys</em>: contact ids change when contacts
 * are joined, split or restored, lookup keys survive that ({@link
 * ContactsProvider2#lookupContactIdByLookupKey}). It lives in this provider's own preferences,
 * which are per user like the provider. Only Settings (Guard) writes it, through {@link
 * ContactsProvider2#call}.
 *
 * <p>The resolved contact ids are cached per package and dropped whenever the provider's data
 * changes ({@link #onDataChanged}), and after {@link #MAX_CACHE_AGE_MS} at the latest.
 */
final class IchthysPickedContacts {

    private static final String TAG = "IchthysPicked";
    private static final String PREFS = "ichthys_picked_contacts";
    private static final long MAX_CACHE_AGE_MS = 60_000;

    /** Hard cap on one app's set; the clause is built from integers, but keep it bounded. */
    static final int MAX_PICKED = 5000;

    private static final class Resolved {
        final long generation;
        final long time;
        final long[] ids;

        Resolved(long generation, long time, long[] ids) {
            this.generation = generation;
            this.time = time;
            this.ids = ids;
        }
    }

    private final Context mContext;
    private final Object mLock = new Object();
    private final ArrayMap<String, Resolved> mCache = new ArrayMap<>();
    private long mGeneration;

    IchthysPickedContacts(Context context) {
        mContext = context;
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The lookup keys picked for {@code pkg}; empty when none. */
    List<String> getLookupKeys(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return Collections.emptyList();
        }
        final Set<String> stored = prefs().getStringSet(pkg, null);
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> keys = new ArrayList<>(stored);
        Collections.sort(keys);
        return keys;
    }

    /** Replaces {@code pkg}'s set; an empty or null collection removes it. */
    void setLookupKeys(String pkg, Collection<String> lookupKeys) {
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        final Set<String> keys = new HashSet<>();
        if (lookupKeys != null) {
            for (String key : lookupKeys) {
                if (!TextUtils.isEmpty(key) && keys.size() < MAX_PICKED) {
                    keys.add(key);
                }
            }
        }
        final SharedPreferences.Editor editor = prefs().edit();
        if (keys.isEmpty()) {
            editor.remove(pkg);
        } else {
            editor.putStringSet(pkg, keys);
        }
        editor.commit();
        synchronized (mLock) {
            mCache.remove(pkg);
        }
    }

    /** Forgets {@code pkg}'s set (the app was uninstalled). */
    void remove(String pkg) {
        if (prefs().contains(pkg)) {
            Log.i(TAG, "Dropping the picked contacts of removed package " + pkg);
            setLookupKeys(pkg, null);
        }
    }

    /** The provider's data changed: contact ids may have moved. */
    void onDataChanged() {
        synchronized (mLock) {
            mGeneration++;
        }
    }

    /**
     * The contact ids picked for {@code pkg}, sorted, resolved with {@code resolver} (lookup key
     * to contact id, -1 when gone). Never null; empty when nothing is picked.
     */
    long[] contactIds(String pkg, ToLongFunction<String> resolver) {
        final long now = SystemClock.elapsedRealtime();
        final long generation;
        synchronized (mLock) {
            final Resolved cached = mCache.get(pkg);
            if (cached != null && cached.generation == mGeneration
                    && now - cached.time < MAX_CACHE_AGE_MS) {
                return cached.ids;
            }
            generation = mGeneration;
        }
        final List<String> keys = getLookupKeys(pkg);
        final long[] found = new long[keys.size()];
        int n = 0;
        for (String key : keys) {
            long id;
            try {
                id = resolver.applyAsLong(key);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot resolve a picked contact of " + pkg, e);
                id = -1;
            }
            if (id > 0) {
                found[n++] = id;
            }
        }
        long[] ids = Arrays.copyOf(found, n);
        Arrays.sort(ids);
        ids = distinct(ids);
        synchronized (mLock) {
            if (generation == mGeneration) {
                mCache.put(pkg, new Resolved(generation, now, ids));
            }
        }
        return ids;
    }

    /** Whether sorted {@code ids} holds {@code id}. */
    static boolean contains(long[] ids, long id) {
        return ids != null && Arrays.binarySearch(ids, id) >= 0;
    }

    /** {@code column IN (ids)}, or the always-false "0" for an empty set. Integers only. */
    static String inClause(String column, long[] ids) {
        if (ids == null || ids.length == 0) {
            return "0";
        }
        final StringBuilder sb = new StringBuilder(column.length() + ids.length * 6 + 8);
        sb.append(column).append(" IN (");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
        }
        return sb.append(')').toString();
    }

    private static long[] distinct(long[] sorted) {
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[n++] = sorted[i];
            }
        }
        return n == sorted.length ? sorted : Arrays.copyOf(sorted, n);
    }
}
