package org.schoollibrary.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * School Library — Android app.
 *
 * Shows the School Library page (loaded from your GitHub site, with a built-in
 * copy for when there is no internet) and gives it what a browser can't:
 *  - it remembers the files folder on the phone,
 *  - it lists the files in that folder,
 *  - a tap opens the original file directly in its app (WPS Office, music player...).
 */
public class MainActivity extends Activity {

    private static final int PICK_FOLDER = 41;
    private static final String PREFS = "library";
    private static final String KEY_TREE = "tree_uri";
    private static final String BUILT_IN_PAGE = "file:///android_asset/index.html";
    private static final int MAX_DEPTH = 8;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** path inside the folder -> { document id, mime type } from the last listing */
    private final Map<String, String[]> docs = new ConcurrentHashMap<>();
    private String pendingPick;
    private boolean usingBuiltIn;
    private String libraryUrl;
    private String libraryHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        libraryUrl = getString(R.string.library_url).trim();
        libraryHost = Uri.parse(libraryUrl).getHost();
        getWindow().setStatusBarColor(Color.parseColor("#0F6E64"));

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Online: normal loading (picks up page updates). Offline: use the saved copy.
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        web.addJavascriptInterface(new Bridge(), "LibraryApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openOutside(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showBuiltInPage();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showBuiltInPage();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // If the online page is an old version without app support, use the built-in copy.
                if (usingBuiltIn || url == null || !url.startsWith("https://")) return;
                view.evaluateJavascript("typeof window.__libraryApp", value -> {
                    if (!"\"object\"".equals(value)) showBuiltInPage();
                });
            }
        });

        if (libraryUrl.startsWith("https://")) web.loadUrl(libraryUrl);
        else showBuiltInPage();
    }

    private void showBuiltInPage() {
        if (usingBuiltIn) return;
        usingBuiltIn = true;
        web.loadUrl(BUILT_IN_PAGE);
    }

    /** Pages other than the library open in the phone's browser, so the bridge is only given to our page. */
    private boolean openOutside(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("about")) return false;
        if (scheme.equals("https") && libraryHost != null && libraryHost.equalsIgnoreCase(uri.getHost())) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // nothing can open it
        }
        return true;
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            // New files copied into the folder show up when coming back to the app.
            web.evaluateJavascript("window.__libraryApp && window.__libraryApp.refresh && window.__libraryApp.refresh()", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__libraryApp && window.__libraryApp.back) ? window.__libraryApp.back() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Choosing the folder                                                */
    /* ------------------------------------------------------------------ */

    private void startPick(String cb) {
        pendingPick = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            pendingPick = null;
            reply(cb, error("no_picker"));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER) return;
        String cb = pendingPick;
        pendingPick = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (cb != null) reply(cb, error("cancelled"));
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            if (cb != null) reply(cb, error("no_permission"));
            return;
        }
        String old = prefs.getString(KEY_TREE, null);
        if (old != null && !old.equals(tree.toString())) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // already gone
            }
        }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply();
        docs.clear();
        if (cb != null) reply(cb, ok());
        else if (web != null) web.reload();
    }

    /* ------------------------------------------------------------------ */
    /*  Listing the files                                                  */
    /* ------------------------------------------------------------------ */

    private String listJson() {
        String saved = prefs.getString(KEY_TREE, null);
        if (saved == null) return error("no_folder");
        Uri tree = Uri.parse(saved);
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            JSONArray files = new JSONArray();
            Map<String, String[]> found = new HashMap<>();
            walk(tree, rootId, "", 0, files, found);
            docs.clear();
            docs.putAll(found);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", displayName(tree, rootId));
            o.put("files", files);
            return o.toString();
        } catch (SecurityException e) {
            return error("no_permission");
        } catch (Exception e) {
            return error("failed");
        }
    }

    private void walk(Uri tree, String parentId, String prefix, int depth, JSONArray out, Map<String, String[]> found) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) throw new IllegalStateException("folder not readable");
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null || name == null || name.startsWith(".")) continue;
                String path = prefix + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < MAX_DEPTH) walk(tree, id, path + "/", depth + 1, out, found);
                } else {
                    JSONObject f = new JSONObject();
                    f.put("p", path);
                    f.put("s", c.isNull(3) ? 0 : c.getLong(3));
                    f.put("m", c.isNull(4) ? 0 : c.getLong(4));
                    out.put(f);
                    found.put(path, new String[] { id, mime });
                }
            }
        }
    }

    private String displayName(Uri tree, String docId) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        String[] cols = { DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor c = getContentResolver().query(doc, cols, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // no name available
        }
        return "";
    }

    /* ------------------------------------------------------------------ */
    /*  Opening a file in its app                                          */
    /* ------------------------------------------------------------------ */

    private String open(String path) {
        String saved = prefs.getString(KEY_TREE, null);
        String[] d = path == null ? null : docs.get(path);
        if (saved == null || d == null) return error("not_found");
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(saved), d[0]);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mimeFor(path, d[1]));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
            return ok();
        } catch (ActivityNotFoundException e) {
            return error("no_app");
        } catch (SecurityException e) {
            return error("no_permission");
        } catch (Exception e) {
            return error("failed");
        }
    }

    private static String mimeFor(String path, String reported) {
        if (reported != null && !reported.isEmpty() && !reported.equals("application/octet-stream")) return reported;
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String fromName = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return fromName != null ? fromName : "*/*";
    }

    /* ------------------------------------------------------------------ */
    /*  Talking to the page                                                */
    /* ------------------------------------------------------------------ */

    private void reply(String cb, String json) {
        final String js = "window.__libraryApp && window.__libraryApp.resolve(" + JSONObject.quote(cb) + "," + JSONObject.quote(json) + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String error(String code) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(code) + "}";
    }

    /** Called by the page as window.LibraryApp.* */
    private class Bridge {
        @JavascriptInterface
        public String getItem(String key) {
            return prefs.getString("js:" + key, null);
        }

        @JavascriptInterface
        public void setItem(String key, String value) {
            prefs.edit().putString("js:" + key, value).apply();
        }

        @JavascriptInterface
        public void removeItem(String key) {
            prefs.edit().remove("js:" + key).apply();
        }

        @JavascriptInterface
        public void pickFolder(final String cb) {
            main.post(() -> startPick(cb));
        }

        @JavascriptInterface
        public void listFiles(final String cb) {
            worker.execute(() -> reply(cb, listJson()));
        }

        @JavascriptInterface
        public void openFile(final String cb, final String path) {
            main.post(() -> reply(cb, open(path)));
        }
    }
}
