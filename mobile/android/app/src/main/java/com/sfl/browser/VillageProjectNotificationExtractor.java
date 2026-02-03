package com.sfl.browser;

import com.sfl.browser.clustering.NotificationGroup;
import com.sfl.browser.models.VillageProject;
import java.util.List;

/**
 * Extractor for village project completion notifications.
 * Detects newly completed projects and creates aggregated notifications.
 */
public class VillageProjectNotificationExtractor {

    /**
     * Create a notification group for newly completed village projects.
     * Shows all completed projects with their cheer counts.
     *
     * @param newlyCompletedProjects List of newly completed projects from VillageProjectTracker
     * @return NotificationGroup containing the aggregated completion notification, or null if no new completions
     */
    public static NotificationGroup createVillageProjectNotification(List<VillageProject> newlyCompletedProjects) {
        if (newlyCompletedProjects == null || newlyCompletedProjects.isEmpty()) {
            return null;
        }

        // Build the notification body with project names
        StringBuilder bodyBuilder = new StringBuilder();
        for (VillageProject project : newlyCompletedProjects) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append(", ");
            }
            bodyBuilder.append(project.name).append(" (").append(project.cheers).append(")");
        }

        String title;
        if (newlyCompletedProjects.size() == 1) {
            title = "Village Project Complete!";
        } else {
            title = newlyCompletedProjects.size() + " Village Projects Complete!";
        }
        
        String body = bodyBuilder.toString();

        DebugLog.log("🏘️ VILLAGE PROJECT NOTIFICATION: " + title + " - " + body);

        // Create NotificationGroup with category = "village_projects"
        NotificationGroup group = new NotificationGroup();
        group.category = "village_projects";
        group.name = body; // Store the formatted body in name field
        group.quantity = newlyCompletedProjects.size();
        group.earliestReadyTime = System.currentTimeMillis();
        group.groupId = "village_projects_" + System.currentTimeMillis(); // Unique ID for tracking
        group.details = null;

        return group;
    }
}
