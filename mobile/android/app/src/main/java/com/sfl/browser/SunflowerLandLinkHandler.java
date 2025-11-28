package com.sfl.browser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

/**
 * Custom LinkMovementMethod that intercepts sunflower-land.com links
 * and routes them based on the "notifications_only" toggle setting.
 * 
 * If notifications_only is OFF (browser mode):
 *   - Sunflower Land links open in the app's SFL tab
 * 
 * If notifications_only is ON (notifications only mode):
 *   - Links open with the default system handler
 */
public class SunflowerLandLinkHandler extends LinkMovementMethod {
    private final Context context;
    
    public SunflowerLandLinkHandler(Context context) {
        this.context = context;
    }
    
    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return super.onTouchEvent(widget, buffer, event);
        }
        
        int x = (int) event.getX();
        int y = (int) event.getY();
        
        x -= widget.getTotalPaddingLeft();
        y -= widget.getTotalPaddingTop();
        
        x += widget.getScrollX();
        y += widget.getScrollY();
        
        android.text.Layout layout = widget.getLayout();
        int line = layout.getLineForVertical(y);
        int off = layout.getOffsetForHorizontal(line, x);
        
        URLSpan[] links = buffer.getSpans(off, off, URLSpan.class);
        if (links.length > 0) {
            String url = links[0].getURL();
            handleUrl(url);
            return true;
        }
        
        return super.onTouchEvent(widget, buffer, event);
    }
    
    private void handleUrl(String url) {
        Log.d("SunflowerLandLinkHandler", "Handling URL: " + url);
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean notificationsOnly = prefs.getBoolean("only_notifications", true);
        
        // Check if URL is sunflower-land.com
        if (url.contains("sunflower-land.com") && !notificationsOnly) {
            // Browser mode: open in the SFL tab
            try {
                Intent intent = new Intent(context, MainActivity.class);
                intent.putExtra("load_url_in_tab_1", url);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
                Log.d("SunflowerLandLinkHandler", "Opened sunflower-land link in SFL Browser tab");
            } catch (Exception e) {
                Log.e("SunflowerLandLinkHandler", "Failed to open link in app", e);
                openExternally(url);
            }
        } else if (url.contains("sunflower-land.com") && notificationsOnly) {
            // Notifications only mode: open with system handler
            Log.d("SunflowerLandLinkHandler", "Notifications only mode - opening with default handler");
            openExternally(url);
        } else {
            // Non-sunflower-land link: open with system handler
            openExternally(url);
        }
    }
    
    private void openExternally(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d("SunflowerLandLinkHandler", "Opened URL externally: " + url);
        } catch (Exception e) {
            Log.e("SunflowerLandLinkHandler", "Failed to open URL: " + url, e);
        }
    }
}
