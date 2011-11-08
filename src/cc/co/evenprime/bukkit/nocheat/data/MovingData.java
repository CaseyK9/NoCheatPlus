package cc.co.evenprime.bukkit.nocheat.data;

/**
 * Player specific data for the moving check group
 */
public class MovingData extends Data {

    public int                    jumpPhase;

    public final PreciseLocation  runflySetBackPoint      = new PreciseLocation();

    public double                 runflyVL;

    public double                 vertFreedom;
    public double                 vertVelocity;
    public int                    vertVelocityCounter;
    public double                 horizFreedom;
    public int                    horizVelocityCounter;

    public double                 nofallVL;
    public float                  fallDistance;
    public float                  lastAddedFallDistance;

    public double                 horizontalBuffer;
    public int                    bunnyhopdelay;

    public int                    morePacketsCounter;
    public int                    morePacketsBuffer       = 50;
    public int                    packets;

    public final PreciseLocation  morePacketsSetbackPoint = new PreciseLocation();
    public double                 morePacketsVL;

    public final PreciseLocation  teleportTo              = new PreciseLocation();

    public int                    lastElapsedIngameSeconds;

    public final ExecutionHistory history                 = new ExecutionHistory();

    public final PreciseLocation  from                    = new PreciseLocation();
    public final PreciseLocation  to                      = new PreciseLocation();

    public boolean                fromOnOrInGround;
    public boolean                toOnOrInGround;

    @Override
    public void clearCriticalData() {
        teleportTo.reset();
        jumpPhase = 0;
        runflySetBackPoint.reset();
        fallDistance = 0;
        lastAddedFallDistance = 0;
        bunnyhopdelay = 0;
        morePacketsBuffer = 50;
        morePacketsSetbackPoint.reset();
        lastElapsedIngameSeconds = 0;
        morePacketsCounter = 0;
    }
}
