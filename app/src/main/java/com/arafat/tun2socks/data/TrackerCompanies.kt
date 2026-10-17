package com.arafat.tun2socks.data

/** Maps tracker domains to the company behind them for the App Tracker Report. */
object TrackerCompanies {
    private val companies = mapOf(
        "Google" to listOf(
            "doubleclick.net", "google-analytics.com", "googleadservices.com", "googlesyndication.com", "googletagmanager.com",
            "googletagservices.com", "app-measurement.com", "crashlytics.com", "admob.com", "adservice.google.com",
            "firebase-settings.crashlytics.com", "firebaselogging-pa.googleapis.com", "app-analytics-services.com",
        ),
        "Meta (Facebook)" to listOf("facebook.net", "graph.facebook.com", "an.facebook.com", "connect.facebook.net", "fbsbx.com"),
        "Amazon" to listOf("amazon-adsystem.com", "aax.amazon-adsystem.com", "assoc-amazon.com"),
        "Microsoft" to listOf("clarity.ms", "bat.bing.com", "ads.msn.com", "app-center.ms", "vortex.data.microsoft.com"),
        "AppsFlyer" to listOf("appsflyer.com", "appsflyersdk.com"),
        "Adjust" to listOf("adjust.com", "adjust.io", "adj.st"),
        "Branch" to listOf("branch.io", "app.link"),
        "Kochava" to listOf("kochava.com"),
        "Singular" to listOf("singular.net", "sng.link"),
        "Unity" to listOf("unityads.unity3d.com", "unity3d.com", "ironsrc.com", "ironsrc.mobi", "supersonicads.com"),
        "AppLovin" to listOf("applovin.com", "applvn.com"),
        "Liftoff / Vungle" to listOf("vungle.com", "liftoff.io"),
        "Chartboost" to listOf("chartboost.com"),
        "InMobi" to listOf("inmobi.com"),
        "Mintegral" to listOf("rayjump.com", "mintegral.com", "mtgglobals.com"),
        "ByteDance (TikTok / Pangle)" to listOf("pangle.io", "pangleglobal.com", "byteoversea.com", "tiktokv.com", "isnssdk.com"),
        "Yandex" to listOf("appmetrica.yandex.net", "mc.yandex.ru", "an.yandex.ru"),
        "Xiaomi" to listOf("tracking.miui.com", "data.mistat.xiaomi.com", "sdkconfig.ad.xiaomi.com"),
        "Adobe" to listOf("omtrdc.net", "2o7.net", "demdex.net", "adobedc.net", "everesttech.net"),
        "comScore" to listOf("scorecardresearch.com", "comscore.com"),
        "Criteo" to listOf("criteo.com", "criteo.net"),
        "Taboola" to listOf("taboola.com"),
        "Outbrain" to listOf("outbrain.com"),
        "Hotjar" to listOf("hotjar.com"),
        "Mixpanel" to listOf("mixpanel.com"),
        "Amplitude" to listOf("amplitude.com"),
        "Segment" to listOf("segment.io", "segment.com"),
        "New Relic" to listOf("newrelic.com", "nr-data.net"),
        "Bugsnag" to listOf("bugsnag.com"),
        "OneSignal" to listOf("onesignal.com"),
        "Braze" to listOf("braze.com", "appboy.com"),
        "Eulerian" to listOf("eulerian.net"),
        "AT Internet" to listOf("xiti.com", "ati-host.net", "at-o.net"),
        "Quantcast" to listOf("quantserve.com", "quantcount.com"),
        "Taplytics / Moat" to listOf("moatads.com"),
        "Startapp" to listOf("startappservice.com", "startapp.com"),
        "AdColony" to listOf("adcolony.com"),
        "Twitter / X" to listOf("ads-twitter.com", "analytics.twitter.com", "mopub.com"),
        "Snap" to listOf("sc-analytics.appspot.com", "tr.snapchat.com"),
    )

    private val bySuffix: Map<String, String> = buildMap {
        companies.forEach { (company, domains) -> domains.forEach { put(it, company) } }
    }

    fun companyFor(domain: String): String {
        var d = domain.lowercase()
        while (true) {
            bySuffix[d]?.let { return it }
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
        }
        return registrableDomain(domain)
    }

    fun registrableDomain(domain: String): String {
        val parts = domain.lowercase().trimEnd('.').split('.')
        if (parts.size <= 2) return domain
        val sld = parts[parts.size - 2]
        val take = if (sld.length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }
}
