package com.limelight.vr;

import com.limelight.Game;

/**
 * Per-flavor indirection for choosing which activity handles a streaming session.
 *
 * This is the 'mobile' flavor implementation: streaming uses the normal
 * {@link com.limelight.Game} activity, exactly as the app always has. The 'quest'
 * flavor provides a parallel copy of this class that returns the immersive VR
 * activity instead.
 *
 * Keeping this indirection in a flavor-specific class lets shared code
 * ({@link com.limelight.utils.ServerHelper}) build the launch intent without any
 * knowledge of, or compile-time dependency on, VR classes.
 */
public final class VrLaunch {
    private VrLaunch() {}

    /**
     * @return the activity class that should host a streaming session on this flavor.
     */
    public static Class<?> streamActivityClass() {
        return Game.class;
    }
}
