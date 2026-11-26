package org.fossify.documents.rosenweg

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import ch.rosenweg4303.cloud.AnmeldenActivity
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.WebDav
import org.fossify.documents.activities.DocumentOpenActivity
import org.fossify.documents.models.DocumentKind
import java.io.File
import java.util.concurrent.Executors

/**
 * Rosenweg (Stefan 26.11.2026): Dokumente aus der Rosenweg-Nextcloud lesen, per WebDAV.
 *
 * Zugang: KontoSpeicher aus commons-rosenweg - auf geteilten Telefonen von Rosenweg Geraet, auf
 * dem eigenen Handy nach dem Anmeldeschirm (Rosenweg-Karte oder Rosenweg-Konto) ueber die Anlage.
 * Zeigt Ordner und die Dateien, die diese App lesen kann (PDF, DOCX, Text, Markdown, CSV, HTML).
 * Antippen laedt die Datei in den Zwischenspeicher und oeffnet sie wie jede andere; Aenderungen
 * gehen nicht zurueck (dafuer: Euro-Office in der Nextcloud).
 */
class NextcloudDokumenteActivity : Activity() {
    companion object {
        private const val APP = "Rosenweg Dokumente (Android)"
        private const val PFAD = "pfad"
        const val BESCHREIBUNG = "Deine Dokumente aus der Rosenweg-Nextcloud lesen: PDF, Word (DOCX), Text, " +
            "Markdown, CSV. Bearbeitet wird in der Nextcloud (Euro-Office)."
    }

    private val netz = Executors.newSingleThreadExecutor()
    private lateinit var titel: TextView
    private lateinit var liste: LinearLayout
    private lateinit var meldung: TextView
    private var pfad = ""
    private var dav: WebDav? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pfad = b?.getString(PFAD) ?: ""
        val rand = dp(16)
        titel = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(rand, rand, rand, dp(8)) }
        meldung = TextView(this).apply { setPadding(rand, dp(8), rand, dp(8)) }
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(titel)
            addView(meldung)
            addView(ScrollView(context).apply { addView(liste) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(PFAD, pfad)
    }

    override fun onResume() {
        super.onResume()
        val konto = KontoSpeicher(this).laden()
        dav = konto?.let { WebDav(it, APP) }
        if (konto == null) nichtAngemeldet() else laden()
    }

    override fun onDestroy() {
        netz.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pfad.isEmpty() || dav == null) super.onBackPressed() else {
            pfad = pfad.substringBeforeLast('/', "")
            laden()
        }
    }

    private fun nichtAngemeldet() {
        titel.text = "Rosenweg-Nextcloud"
        meldung.text = "Nicht angemeldet. Melde dich an (Rosenweg-Karte oder Rosenweg-Konto), dann siehst du hier deine Dokumente."
        liste.removeAllViews()
        liste.addView(Button(this).apply {
            text = "Anmelden"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@NextcloudDokumenteActivity, AnmeldenActivity::class.java)
                    .putExtra(AnmeldenActivity.EXTRA_BESCHREIBUNG, BESCHREIBUNG))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), 0) })
    }

    private fun laden() {
        val d = dav ?: return
        titel.text = if (pfad.isEmpty()) "Rosenweg-Nextcloud" else pfad.substringAfterLast('/')
        meldung.text = "Lade …"
        liste.removeAllViews()
        val ort = pfad
        netz.execute {
            val ergebnis = try {
                Result.success(d.liste(ort))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || ort != pfad) return@runOnUiThread
                ergebnis.onSuccess { zeigen(it) }.onFailure {
                    meldung.text = "Nextcloud nicht erreichbar: ${it.message}"
                }
            }
        }
    }

    private fun lesbar(e: Eintrag) = DocumentKind.fromName(e.name, e.mime) != DocumentKind.OTHER

    private fun zeigen(eintraege: List<Eintrag>) {
        val sichtbar = eintraege.filter { (it.istOrdner || lesbar(it)) && !it.name.startsWith(".") }
            .sortedWith(compareBy<Eintrag>({ !it.istOrdner }, { it.name.lowercase() }))
        meldung.text = if (sichtbar.isEmpty()) "Hier gibt es keine Dokumente." else ""
        meldung.visibility = if (sichtbar.isEmpty()) View.VISIBLE else View.GONE
        for (e in sichtbar) {
            liste.addView(TextView(this).apply {
                text = (if (e.istOrdner) "📁  " else "📄  ") + e.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { if (e.istOrdner) { pfad = e.pfad; laden() } else oeffnen(e) }
            })
        }
    }

    /** Herunterladen in den Zwischenspeicher, dann wie jedes Dokument oeffnen (nur lesen). */
    private fun oeffnen(e: Eintrag) {
        val d = dav ?: return
        Toast.makeText(this, "Lade ${e.name} …", Toast.LENGTH_SHORT).show()
        netz.execute {
            try {
                val ordner = File(cacheDir, "nextcloud").apply { mkdirs() }
                // Zwischenspeicher klein halten: was aelter als ein Tag ist, weg
                ordner.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                val datei = File(ordner, e.name.replace('/', '_'))
                d.herunterladen(e.pfad, datei)
                val uri = FileProvider.getUriForFile(this, "$packageName.rosenweg.dateien", datei)
                runOnUiThread {
                    startActivity(Intent(this, DocumentOpenActivity::class.java)
                        .setDataAndType(uri, e.mime.ifEmpty { "application/octet-stream" })
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (x: Exception) {
                runOnUiThread { Toast.makeText(this, "Laden fehlgeschlagen: ${x.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
