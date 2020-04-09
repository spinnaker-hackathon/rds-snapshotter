package oss.plugin.stage.snapshotter.rds

/**
 * This the the part of the Context map that we care about as input to the stage execution.
 * The data can be key/value pairs or an entire configuration tree.
 */
data class RDSInput(var awsAccount: String, var instanceName: String, var snapshotName: String, var region: String) {}

