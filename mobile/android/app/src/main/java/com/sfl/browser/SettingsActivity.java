package com.sfl.browser;

import android.os.Bundle;
import android.os.Build;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;
import android.Manifest;
import android.util.Log;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class SettingsActivity extends AppCompatActivity {
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 100;
    
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
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d("SettingsActivity", "Requesting POST_NOTIFICATIONS permission");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
            } else {
                Log.d("SettingsActivity", "POST_NOTIFICATIONS permission already granted");
                showTutorialPromptIfNeeded();
            }
        } else {
            showTutorialPromptIfNeeded();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            showTutorialPromptIfNeeded();
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
