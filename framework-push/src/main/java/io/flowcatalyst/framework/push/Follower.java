package io.flowcatalyst.framework.push;

/** What {@link Log#follow} calls. */
public interface Follower {

  /** The log may have grown: the hub then reads after its last id. */
  void grown();

  /**
   * Some instance asked to end the streams of a holder ({@link Log#end}).
   *
   * @param holder the holder
   */
  void end(Holder holder);
}
