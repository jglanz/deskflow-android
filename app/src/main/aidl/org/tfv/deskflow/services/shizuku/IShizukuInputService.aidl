// Runs inside a Shizuku UserService (shell uid), which holds INJECT_EVENTS.
package org.tfv.deskflow.services.shizuku;

import android.view.MotionEvent;

interface IShizukuInputService {
    /** Reserved by Shizuku: called when the user service is being torn down. */
    void destroy() = 16777114;

    /** Uid of the user service process (2000 = shell, 0 = root). */
    int getUid() = 1;

    /**
     * Inject a motion event. Oneway so the caller never blocks; ordering is
     * preserved for oneway calls from one client to the same binder.
     *
     * @param displayId Target display, applied via hidden InputEvent.setDisplayId.
     * @param actionButton For ACTION_BUTTON_PRESS/RELEASE, set via hidden
     *   MotionEvent.setActionButton; 0 otherwise.
     */
    oneway void injectMotionEvent(in MotionEvent event, int displayId, int actionButton) = 2;
}
