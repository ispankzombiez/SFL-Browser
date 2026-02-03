package com.sfl.browser.models;

/**
 * Represents a village project for tracking completion status
 * Used to detect NEW completions and avoid duplicate notifications
 * Fires when cheers >= threshold, resets when cheers < threshold
 */
public class VillageProject {
    public String name;           // "Big Banana", "Expert Cooking Pot", etc.
    public int cheers;            // Current cheer count
    public int threshold;         // Required cheers for completion
    public boolean isComplete;    // Whether project meets threshold
    public long detectedAt;       // Timestamp when completion was detected

    public VillageProject() {
    }

    public VillageProject(String name, int cheers, int threshold, long detectedAt) {
        this.name = name;
        this.cheers = cheers;
        this.threshold = threshold;
        this.isComplete = (cheers >= threshold);
        this.detectedAt = detectedAt;
    }

    @Override
    public String toString() {
        return name + ": " + cheers + "/" + threshold + " (complete: " + isComplete + ")";
    }

    /**
     * Create a unique key for this project (for tracking)
     */
    public String getKey() {
        return name;
    }
}
