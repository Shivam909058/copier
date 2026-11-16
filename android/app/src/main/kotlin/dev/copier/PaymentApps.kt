package dev.copier

/**
 * Apps that refuse to run while a sideloaded accessibility service is on. Automatic copy turns
 * itself off when one of them opens (CopyWatcher.pauseFor); people can edit the list in the app.
 */
object PaymentApps {
    // ponytail: a starter list of common Indian UPI, wallet and bank apps; unknown ids never match,
    // and the in-app picker covers everything else.
    val KNOWN: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "com.dreamplug.androidapp", // CRED
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.sbi.lotusintouch", // YONO SBI
        "com.sbi.SBIFreedomPlus",
        "com.sbi.upi",
        "com.snapwork.hdfc",
        "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.upi.axispay",
        "com.msf.kbank.mobile", // Kotak
        "com.bankofbaroda.mconnect",
        "com.idfcfirstbank.optimus",
        "com.fedmobile",
        "com.canarabank.mobility",
        "com.infrasoft.uboi", // Union Bank
        "money.jupiter",
        "com.epifi.paisa", // Fi
        "com.naviapp",
    )
}
