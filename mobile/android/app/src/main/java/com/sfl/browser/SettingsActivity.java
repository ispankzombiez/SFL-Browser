package com.sfl.browser;

import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import android.Manifest;

public class SettingsActivity extends AppCompatActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // Set up the toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
        
        // Enable back button in action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Notification Settings");
        }
        
        // Request notification permission if needed
        requestNotificationPermissionIfNeeded();
        
        // Show tutorial prompt if needed
        showTutorialPromptIfNeeded();
    }
    
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        102);
            }
        }
    }
    
    private void showTutorialPromptIfNeeded() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean hasSeenTutorialPrompt = prefs.getBoolean("has_seen_tutorial_prompt", false);
        
        if (!hasSeenTutorialPrompt) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tutorial")
                .setMessage("Would you like to view the tutorial page?")
                .setNegativeButton("Cancel", (dialog, which) -> {
                    prefs.edit().putBoolean("has_seen_tutorial_prompt", true).apply();
                    dialog.dismiss();
                })
                .setPositiveButton("See Tutorial", (dialog, which) -> {
                    prefs.edit().putBoolean("has_seen_tutorial_prompt", true).apply();
                    Intent tutorialIntent = new Intent(SettingsActivity.this, TutorialActivity.class);
                    startActivity(tutorialIntent);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
        }
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
