package com.sfl.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.sfl.browser.models.VillageProject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks village project completion state to detect new completions
 * Pattern: Track previous state vs current state to find newly completed projects
 * Reset when cheers < threshold (project was collected/reset)
 */
public class VillageProjectTracker {
    private static final String TAG = "VillageProjectTracker";
    private static final String PREF_KEY = "village_project_tracking";

    private Context context;

    public VillageProjectTracker(Context context) {
        this.context = context;
    }

    /**
     * Get newly completed village projects by comparing current state to previous state
     * Only returns projects that:
     * 1. Now have cheers >= threshold
     * 2. Were not marked as complete in previous state (or didn't exist)
     * 
     * @param currentProjects List of all current village projects with their cheer counts
     * @return List of newly completed projects
     */
    public List<VillageProject> getNewlyCompletedProjects(List<VillageProject> currentProjects) {
        List<VillageProject> newlyCompleted = new ArrayList<>();
        
        // Load previous completion state
        Map<String, Boolean> previousState = loadPreviousState();
        
        for (VillageProject project : currentProjects) {
            if (project.isComplete) {
                // Project meets threshold now
                String key = project.getKey();
                Boolean wasComplete = previousState.get(key);
                
                if (wasComplete == null || !wasComplete) {
                    // NEW completion - either first time or recovered from reset
                    newlyCompleted.add(project);
                    Log.d(TAG, "Newly completed: " + project.name + " (" + project.cheers + "/" + project.threshold + ")");
                    DebugLog.log("🎉 Village Project Completed: " + project.name + " (" + project.cheers + "/" + project.threshold + " cheers)");
                } else {
                    // Already was complete, no notification
                    Log.d(TAG, "Already complete (no notification): " + project.name);
                }
            }
        }
        
        if (newlyCompleted.isEmpty()) {
            Log.d(TAG, "No newly completed village projects detected");
        } else {
            Log.d(TAG, "Found " + newlyCompleted.size() + " newly completed project(s)");
        }
        
        return newlyCompleted;
    }

    /**
     * Save current completion state for next comparison
     * Projects with cheers >= threshold are marked as complete
     * Projects with cheers < threshold are marked as incomplete (reset for future detection)
     * 
     * @param currentProjects List of all current village projects
     */
    public void saveCurrentState(List<VillageProject> currentProjects) {
        Map<String, Boolean> currentState = new HashMap<>();
        
        for (VillageProject project : currentProjects) {
            currentState.put(project.getKey(), project.isComplete);
        }
        
        // Save to SharedPreferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        
        // Serialize map to JSON-like string format
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : currentState.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(entry.getKey()).append(":").append(entry.getValue() ? "1" : "0");
        }
        
        editor.putString(PREF_KEY, sb.toString());
        editor.apply();
        
        Log.d(TAG, "Saved current state: " + currentState.size() + " project(s) tracked");
    }

    /**
     * Load previous completion state from SharedPreferences
     * 
     * @return Map of project name -> completion state (true if was complete)
     */
    private Map<String, Boolean> loadPreviousState() {
        Map<String, Boolean> state = new HashMap<>();
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String stored = prefs.getString(PREF_KEY, "");
        
        if (!stored.isEmpty()) {
            String[] entries = stored.split(";");
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts.length == 2) {
                    String projectName = parts[0];
                    boolean wasComplete = parts[1].equals("1");
                    state.put(projectName, wasComplete);
                }
            }
        }
        
        Log.d(TAG, "Loaded previous state: " + state.size() + " project(s)");
        return state;
    }
}
