package dev.codex.go.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

class AdbIdentity(context: Context) : AbsAdbConnectionManager() {
    private val privateIdentity: PrivateKey
    private val publicIdentity: Certificate
    init {
        val directory = File(context.filesDir, "adb-identity").apply { mkdirs() }
        val keyFile = File(directory, "private.pk8"); val certificateFile = File(directory, "certificate.der")
        if (keyFile.exists() && certificateFile.exists()) {
            privateIdentity = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            publicIdentity = certificateFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val provider = BouncyCastleProvider(); val subject = X500Name("CN=Codex Go")
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger(128, SecureRandom()), Date(System.currentTimeMillis() - 60_000), Date(System.currentTimeMillis() + 10L * 365 * 86400_000), subject, pair.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)
            publicIdentity = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            privateIdentity = pair.private
            keyFile.writeBytes(privateIdentity.encoded); certificateFile.writeBytes(publicIdentity.encoded)
        }
        setApi(Build.VERSION.SDK_INT); setTimeout(12, TimeUnit.SECONDS); setThrowOnUnauthorised(true)
    }
    override fun getPrivateKey() = privateIdentity
    override fun getCertificate() = publicIdentity
    override fun getDeviceName() = "Codex Go"
}
