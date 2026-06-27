/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.lsposed.lspd.models.UserInfo;
import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.repo.model.OnlineModule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public final class ModuleUtil {
    // xposedminversion below this
    public static int MIN_MODULE_VERSION = 2; // reject modules with
    public static final int MIN_OUTDATED_MODERN_MODULE_API = 100;
    public static final int MIN_SUPPORTED_MODERN_MODULE_API = 101;
    private static final int MODERN_API_VERSION = MIN_SUPPORTED_MODERN_MODULE_API;
    private static final String IGNORED_MODULE_UPDATES = "ignored_module_updates";
    private static ModuleUtil instance = null;
    private final PackageManager pm;
    private final Set<ModuleListener> listeners = ConcurrentHashMap.newKeySet();
    private HashSet<String> enabledModules = new HashSet<>();
    private List<UserInfo> users = new ArrayList<>();
    private Map<Pair<String, Integer>, InstalledModule> installedModules = new HashMap<>();
    private boolean modulesLoaded = false;

    static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER

    static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    private ModuleUtil() {
        pm = App.getInstance().getPackageManager();
    }

    public boolean isModulesLoaded() {
        return modulesLoaded;
    }

    public static synchronized ModuleUtil getInstance() {
        if (instance == null) {
            instance = new ModuleUtil();
            App.getExecutorService().submit(instance::reloadInstalledModules);
        }
        return instance;
    }

    @NonNull
    public static Set<String> getIgnoredModuleUpdates() {
        return new HashSet<>(App.getPreferences().getStringSet(IGNORED_MODULE_UPDATES, Collections.emptySet()));
    }

    public static boolean isUpdateIgnored(@NonNull String packageName) {
        return getIgnoredModuleUpdates().contains(packageName);
    }

    public static void setUpdateIgnored(@NonNull String packageName, boolean ignored) {
        var ignoredModules = getIgnoredModuleUpdates();
        boolean changed = ignored ? ignoredModules.add(packageName) : ignoredModules.remove(packageName);
        if (!changed) {
            return;
        }
        App.getPreferences().edit().putStringSet(IGNORED_MODULE_UPDATES, ignoredModules).apply();
        getInstance().listeners.forEach(listener -> listener.onModuleUpdateIgnoreChanged(packageName));
    }

    public static int extractIntPart(String str) {
        int result = 0, length = str.length();
        for (int offset = 0; offset < length; offset++) {
            char c = str.charAt(offset);
            if ('0' <= c && c <= '9')
                result = result * 10 + (c - '0');
            else
                break;
        }
        return result;
    }

    private static int extractIntPart(String str, int fallback) {
        return TextUtils.isEmpty(str) ? fallback : extractIntPart(str);
    }

    @Nullable
    public static ZipFile getModuleApk(ApplicationInfo info) {
        String[] apks;
        if (info.splitSourceDirs != null) {
            apks = Arrays.copyOf(info.splitSourceDirs, info.splitSourceDirs.length + 1);
            apks[info.splitSourceDirs.length] = info.sourceDir;
        } else apks = new String[]{info.sourceDir};
        ZipFile zip = null;
        for (var apk : apks) {
            try {
                zip = new ZipFile(apk);
                if (hasAnyModuleInitEntry(zip)) {
                    return zip;
                }
                zip.close();
                zip = null;
            } catch (IOException ignored) {
            }
        }
        return zip;
    }

    private static boolean hasAnyModuleInitEntry(ZipFile zip) {
        return hasModernInitEntry(zip) || hasLegacyInitEntry(zip);
    }

    private static boolean hasModernInitEntry(ZipFile zip) {
        return zip.getEntry("META-INF/xposed/java_init.list") != null ||
                zip.getEntry("META-INF/xposed/native_init.list") != null;
    }

    private static boolean hasLegacyInitEntry(ZipFile zip) {
        return zip.getEntry("assets/xposed_init") != null ||
                zip.getEntry("assets/native_init") != null;
    }

    public static boolean isLegacyModule(ApplicationInfo info) {
        return info.metaData != null && info.metaData.containsKey("xposedminversion");
    }

    private static int readLegacyMinVersion(ApplicationInfo info) {
        if (info.metaData == null) return 0;
        Object minVersionRaw = info.metaData.get("xposedminversion");
        if (minVersionRaw instanceof Integer) {
            return (Integer) minVersionRaw;
        } else if (minVersionRaw instanceof String) {
            return extractIntPart((String) minVersionRaw);
        } else {
            return 0;
        }
    }

    synchronized public void reloadInstalledModules() {
        modulesLoaded = false;
        if (!ConfigManager.isBinderAlive()) {
            modulesLoaded = true;
            return;
        }

        Map<Pair<String, Integer>, InstalledModule> modules = new HashMap<>();
        var users = ConfigManager.getUsers();
        for (PackageInfo pkg : ConfigManager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false)) {
            ApplicationInfo app = pkg.applicationInfo;

            var moduleApk = getModuleApk(app);
            if (moduleApk != null || isLegacyModule(app)) {
                modules.computeIfAbsent(Pair.create(pkg.packageName, app.uid / App.PER_USER_RANGE), k -> new InstalledModule(pkg, moduleApk));
            }
        }

        installedModules = modules;

        this.users = users;

        enabledModules = new HashSet<>(Arrays.asList(ConfigManager.getEnabledModules()));
        modulesLoaded = true;
        listeners.forEach(ModuleListener::onModulesReloaded);
    }

    @Nullable
    public List<UserInfo> getUsers() {
        return modulesLoaded ? users : null;
    }

    public InstalledModule reloadSingleModule(String packageName, int userId) {
        return reloadSingleModule(packageName, userId, false);
    }

    public InstalledModule reloadSingleModule(String packageName, int userId, boolean packageFullyRemoved) {
        if (packageFullyRemoved && isModuleEnabled(packageName)) {
            enabledModules.remove(packageName);
            listeners.forEach(ModuleListener::onModulesReloaded);
        }
        PackageInfo pkg;

        try {
            pkg = ConfigManager.getPackageInfo(packageName, PackageManager.GET_META_DATA, userId);
        } catch (NameNotFoundException e) {
            InstalledModule old = installedModules.remove(Pair.create(packageName, userId));
            if (old != null) listeners.forEach(i -> i.onSingleModuleReloaded(old));
            return null;
        }

        ApplicationInfo app = pkg.applicationInfo;
        var moduleApk = getModuleApk(app);
        if (moduleApk != null || isLegacyModule(app)) {
            InstalledModule module = new InstalledModule(pkg, moduleApk);
            installedModules.put(Pair.create(packageName, userId), module);
            listeners.forEach(i -> i.onSingleModuleReloaded(module));
            return module;
        } else {
            InstalledModule old = installedModules.remove(Pair.create(packageName, userId));
            if (old != null) listeners.forEach(i -> i.onSingleModuleReloaded(old));
            return null;
        }
    }

    @Nullable
    public InstalledModule getModule(String packageName, int userId) {
        return modulesLoaded ? installedModules.get(Pair.create(packageName, userId)) : null;
    }

    @Nullable
    public InstalledModule getModule(String packageName) {
        return getModule(packageName, 0);
    }

    @Nullable
    synchronized public Map<Pair<String, Integer>, InstalledModule> getModules() {
        return modulesLoaded ? installedModules : null;
    }

    public boolean setModuleEnabled(String packageName, boolean enabled) {
        if (!ConfigManager.setModuleEnabled(packageName, enabled)) {
            return false;
        }
        if (enabled) {
            enabledModules.add(packageName);
        } else {
            enabledModules.remove(packageName);
        }
        return true;
    }

    public boolean isModuleEnabled(String packageName) {
        return enabledModules.contains(packageName);
    }

    public int getEnabledModulesCount() {
        return modulesLoaded ? enabledModules.size() : -1;
    }

    public void addListener(ModuleListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ModuleListener listener) {
        listeners.remove(listener);
    }

    public interface ModuleListener {
        /**
         * Called whenever one (previously or now) installed module has been
         * reloaded
         */
        default void onSingleModuleReloaded(InstalledModule module) {

        }

        default void onModulesReloaded() {

        }

        default void onModuleUpdateIgnoreChanged(String packageName) {

        }
    }

    public class InstalledModule {
        //private static final int FLAG_FORWARD_LOCK = 1 << 29;
        public final int userId;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final boolean legacy;
        public final boolean supportsLegacyApi;
        public final int minVersion;
        public final int targetVersion;
        public final boolean staticScope;
        public final long installTime;
        public final long updateTime;
        public final ApplicationInfo app;
        public final PackageInfo pkg;
        private String appName; // loaded lazily
        private String description; // loaded lazily
        private List<String> scopeList; // loaded lazily

        private InstalledModule(PackageInfo pkg, ZipFile moduleApk) {
            app = pkg.applicationInfo;
            this.pkg = pkg;
            userId = pkg.applicationInfo.uid / App.PER_USER_RANGE;
            packageName = pkg.packageName;
            versionName = pkg.versionName;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                versionCode = pkg.versionCode;
            } else {
                versionCode = pkg.getLongVersionCode();
            }
            installTime = pkg.firstInstallTime;
            updateTime = pkg.lastUpdateTime;

            int parsedLegacyMinVersion = readLegacyMinVersion(app);
            boolean legacy = isLegacyModule(app);
            boolean supportsLegacyApi = legacy && parsedLegacyMinVersion < MIN_OUTDATED_MODERN_MODULE_API;
            int minVersion = legacy ? parsedLegacyMinVersion : 0;
            int targetVersion = legacy ? parsedLegacyMinVersion : 0;
            boolean staticScope = false;

            if (moduleApk != null) {
                try (moduleApk) {
                    boolean hasModernEntry = hasModernInitEntry(moduleApk);
                    boolean hasLegacyEntry = hasLegacyInitEntry(moduleApk);

                    int parsedMinVersion = 0;
                    int parsedTargetVersion = 0;
                    var propEntry = moduleApk.getEntry("META-INF/xposed/module.prop");
                    if (propEntry != null) {
                        var prop = new Properties();
                        prop.load(moduleApk.getInputStream(propEntry));
                        parsedMinVersion = extractIntPart(prop.getProperty("minApiVersion"), 0);
                        parsedTargetVersion = extractIntPart(prop.getProperty("targetApiVersion"), 0);
                        staticScope = TextUtils.equals(prop.getProperty("staticScope"), "true");
                    }
                    int legacyMinVersion = parsedLegacyMinVersion != 0 ? parsedLegacyMinVersion : parsedMinVersion;
                    supportsLegacyApi = hasLegacyEntry && legacyMinVersion < MIN_OUTDATED_MODERN_MODULE_API;

                    // targetApiVersion defines the modern API generation shown in the tag.
                    // API 100 hybrid modules are shown as legacy because Vector does not support
                    // API 100 but can still use their legacy entrypoint.
                    int displayApiVersion = parsedTargetVersion >= MIN_OUTDATED_MODERN_MODULE_API
                            ? parsedTargetVersion
                            : parsedMinVersion;
                    boolean isModernApiModule = hasModernEntry && displayApiVersion >= MODERN_API_VERSION;
                    boolean isApi100OnlyModule = hasModernEntry &&
                            displayApiVersion == MIN_OUTDATED_MODERN_MODULE_API &&
                            !hasLegacyEntry;
                    if (isModernApiModule || isApi100OnlyModule) {
                        legacy = false;
                        minVersion = parsedMinVersion;
                        targetVersion = displayApiVersion;

                        var scopeEntry = moduleApk.getEntry("META-INF/xposed/scope.list");
                        if (scopeEntry != null) {
                            try (var reader = new BufferedReader(new InputStreamReader(moduleApk.getInputStream(scopeEntry)))) {
                                scopeList = reader.lines().collect(Collectors.toList());
                            }
                        } else {
                            scopeList = Collections.emptyList();
                        }
                    } else if (hasLegacyEntry || (!hasModernEntry && isLegacyModule(app))) {
                        legacy = true;
                        minVersion = parsedLegacyMinVersion != 0 ? parsedLegacyMinVersion : parsedMinVersion;
                        targetVersion = minVersion;
                        staticScope = false;
                    } else {
                        legacy = false;
                        minVersion = 0;
                        targetVersion = 0;
                        staticScope = false;
                        scopeList = Collections.emptyList();
                    }
                } catch (IOException | OutOfMemoryError e) {
                    Log.e(App.TAG, "Error while parsing module APK", e);
                }
            }

            this.legacy = legacy;
            this.supportsLegacyApi = supportsLegacyApi;
            this.minVersion = minVersion;
            this.targetVersion = targetVersion;
            this.staticScope = staticScope;
        }

        public boolean isInstalledOnExternalStorage() {
            return (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
        }

        public String getAppName() {
            if (appName == null)
                appName = app.loadLabel(pm).toString();
            return appName;
        }

        public String getDescription() {
            if (this.description != null) return this.description;
            String descriptionTmp = "";
            if (legacy) {
                if (app.metaData != null) {
                    Object descriptionRaw = app.metaData.get("xposeddescription");
                    if (descriptionRaw instanceof String) {
                        descriptionTmp = ((String) descriptionRaw).trim();
                    } else if (descriptionRaw instanceof Integer) {
                        try {
                            int resId = (Integer) descriptionRaw;
                            if (resId != 0)
                                descriptionTmp = pm.getResourcesForApplication(app).getString(resId).trim();
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else {
                var des = app.loadDescription(pm);
                if (des != null) descriptionTmp = des.toString();
            }
            this.description = descriptionTmp;
            return this.description;
        }

        public List<String> getScopeList() {
            if (scopeList != null) return scopeList;
            List<String> list = null;
            try {
                if (app.metaData != null) {
                    int scopeListResourceId = app.metaData.getInt("xposedscope");
                    if (scopeListResourceId != 0) {
                        list = Arrays.asList(pm.getResourcesForApplication(app).getStringArray(scopeListResourceId));
                    } else {
                        String scopeListString = app.metaData.getString("xposedscope");
                        if (scopeListString != null)
                            list = Arrays.asList(scopeListString.split(";"));
                    }
                }
            } catch (Exception ignored) {
            }
            if (list == null) {
                OnlineModule module = RepoLoader.getInstance().getOnlineModule(packageName);
                if (module != null && module.getScope() != null) {
                    list = module.getScope();
                }
            }
            if (list != null) {
                //For historical reasons, legacy modules use the opposite name.
                //https://github.com/rovo89/XposedBridge/commit/6b49688c929a7768f3113b4c65b429c7a7032afa
                list.replaceAll(s ->
                    switch (s) {
                        case "android" -> "system";
                        case "system" -> "android";
                        default -> s;
                    }
                );
                scopeList = list;
            }
            return scopeList;
        }

        public PackageInfo getPackageInfo() {
            return pkg;
        }

        @NonNull
        @Override
        public String toString() {
            return getAppName();
        }
    }
}
