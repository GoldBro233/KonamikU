package org.cf0x.konamiku.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.cf0x.konamiku.data.AppDataStore
import org.cf0x.konamiku.data.AppLocale

/*
 * Where the language choice lives:
 *  - API 33+: the system's per-app language (LocaleManager) is the truth. An
 *    empty list means "follow the system", including after the user cleared
 *    it in system Settings. DataStore only mirrors it for the settings UI,
 *    apart from a one-time migration of the choice older builds stored there.
 *  - API 29-32: DataStore is the truth and AppCompat applies it to
 *    AppCompatActivity. Other contexts (services, receivers, notifications)
 *    are not covered by AppCompat and resolve strings through [localized].
 */

/** Applies a language choice. A blank tag ([AppLocale.SYSTEM]) clears the override. */
fun Context.applyLocale(tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        runCatching {
            val lm = getSystemService(android.app.LocaleManager::class.java) ?: return@runCatching
            lm.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    } else {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }
}

/**
 * API 33+: the per-app language the system holds, [AppLocale.SYSTEM] when
 * none is set. Null below 33, when it cannot be read, or when it names a
 * language this app does not offer.
 */
fun Context.systemAppLocale(): AppLocale? {
    if (Build.VERSION.SDK_INT < 33) return null
    val locales = runCatching {
        getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
    }.getOrNull() ?: return null
    val first = locales.get(0) ?: return AppLocale.SYSTEM
    return AppLocale.fromSystemTag(first.toLanguageTag())
}

/**
 * API 33+: copies the system's per-app language into DataStore so the
 * settings screen shows what is actually in effect.
 */
suspend fun Context.mirrorSystemLocale(dataStore: AppDataStore) {
    val current = systemAppLocale() ?: return
    if (dataStore.appLocaleSnapshot().locale != current) dataStore.saveAppLocale(current)
}

/**
 * Below API 33, a context whose resources use the language chosen in the
 * app. AppCompat only applies that choice to AppCompatActivity, so anything
 * else that shows text (notifications, toasts, services) goes through here.
 * On 33+ the system applies the per-app language to every context already.
 */
fun Context.localized(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(config)
}
