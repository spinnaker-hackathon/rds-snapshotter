package oss.plugin.stage.snapshotter.rds

/**
 * This Output is returned from the stage and can be used later in other stages.
 * In this case, the output contains the actual number of seconds the stage waits.
 */
data class Output(var name: String) {}
