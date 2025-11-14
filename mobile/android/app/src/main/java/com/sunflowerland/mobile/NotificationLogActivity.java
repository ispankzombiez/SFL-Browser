package com.sunflowerland.mobile;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * Displays the notification log in chronological order (soonest ready times first)
 */
public class NotificationLogActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setPadding(16, 96, 16, 16);
        textView.setTextSize(12);
        
        String logContent = readAndSortLogFile("notification_summary.log");
        textView.setText(logContent);
        
        scrollView.addView(textView);
        setContentView(scrollView);
    }
    
    /**
     * Read log file and sort notifications chronologically (soonest first)
     */
    private String readAndSortLogFile(String filename) {
        try {
            File logFile = new File(getFilesDir(), filename);
            if (!logFile.exists()) {
                return "No log file found: " + filename;
            }
            
            // Read all lines
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader(logFile));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();
            
            // Find header and notification entries
            String generatedAt = "";
            List<NotificationEntry> notifications = new ArrayList<>();
            
            for (String logLine : lines) {
                if (logLine.startsWith("Generated at:")) {
                    generatedAt = logLine;
                } else if (logLine.startsWith("[")) {
                    // Parse as notification
                    NotificationEntry entry = parseNotificationLine(logLine);
                    if (entry != null && entry.remainingMs > 0) {
                        // Only include future notifications (remainingMs > 0)
                        notifications.add(entry);
                    }
                }
            }
            
            // Sort notifications by ready time (soonest first)
            Collections.sort(notifications, (a, b) -> Long.compare(a.remainingMs, b.remainingMs));
            
            // Format output in plain English
            StringBuilder result = new StringBuilder();
            result.append("📋 UPCOMING NOTIFICATIONS\n");
            result.append("════════════════════════════════════════\n\n");
            result.append(generatedAt).append("\n\n");
            
            if (notifications.isEmpty()) {
                result.append("No upcoming notifications scheduled.\n");
            } else {
                for (NotificationEntry entry : notifications) {
                    result.append(entry.formatForDisplay()).append("\n\n");
                }
            }
            
            return result.toString();
        } catch (IOException e) {
            return "Error reading log: " + e.getMessage();
        }
    }
    
    /**
     * Parse a notification line to extract remaining time for sorting
     * Format: "[h:mm a] quantity name (ready in Xm)" or "(ready in Xh Xm)" or "(now)"
     */
    private NotificationEntry parseNotificationLine(String line) {
        try {
            // Extract time from "[h:mm a]"
            int timeStart = line.indexOf("[");
            int timeEnd = line.indexOf("]");
            if (timeStart == -1 || timeEnd == -1) {
                return null;
            }
            
            String readyTime = line.substring(timeStart + 1, timeEnd);
            
            // Extract remaining time from "(ready in ...)"
            int readyInStart = line.indexOf("(ready in ");
            if (readyInStart == -1) {
                return null;
            }
            
            int readyInEnd = line.indexOf(")", readyInStart);
            if (readyInEnd == -1) {
                return null;
            }
            
            String readyInStr = line.substring(readyInStart + 10, readyInEnd).trim();
            
            long remainingMs = 0;
            
            if (readyInStr.equals("now")) {
                remainingMs = 0;
            } else {
                // Parse "Xh Ym" or "Xm" format
                String[] parts = readyInStr.split("\\s+");
                for (String part : parts) {
                    if (part.endsWith("h")) {
                        long hours = Long.parseLong(part.substring(0, part.length() - 1));
                        remainingMs += hours * 60 * 60 * 1000;
                    } else if (part.endsWith("m")) {
                        long minutes = Long.parseLong(part.substring(0, part.length() - 1));
                        remainingMs += minutes * 60 * 1000;
                    }
                }
            }
            
            // Extract quantity and name
            // Format: "[h:mm a] quantity name (ready in Xm)"
            String afterTime = line.substring(timeEnd + 1).trim(); // Everything after "]"
            
            // Find where the "(ready in" part starts
            int parenStart = afterTime.indexOf("(ready in");
            String itemInfo = afterTime.substring(0, parenStart).trim();
            
            // Split into quantity and name
            String[] parts = itemInfo.split("\\s+", 2);
            int quantity = 1;
            String itemName = itemInfo;
            
            if (parts.length == 2) {
                try {
                    quantity = Integer.parseInt(parts[0]);
                    itemName = parts[1];
                } catch (NumberFormatException e) {
                    // First part isn't a number, use whole thing as name
                    itemName = itemInfo;
                    quantity = 1;
                }
            }
            
            return new NotificationEntry(line, remainingMs, itemName, quantity, readyTime);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Helper class to hold notification entry data
     */
    private static class NotificationEntry {
        String originalLine;
        long remainingMs;
        String itemName;
        int quantity;
        String readyTime;
        
        NotificationEntry(String line, long timeMs, String name, int qty, String time) {
            this.originalLine = line;
            this.remainingMs = timeMs;
            this.itemName = name;
            this.quantity = qty;
            this.readyTime = time;
        }
        
        String formatForDisplay() {
            String timeRemaining;
            if (remainingMs <= 0) {
                timeRemaining = "0 minutes";
            } else {
                long hours = remainingMs / (60 * 60 * 1000);
                long minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000);
                
                if (hours > 0) {
                    timeRemaining = String.format("%d hour%s %d minute%s",
                        hours, hours != 1 ? "s" : "",
                        minutes, minutes != 1 ? "s" : "");
                } else {
                    timeRemaining = String.format("%d minute%s",
                        minutes, minutes != 1 ? "s" : "");
                }
            }
            
            return String.format("%s - %d %s - (%s)",
                readyTime, quantity, itemName, timeRemaining);
        }
    }
}

