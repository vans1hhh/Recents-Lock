package com.example.recentslock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    public static final String PREFS = "locked_apps";
    public static final String KEY = "locked";

    private SharedPreferences prefs;
    private final Set<String> locked = new HashSet<>();
    private final List<String> pkgs = new ArrayList<>();

    @SuppressWarnings({"deprecation", "WorldReadableFiles"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            prefs = getSharedPreferences(PREFS, MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            Toast.makeText(this, "Module isn't enabled in LSPosed yet - settings won't reach the hook",
                    Toast.LENGTH_LONG).show();
        }
        locked.addAll(prefs.getStringSet(KEY, Collections.emptySet()));

        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<String[]> rows = new ArrayList<>(); // {label, pkg}
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (seen.add(p)) rows.add(new String[]{ri.loadLabel(pm).toString(), p});
        }
        rows.sort((a, c) -> a[0].compareToIgnoreCase(c[0]));

        List<String> labels = new ArrayList<>();
        for (String[] r : rows) { labels.add(r[0] + "\n" + r[1]); pkgs.add(r[1]); }

        ListView lv = new ListView(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        lv.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < pkgs.size(); i++) lv.setItemChecked(i, locked.contains(pkgs.get(i)));

        lv.setOnItemClickListener((parent, v, pos, id) -> {
            String p = pkgs.get(pos);
            if (lv.isItemChecked(pos)) locked.add(p); else locked.remove(p);
            prefs.edit().putStringSet(KEY, new HashSet<>(locked)).apply();
        });
        setContentView(lv);
        setTitle("Lock apps in Recents");
    }
}
