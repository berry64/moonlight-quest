package com.limelight.vr;

/**
 * Per-flavor indirection for choosing which activity handles a streaming session.
 *
 * This is the 'quest' flavor implementation: streaming is hosted by the immersive
 * {@link VrGameActivity}, which renders the video onto a movable/resizable panel in
 * a pitch-black VR space. The 'mobile' flavor provides a parallel copy of this class
 * that returns {@link com.limelight.Game} instead.
 *
 * {@link VrGameActivity} reads the same {@code Game.EXTRA_*} intent extras that
 * {@link com.limelight.Game} does, so {@link com.limelight.utils.ServerHelper} builds
 * the exact same intent regardless of flavor — only the target class differs.
 */
public final class VrLaunch {
    private VrLaunch() {}

    /**
     * @return the activity class that should host a streaming session on this flavor.
     */
    public static Class<?> streamActivityClass() {
        return VrGameActivity.class;
    }
}
