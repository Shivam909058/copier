package dev.copier

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import dev.copier.proto.Crypto
import dev.copier.proto.Identity
import dev.copier.proto.Proto
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val TAG = "Copier"

/** The Mac this phone is paired with. `host`/`port` remember the last address that worked. */
class PairedMac(val id: String, val name: String, val publicKey: ByteArray, val host: String?, val port: Int)

/** Process-wide persistent state: identity, pairing, settings. Holds no Context, so it's safe to keep. */
class Store private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("copier", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "identity.bin"))

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    val appVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    val identity: Identity by lazy { loadIdentity() ?: createIdentity() }

    val localDevice: LocalDevice get() = LocalDevice(identity, deviceName, appVersion)

    var pairedMac: PairedMac?
        get() {
            val id = prefs.getString(MAC_ID, null) ?: return null
            val pub = prefs.getString(MAC_PUB, null) ?: return null
            return PairedMac(
                id = id,
                name = prefs.getString(MAC_NAME, null) ?: "Mac",
                publicKey = Base64.getDecoder().decode(pub),
                host = prefs.getString(MAC_HOST, null),
                port = prefs.getInt(MAC_PORT, Proto.PORT),
            )
        }
        set(mac) {
            val edit = prefs.edit()
            if (mac == null) {
                edit.remove(MAC_ID).remove(MAC_NAME).remove(MAC_PUB).remove(MAC_HOST).remove(MAC_PORT)
            } else {
                edit.putString(MAC_ID, mac.id).putString(MAC_NAME, mac.name)
                    .putString(MAC_PUB, Base64.getEncoder().encodeToString(mac.publicKey))
                    .putString(MAC_HOST, mac.host).putInt(MAC_PORT, mac.port)
            }
            edit.apply()
        }

    fun rememberMac(name: String, host: String, port: Int) {
        prefs.edit().putString(MAC_NAME, name).putString(MAC_HOST, host).putInt(MAC_PORT, port).apply()
    }

    var autoClipboard: Boolean
        get() = prefs.getBoolean(AUTO_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(AUTO_CLIPBOARD, value).apply()

    /** Also check the clipboard after menus and dialogs close (CopyDetector.thorough). */
    var thoroughCopy: Boolean
        // Unless the user chose: on only where the system doesn't announce copies itself.
        get() = if (prefs.contains(THOROUGH_COPY)) prefs.getBoolean(THOROUGH_COPY, true) else !systemAnnouncesCopies
        set(value) = prefs.edit().putBoolean(THOROUGH_COPY, value).apply()

    /** Learned at runtime: this phone says "Text copied" on every copy, so nothing else is needed. */
    var systemAnnouncesCopies: Boolean
        get() = prefs.getBoolean(SYSTEM_ANNOUNCES, false)
        set(value) = prefs.edit().putBoolean(SYSTEM_ANNOUNCES, value).apply()

    /** Learned at runtime: this phone refuses clipboard reads from the focus overlay. */
    var overlayBlocked: Boolean
        get() = prefs.getBoolean(OVERLAY_BLOCKED, false)
        set(value) = prefs.edit().putBoolean(OVERLAY_BLOCKED, value).apply()

    /** The user turned automatic copy on at some point, so tell them when Android stops it. */
    var autoCopyWanted: Boolean
        get() = prefs.getBoolean(AUTO_COPY_WANTED, false)
        set(value) = prefs.edit().putBoolean(AUTO_COPY_WANTED, value).apply()

    /** Name of the payment app automatic copy stepped aside for, until it's turned back on. */
    var pausedFor: String?
        get() = prefs.getString(PAUSED_FOR, null)
        set(value) = prefs.edit().putString(PAUSED_FOR, value).apply()

    /** Packages that refuse to run next to an accessibility service; automatic copy pauses for them. */
    var pauseApps: Set<String>
        get() = prefs.getStringSet(PAUSE_APPS, null) ?: PaymentApps.KNOWN
        set(value) = prefs.edit().putStringSet(PAUSE_APPS, value).apply()

    /** New screenshots go to the Mac (needs photo access). */
    var screenshots: Boolean
        get() = prefs.getBoolean(SCREENSHOTS, true)
        set(value) = prefs.edit().putBoolean(SCREENSHOTS, value).apply()

    /** Post-pairing onboarding progress (MainActivity.Onboarding ordinal); survives trips to Settings. */
    var onboardingStep: Int
        get() = prefs.getInt(ONBOARDING, Int.MAX_VALUE)
        set(value) = prefs.edit().putInt(ONBOARDING, value).apply()

    /**
     * The EC key stays in software (AndroidKeyStore ECDH needs API 31+, we support 29), so it's
     * stored encrypted under an AES key that never leaves the keystore. Plaintext = x963 pub ‖ PKCS#8.
     */
    private fun loadIdentity(): Identity? {
        if (!keyFile.baseFile.exists()) return null
        return try {
            val blob = keyFile.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, 12))
            val plain = cipher.doFinal(blob, 12, blob.size - 12)
            Identity(Crypto.privateKeyFromPkcs8(plain.copyOfRange(65, plain.size)), plain.copyOfRange(0, 65))
        } catch (e: GeneralSecurityException) {
            // Keystore keys don't survive a restore onto another device; a new identity means re-pairing.
            Log.w(TAG, "stored identity is unreadable; generating a new one", e)
            pairedMac = null
            null
        }
    }

    private fun createIdentity(): Identity {
        val identity = Identity.generate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val sealed = cipher.iv + cipher.doFinal(identity.publicKey + identity.privateKey.encoded)
        val out = keyFile.startWrite()
        try {
            out.write(sealed)
            keyFile.finishWrite(out)
        } catch (e: IOException) {
            keyFile.failWrite(out)
            throw IOException("could not persist the device identity", e)
        }
        return identity
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "copier-identity-wrap"
        private const val MAC_ID = "mac_id"
        private const val MAC_NAME = "mac_name"
        private const val MAC_PUB = "mac_pub"
        private const val MAC_HOST = "mac_host"
        private const val MAC_PORT = "mac_port"
        private const val AUTO_CLIPBOARD = "auto_clipboard"
        private const val THOROUGH_COPY = "thorough_copy"
        private const val SYSTEM_ANNOUNCES = "system_announces_copies"
        private const val OVERLAY_BLOCKED = "overlay_blocked"
        private const val AUTO_COPY_WANTED = "auto_copy_wanted"
        private const val PAUSED_FOR = "paused_for"
        private const val PAUSE_APPS = "pause_apps"
        private const val SCREENSHOTS = "screenshots"
        private const val ONBOARDING = "onboarding_step"

        @Volatile private var instance: Store? = null

        /** One instance per process, so two components can't race to create two identities. */
        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
