package oss.plugin.stage.snapshotter.rds

/**
 * Context is used within the stage itself and returned to the Orca pipeline execution.
 */
data class Context(var errorMessage: String) {}
