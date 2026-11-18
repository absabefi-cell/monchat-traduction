package com.mouad.chattrad.natif;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Partie Android de MonChat Traduction, offerte à la page sous le nom window.MonChatNatif :
 * écoute sans Internet (Vosk), traduction sans Internet (ML Kit), voix (synthèse vocale d'Android).
 * Les réponses reviennent à la page par window.MonChatNatifRetour({...}).
 */
public class MonChatNatif extends CordovaPlugin {

    // Modèles d'écoute : nom chez alphacephei.com et taille en Mo
    private static final Map<String, String[]> MODELES = new HashMap<>();
    static {
        MODELES.put("fr", new String[]{"vosk-model-small-fr-0.22", "41"});
        MODELES.put("en", new String[]{"vosk-model-small-en-us-0.15", "40"});
        MODELES.put("es", new String[]{"vosk-model-small-es-0.42", "39"});
        MODELES.put("ar", new String[]{"vosk-model-ar-mgb2-0.4", "318"});
    }
    private static final int MOTS_MAX = 12;          // au-delà, on envoie la phrase sans attendre la fin
    private static final long DUREE_MAX = 7000;      // ms

    private WebView vue;
    private final ExecutorService ecoute = Executors.newSingleThreadExecutor();
    private final ExecutorService fond = Executors.newSingleThreadExecutor();
    private final ExecutorService trad = Executors.newSingleThreadExecutor();
    private final Map<String, Model> modeles = new ConcurrentHashMap<>();
    private final Map<String, Translator> traducteurs = new ConcurrentHashMap<>();
    private final Set<String> traductionsPretes = ConcurrentHashMap.newKeySet();
    private LanguageIdentifier identifieur;

    // État de l'écoute (utilisé seulement dans le fil « ecoute »)
    private Recognizer reco;
    private String mode = "";
    private final StringBuilder texteVocal = new StringBuilder();
    private long debutPhrase = 0;

    private TextToSpeech tts;
    private volatile boolean ttsPret = false;
    private final AtomicInteger enCours = new AtomicInteger(0);

    @Override
    protected void pluginInitialize() {
        final android.view.View v = webView.getView();
        if (v instanceof WebView) {
            vue = (WebView) v;
            cordova.getActivity().runOnUiThread(() -> vue.addJavascriptInterface(new Pont(), "MonChatNatif"));
        }
        traductionsPretes.add("en");     // l'anglais est toujours dans le téléphone
        majTraductions();
        tts = new TextToSpeech(ctx(), st -> {
            ttsPret = st == TextToSpeech.SUCCESS;
            if (!ttsPret) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { retour(obj("type", "parole", "etat", "debut")); }
                @Override public void onDone(String id) { fini(); }
                @Override public void onError(String id) { fini(); }
                @Override public void onStop(String id, boolean interrompu) { fini(); }
            });
        });
    }

    private Context ctx() { return cordova.getActivity().getApplicationContext(); }

    private void fini() {
        if (enCours.decrementAndGet() <= 0) { enCours.set(0); retour(obj("type", "parole", "etat", "fin")); }
    }

    // ---------- Réponses vers la page ----------

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]); }
        catch (JSONException ignore) { }
        return o;
    }

    private void retour(JSONObject o) {
        final WebView w = vue;
        if (w == null) return;
        final String js = "window.MonChatNatifRetour&&window.MonChatNatifRetour(" + o.toString() + ")";
        w.post(() -> w.evaluateJavascript(js, null));
    }

    // ---------- Modèles d'écoute (téléchargés une seule fois, gardés dans le dossier de l'app) ----------

    private File dossier(String lang) { return new File(ctx().getFilesDir(), "vosk/" + lang); }

    private static File racine(File d) {
        if (new File(d, "am").isDirectory() || new File(d, "conf").isDirectory()) return d;
        File[] l = d.listFiles();
        if (l == null) return null;
        for (File f : l) if (f.isDirectory()) { File r = racine(f); if (r != null) return r; }
        return null;
    }

    private File pret(String lang) {
        File d = dossier(lang);
        return new File(d, "ok").exists() ? racine(d) : null;
    }

    private interface Progres { void pourcent(int p); }

    private synchronized File telecharger(String lang, Progres pr) throws IOException {
        File ok = pret(lang);
        if (ok != null) return ok;
        String[] m = MODELES.get(lang);
        if (m == null) throw new IOException("langue non prise en charge");
        File part = new File(ctx().getFilesDir(), "vosk/" + m[0] + ".zip.part");
        part.getParentFile().mkdirs();
        int essais = 0;
        while (true) {
            try {
                if (morceau(new URL("https://alphacephei.com/vosk/models/" + m[0] + ".zip"), part, pr)) break;
                if (++essais >= 8) throw new IOException("téléchargement incomplet");
            } catch (IOException e) {
                if (++essais >= 8) throw e;
                try { Thread.sleep(2000L * essais); } catch (InterruptedException ie) { throw new IOException("arrêté"); }
            }
        }
        File d = dossier(lang);
        supprimer(d); d.mkdirs();
        String base = d.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(part)))) {
            ZipEntry e;
            byte[] b = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(d, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) continue;      // sécurité : rien hors du dossier
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) { int n; while ((n = z.read(b)) > 0) out.write(b, 0, n); }
            }
        } catch (IOException e) {
            part.delete(); supprimer(d);                                   // fichier abîmé : on recommencera
            throw e;
        }
        part.delete();
        try (OutputStream o = new FileOutputStream(new File(d, "ok"))) { o.write(m[0].getBytes()); }
        File r = racine(d);
        if (r == null) throw new IOException("modèle incomplet");
        return r;
    }

    /** Télécharge la suite du fichier (reprise si la connexion a coupé). true = complet. */
    private static boolean morceau(URL url, File part, Progres pr) throws IOException {
        long deja = part.exists() ? part.length() : 0;
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        if (deja > 0) c.setRequestProperty("Range", "bytes=" + deja + "-");
        try {
            int code = c.getResponseCode();
            if (code == 416) return true;
            if (code != 200 && code != 206) throw new IOException("serveur : " + code);
            boolean reprise = code == 206;
            long lg = c.getContentLengthLong();
            long total = lg > 0 ? lg + (reprise ? deja : 0) : -1;
            long lu = reprise ? deja : 0;
            int dernier = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, reprise)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); lu += n;
                    if (total > 0) { int p = (int) (lu * 100 / total); if (p != dernier) { dernier = p; pr.pourcent(p); } }
                }
            }
            return total <= 0 || lu >= total;
        } finally { c.disconnect(); }
    }

    private static void supprimer(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) supprimer(x);
        f.delete();
    }

    // ---------- Traduction ----------

    private void majTraductions() {
        try {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(l -> { for (TranslateRemoteModel m : l) traductionsPretes.add(m.getLanguage()); });
        } catch (Exception ignore) { }
    }

    private static String court(String tag) {
        if (tag == null) return "";
        int i = tag.indexOf('-');
        return (i > 0 ? tag.substring(0, i) : tag).toLowerCase(Locale.ROOT);
    }

    private String traduireMaintenant(String texte, String de, String vers) throws Exception {
        String src = court(de);
        if (src.isEmpty()) {
            if (identifieur == null) identifieur = LanguageIdentification.getClient();
            src = court(Tasks.await(identifieur.identifyLanguage(texte), 10, TimeUnit.SECONDS));
            if ("und".equals(src)) src = "";
        }
        if (src.isEmpty() || src.equals(vers)) return null;           // déjà dans la bonne langue
        String a = TranslateLanguage.fromLanguageTag(src), b = TranslateLanguage.fromLanguageTag(vers);
        if (a == null || b == null) return null;
        Translator t = traducteurs.get(a + ">" + b);
        if (t == null) {
            t = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build());
            traducteurs.put(a + ">" + b, t);
        }
        Tasks.await(t.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS);
        traductionsPretes.add(src); traductionsPretes.add(vers);
        return Tasks.await(t.translate(texte), 30, TimeUnit.SECONDS);
    }

    // ---------- Écoute ----------

    private static String champ(String json, String cle) {
        try { return new JSONObject(json).optString(cle, "").trim(); } catch (JSONException e) { return ""; }
    }

    private void phrase(String json) {
        debutPhrase = 0;
        String t = champ(json, "text");
        if (t.length() < 2) return;
        if ("vocal".equals(mode)) { if (texteVocal.length() > 0) texteVocal.append(' '); texteVocal.append(t); }
        else retour(obj("type", "phrase", "texte", t));
    }

    private void traiter(byte[] pcm) {
        Recognizer r = reco;
        if (r == null) return;
        if (r.acceptWaveForm(pcm, pcm.length)) { phrase(r.getResult()); return; }
        String p = champ(r.getPartialResult(), "partial");
        if (p.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (debutPhrase == 0) debutPhrase = now;
        int mots = p.split("\\s+").length;
        if (mots >= MOTS_MAX || now - debutPhrase > DUREE_MAX) { phrase(r.getFinalResult()); r.reset(); }
        else if (!"vocal".equals(mode)) retour(obj("type", "partiel", "texte", p));
    }

    private void fermerReco() {
        if (reco != null) { try { reco.close(); } catch (Exception ignore) { } reco = null; }
    }

    // ---------- Ce que la page peut appeler ----------

    public class Pont {

        /** État des langues : écoute téléchargée, traduction prête, voix installée. */
        @JavascriptInterface
        public String etat(String parle, String entend) {
            String[] m = MODELES.get(parle);
            boolean tr = parle.equals(entend) || (traductionsPretes.contains(parle) && traductionsPretes.contains(entend));
            return obj("ecoute", pret(parle) != null, "ecouteMo", m == null ? 0 : Integer.parseInt(m[1]),
                "traduction", tr, "voix", voixPrete(entend)).toString();
        }

        /** Télécharge d'avance l'écoute de « parle » et la traduction parle ↔ entend. */
        @JavascriptInterface
        public void preparer(final String parle, final String entend) {
            fond.execute(() -> {
                String erreur = null;
                try {
                    telecharger(parle, p -> retour(obj("type", "prep", "texte", "Écoute : " + p + " %", "fini", false)));
                } catch (Exception e) { erreur = "écoute (" + e.getMessage() + ")"; }
                for (String l : new String[]{parle, entend}) {
                    if ("en".equals(l) || TranslateLanguage.fromLanguageTag(l) == null) continue;
                    retour(obj("type", "prep", "texte", "Traduction : téléchargement…", "fini", false));
                    try {
                        Tasks.await(RemoteModelManager.getInstance().download(
                            new TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(l)).build(),
                            new DownloadConditions.Builder().build()), 300, TimeUnit.SECONDS);
                        traductionsPretes.add(l);
                    } catch (Exception e) { erreur = "traduction (" + e.getMessage() + ")"; }
                }
                majTraductions();
                retour(obj("type", "prep", "fini", true, "texte",
                    erreur == null ? "✓ Tout est prêt." : "Préparation incomplète : " + erreur + ". Vérifie Internet et réessaie."));
            });
        }

        /** Commence l'écoute (mode « appel » : phrase par phrase ; « vocal » : tout le texte à la fin). */
        @JavascriptInterface
        public boolean ecouteDemarrer(final String lang, final String m) {
            final File d = pret(lang);
            if (d == null) return false;
            ecoute.execute(() -> {
                fermerReco();
                mode = m; texteVocal.setLength(0); debutPhrase = 0;
                try {
                    Model mod = modeles.get(lang);
                    if (mod == null) { mod = new Model(d.getAbsolutePath()); modeles.put(lang, mod); }
                    reco = new Recognizer(mod, 16000f);
                } catch (Exception e) {
                    retour(obj("type", "erreur", "texte", "Écoute impossible : " + e.getMessage()));
                }
            });
            return true;
        }

        /** Son du micro : 16 kHz, 16 bits, mono, en base64. */
        @JavascriptInterface
        public void audio(String b64) {
            final byte[] pcm;
            try { pcm = Base64.decode(b64, Base64.NO_WRAP); } catch (Exception e) { return; }
            ecoute.execute(() -> traiter(pcm));
        }

        /** Arrête l'écoute. Renvoie tout le texte entendu (pour les messages vocaux). */
        @JavascriptInterface
        public String ecouteArreter() {
            Future<String> f = ecoute.submit(() -> {
                if (reco != null) phrase(reco.getFinalResult());
                fermerReco();
                String r = texteVocal.toString().trim();
                texteVocal.setLength(0);
                mode = "";
                return r;
            });
            try { return f.get(4, TimeUnit.SECONDS); } catch (Exception e) { return ""; }
        }

        /** Traduit ; la réponse revient par MonChatNatifRetour({type:'trad', id, texte}) (texte null = rien à traduire). */
        @JavascriptInterface
        public void traduire(final String id, final String texte, final String de, final String vers) {
            trad.execute(() -> {
                String r = null;
                try { r = traduireMaintenant(texte, de, vers); } catch (Exception ignore) { }
                retour(obj("type", "trad", "id", id, "texte", r));
            });
        }

        /** Dit un texte. g = « h » (voix d'homme) ou « f » ; appel = passe par le son de l'appel. */
        @JavascriptInterface
        public void dire(String texte, String lang, String g, boolean appel) {
            if (!ttsPret || texte == null || texte.trim().isEmpty()) return;
            tts.setLanguage(new Locale(lang));
            tts.setPitch("f".equals(g) ? 1.15f : 0.85f);
            tts.setSpeechRate(1.05f);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(appel ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            enCours.incrementAndGet();
            if (tts.speak(texte, TextToSpeech.QUEUE_ADD, new Bundle(), "u" + System.nanoTime()) != TextToSpeech.SUCCESS) fini();
        }

        /** Arrête la voix tout de suite. */
        @JavascriptInterface
        public void taire() {
            if (tts != null) tts.stop();
            enCours.set(0);
            retour(obj("type", "parole", "etat", "fin"));
        }

        /** Ouvre l'écran d'Android pour installer une voix. */
        @JavascriptInterface
        public void installerVoix() {
            try {
                ctx().startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                try { ctx().startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignore) { }
            }
        }
    }

    private boolean voixPrete(String lang) {
        if (!ttsPret) return false;
        try { return tts.isLanguageAvailable(new Locale(lang)) >= TextToSpeech.LANG_AVAILABLE; }
        catch (Exception e) { return false; }
    }

    @Override
    public void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignore) { }
        ecoute.execute(() -> { fermerReco(); for (Model m : modeles.values()) m.close(); modeles.clear(); });
        for (Translator t : traducteurs.values()) t.close();
        if (identifieur != null) identifieur.close();
        super.onDestroy();
    }
}
